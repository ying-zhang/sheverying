package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Debug hook kept so the lab activity still registers. Provider injection was removed. */
class TestKeysReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit

    companion object {
        const val ACTION_TEST_KEYS = "moe.shizuku.manager.TEST_KEYS"
    }
}
