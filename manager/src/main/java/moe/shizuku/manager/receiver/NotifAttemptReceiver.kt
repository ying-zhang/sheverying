package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.shizuku.manager.worker.AdbStartWorker

class NotifAttemptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AdbStartWorker.enqueueNow(context)
        // An explicit retry replaces a delayed attempt and starts immediately.
        ShizukuReceiverStarter.updateNotification(context, AdbStartWorker.bannerStateFor(context))
    }
}
