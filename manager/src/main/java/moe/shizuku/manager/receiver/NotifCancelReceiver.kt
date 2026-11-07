package moe.shizuku.manager.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NotifCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        moe.shizuku.manager.service.WatchdogManager.requestStopServer(context, userInitiated = true)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(ShizukuReceiverStarter.NOTIFICATION_ID)
    }
}
