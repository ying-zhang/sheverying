package moe.shizuku.manager.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import moe.shizuku.manager.R
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import moe.shizuku.manager.adb.AdbArm
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.UserHandleCompat
import moe.shizuku.manager.utils.awaitCompletion
import moe.shizuku.manager.worker.AdbStartWorker

object ShizukuReceiverStarter {

    const val NOTIFICATION_ID = 1447
    internal const val CHANNEL_ID = "shizuku_receiver_starter"

    enum class WorkerState {
        AWAITING_WIFI,
        AWAITING_RETRY,
        RUNNING,
        // STOPPED cancels any stale progress UI (see updateNotification();the
        // terminal path re-posts the error after it on the same NOTIFICATION_ID.)
        STOPPED
    }

    /** Called from the bounded broadcast job, never from the main thread. */
    suspend fun start(context: Context, forceStart: Boolean = false): Boolean {
        if (moe.shizuku.manager.service.WatchdogManager.isUserStopRequested()) return false
        if ((UserHandleCompat.myUserId() > 0 || ShizukuStateMachine.isRunning()) && !forceStart) return false

        // WorkManager owns deduplication. A competing caller must await the same durable work,
        // rather than reporting "already starting" before anything has reached the database.
        return when (ShizukuSettings.getLastLaunchMode()) {
            LaunchMethod.ROOT -> {
                if (!ShizukuSettings.getStartOnBoot()) false
                else {
                    moe.shizuku.manager.worker.RootStartWorker.enqueue(context).result.awaitCompletion()
                    true
                }
            }
            LaunchMethod.ADB -> {
                if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || EnvironmentUtils.isTelevision() ||
                        EnvironmentUtils.getAdbTcpPort() > 0) && AdbArm.canArm(context)) {
                    val scheduled = AdbStartWorker.enqueue(context)
                    // Notification failures cannot release the broadcast before the database write.
                    if (scheduled) runCatching {
                        moe.shizuku.manager.service.StartupNotificationManager.dismiss(context)
                    }.onFailure { Log.w(AppConstants.TAG, "Could not dismiss startup notification", it) }
                    scheduled
                } else {
                    showPermissionErrorNotification(context)
                    false
                }
            }
            else -> false
        }
    }

    fun buildNotification(context: Context, msg: String? = null): Notification {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(context)

        val cancelIntent = Intent(context, NotifCancelReceiver::class.java)
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context, 0x7F010001, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val attemptNowIntent = Intent(context, NotifAttemptReceiver::class.java)
        val attemptNowPendingIntent = PendingIntent.getBroadcast(
            context, 0x7F010002, attemptNowIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val restoreIntent = Intent(context, NotifRestoreReceiver::class.java)
        val restorePendingIntent = PendingIntent.getBroadcast(
            context, 0x7F010003, restoreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val wifiIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/HmnDev-Tech/shevery/wiki#shizuku-isnt-starting-on-boot-for-me"))
        val wifiPendingIntent = PendingIntent.getActivity(
            context, 0x7F010004, wifiIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nb = NotificationCompat.Builder(context, CHANNEL_ID)

        if (msg != null) nb.setContentText(msg)

        return nb
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(context.getString(R.string.wadb_notification_title))
            .setOngoing(true)
            .setSilent(true)
            .addAction(R.drawable.ic_server_restart, context.getString(R.string.wadb_notification_attempt_now), attemptNowPendingIntent)
            .addAction(R.drawable.ic_close_24, context.getString(android.R.string.cancel), cancelPendingIntent)
            .setDeleteIntent(restorePendingIntent)
            .setContentIntent(wifiPendingIntent)
            .build()
    }

    fun updateNotification(context: Context, state: WorkerState) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // STOPPED means "no notification" — explicitly cancel any stale one
        // (previously returned without cancelling, leaving a stale "stopped"
        // notification visible even after the service started successfully).
        if (state == WorkerState.STOPPED) {
            nm.cancel(NOTIFICATION_ID)
            return
        }
        val msgId = when (state) {
            WorkerState.AWAITING_WIFI -> R.string.wadb_notification_wifi_required
            WorkerState.AWAITING_RETRY -> R.string.wadb_notification_retry
            else -> null
        }
        val msg = if (msgId != null) context.getString(msgId) else null
        nm.notify(NOTIFICATION_ID, buildNotification(context, msg))
    }

    private fun showPermissionErrorNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_server_error_24dp)
            .setContentTitle(context.getString(R.string.wadb_error_title))
            .setContentText(context.getString(R.string.wadb_permission_error_notification_content))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        val wikiUrl = "https://github.com/HmnDev-Tech/shevery/wiki#shizuku-isnt-starting-on-boot-for-me"
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(wikiUrl))
        val pendingIntent = PendingIntent.getActivity(
            context, 0x7F010005, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        builder.setContentIntent(pendingIntent)

        nm.notify(NOTIFICATION_ID, builder.build())
    }

    internal fun ensureChannel(context: Context) {
        // Idempotent system operation; never publish success before creation completes.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.wadb_notification_title),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }
}
