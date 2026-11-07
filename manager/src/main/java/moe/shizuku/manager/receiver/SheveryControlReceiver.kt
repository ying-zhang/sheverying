package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import moe.shizuku.manager.ktx.logw
import moe.shizuku.manager.service.SheveryNotificationManager
import moe.shizuku.manager.service.WatchdogManager
import moe.shizuku.manager.worker.AdbStartWorker

class SheveryControlReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_START_SERVER = "moe.shizuku.manager.action.START_SERVER"
        const val ACTION_STOP_SERVER = "moe.shizuku.manager.action.STOP_SERVER"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START_SERVER -> {
                val appCtx = context.applicationContext
                WatchdogManager.clearUserStopRequest(appCtx)
                if (ShizukuSettings.getLastLaunchMode() == LaunchMethod.ADB) {
                    AdbStartWorker.enqueueNow(appCtx)
                    ShizukuReceiverStarter.updateNotification(appCtx,
                        AdbStartWorker.bannerStateFor(appCtx)
                    )
                }
                else WatchdogManager.attemptRestart(appCtx)
            }
            ACTION_STOP_SERVER -> {
                val pendingResult = goAsync()
                val app = context.applicationContext
                BroadcastWork.control.run(pendingResult::finish, onTimeout = {
                    logw("Stop broadcast deadline reached; stop completion is unconfirmed")
                }) {
                    try {
                        WatchdogManager.stopServerAndWait(app, userInitiated = true)
                        SheveryNotificationManager.updateNotification(app)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logw("Stop server failed", e)
                    }
                }
            }
        }
    }
}
