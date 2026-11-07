package moe.shizuku.manager.receiver

import android.Manifest.permission.NEARBY_WIFI_DEVICES
import android.os.UserManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import moe.shizuku.manager.adb.AdbArm
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.awaitCompletion
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.shizuku.Shizuku

class BootCompleteReceiver : BroadcastReceiver() {

    companion object {
        private const val SDK_ANDROID_17 = 37
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED != intent.action
            && Intent.ACTION_BOOT_COMPLETED != intent.action
            && Intent.ACTION_MY_PACKAGE_REPLACED != intent.action) {
            return
        }

        val pending = goAsync()
        val app = context.applicationContext
        BroadcastWork.boot.run(pending::finish, onTimeout = {
            Log.w(AppConstants.TAG, "Boot/update recovery scheduling deadline; persistence is unconfirmed")
        }) {
            try {
                recover(app)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(AppConstants.TAG, "Boot/update recovery scheduling failed", e)
            }
        }
    }

    private suspend fun recover(context: Context) {
        moe.shizuku.manager.utils.LegacyUpdateCleanup.cancel(context)
        val safetyNet = if (UserHandleCompat.myUserId() == 0 &&
            context.getSystemService(UserManager::class.java)?.isUserUnlocked == true) {
            moe.shizuku.manager.worker.AdbRecoveryWorker.schedule(context)
        } else null
        try {
            recoverStartup(context)
        } finally {
            safetyNet?.result?.awaitCompletion()
        }
    }

    private suspend fun recoverStartup(context: Context) {
        if (UserHandleCompat.myUserId() > 0 || Shizuku.pingBinder()) {
            // Server already alive (or secondary user): still ensure ErrorProtect
            // watchdog keeps running so later deaths are caught.
            try {
                moe.shizuku.manager.service.WatchdogManager.init(context.applicationContext)
                moe.shizuku.manager.service.WatchdogManager.reconcileService(context.applicationContext)
            } catch (_: Throwable) {}
            return
        }

        // WorkManager uses credential-protected storage. BOOT_COMPLETED is
        // delivered after first unlock even if this process dies in Direct Boot.
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked != true) {
            Log.i(AppConstants.TAG, "Deferring boot work until first user unlock")
            return
        }
        if (moe.shizuku.manager.service.WatchdogManager.isUserStopRequested()) {
            Log.i(AppConstants.TAG, "Skipping boot start after explicit user stop")
            return
        }

        // Root startup must not depend on wireless ADB permissions or toggles.
        if (ShizukuSettings.getLastLaunchMode() == LaunchMethod.ROOT) {
            if (ShizukuSettings.getStartOnBoot()) ShizukuReceiverStarter.start(context)
            return
        }

        val isAdbCandidate = ShizukuSettings.getStartOnBootAdb() && (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ||
            EnvironmentUtils.getAdbTcpPort() > 0 ||
            ShizukuSettings.isTcpMode() ||
            EnvironmentUtils.isTV(context)
        )

        if (isAdbCandidate) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // This build arms adbd through WRITE_SECURE_SETTINGS.
                if (!AdbArm.canArm(context)) {
                    moe.shizuku.manager.service.StartupNotificationManager.showFailed(
                        context,
                        context.getString(moe.shizuku.manager.R.string.notification_startup_no_permission)
                    )
                    return
                }
                // On API 33+ (Android 13+), Wi-Fi/mDNS discovery requires NEARBY_WIFI_DEVICES
                val needsWifiLink = moe.shizuku.manager.worker.AdbRecoveryPolicy.needsWifiLink(
                    EnvironmentUtils.isTV(context),
                    EnvironmentUtils.getLiveAdbTcpPorts().isNotEmpty()
                )
                if (needsWifiLink
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && context.checkSelfPermission(NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                    moe.shizuku.manager.service.StartupNotificationManager.showFailed(
                        context,
                        context.getString(moe.shizuku.manager.R.string.notification_startup_no_permission)
                    )
                    return
                }
                // On API 37+ (Android 17+), local network access requires ACCESS_LOCAL_NETWORK.
                // Android 16 (API 36) temporarily uses NEARBY_WIFI_DEVICES as the local-network
                // gate and local network access stays open by default, so requiring
                // ACCESS_LOCAL_NETWORK there would fail on a permission the app never requests
                // below Android 17 (see HomeActivity.buildLocalNetworkPermissionState).
                val ACCESS_LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
                if (needsWifiLink
                    && Build.VERSION.SDK_INT >= SDK_ANDROID_17
                    && context.checkSelfPermission(ACCESS_LOCAL_NETWORK_PERMISSION)
                            != PackageManager.PERMISSION_GRANTED) {
                    moe.shizuku.manager.service.StartupNotificationManager.showFailed(
                        context,
                        context.getString(moe.shizuku.manager.R.string.notification_startup_no_permission)
                    )
                    return
                }
            }
            // Enable debugging before discovery. The worker re-arms after unlock.
            AdbArm.arm(context)
            if (!ShizukuReceiverStarter.start(context)) {
                Log.w(AppConstants.TAG, "Boot/update ADB recovery was skipped or persistence was not confirmed")
            }
        } else {
            Log.w(AppConstants.TAG, "No support start on boot")
        }
    }

}
