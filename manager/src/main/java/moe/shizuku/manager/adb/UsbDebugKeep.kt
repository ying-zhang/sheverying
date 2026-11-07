package moe.shizuku.manager.adb

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import moe.shizuku.manager.ShizukuSettings

/**
 * Keeps USB debugging on by writing Settings.Global.ADB_ENABLED.
 * Requires WRITE_SECURE_SETTINGS, which the server grants after the first start.
 */
object UsbDebugKeep {

    private var observer: ContentObserver? = null

    fun register(context: Context) {
        if (observer != null) return
        val app = context.applicationContext
        val watch = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reassert(app)
            }
        }
        observer = watch
        app.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ADB_ENABLED),
            false,
            watch
        )
        reassert(app)
    }

    fun reassert(context: Context) {
        if (!ShizukuSettings.isKeepUsbDebugging()) return
        val app = context.applicationContext
        val enabled = runCatching {
            Settings.Global.getInt(app.contentResolver, Settings.Global.ADB_ENABLED, 0)
        }.getOrDefault(0)
        if (enabled == 1) return
        AdbArm.setUsbDebuggingEnabled(app, true)
    }
}
