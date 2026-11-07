package moe.shizuku.manager.shell

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.utils.Logger.LOGGER
import rikka.shizuku.Shizuku

object ShellBinderRequestHandler {

    private val REQUEST_BINDER_ACTIONS = setOf(
        "rikka.shizuku.intent.action.REQUEST_BINDER",
        "${BuildConfig.APPLICATION_ID}.intent.action.REQUEST_BINDER",
        "moe.shizuku.privileged.api.intent.action.REQUEST_BINDER",
    )

    fun handleRequest(context: Context, intent: Intent): Boolean {
        if (intent.action !in REQUEST_BINDER_ACTIONS) {
            return false
        }

        val binder = intent.getBundleExtra("data")?.getBinder("binder") ?: return false
        val shizukuBinder = Shizuku.getBinder()
        if (shizukuBinder == null) {
            LOGGER.w("Binder not received or Shizuku service not running")
        }

        val data = Parcel.obtain()
        return try {
            data.writeStrongBinder(shizukuBinder)
            data.writeString(context.applicationInfo.sourceDir)
            binder.transact(1, data, null, IBinder.FLAG_ONEWAY)
            true
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        } finally {
            data.recycle()
        }
    }
}
