package moe.shizuku.manager.adb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
/**
 * Single choke point for arming / disarming adbd.
 *
 * Writes go through WRITE_SECURE_SETTINGS, granted back by the server after
 * the first successful start. That permission can also set
 * `adb_allowed_connection_time` so pairing keys do not expire.
 */
object AdbArm {

    private const val TAG = "AdbArm"

    const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    const val ADB_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time"

    enum class Method {
        WRITE_SECURE_SETTINGS,
        NONE
    }

    fun hasWriteSecureSettings(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Which method [arm] would use right now. Cheap, synchronous and safe to
     * call from a [android.content.BroadcastReceiver].
     */
    fun method(context: Context): Method = when {
        hasWriteSecureSettings(context) -> Method.WRITE_SECURE_SETTINGS
        else -> Method.NONE
    }

    fun canArm(context: Context): Boolean = method(context) != Method.NONE

    /**
     * Turn USB debugging + wireless debugging on. Idempotent: writing a value
     * that is already set is a no-op for both paths.
     *
     * @return the method used, or [Method.NONE] when neither path is available
     *         (in which case nothing was written).
     */
    fun arm(context: Context): Method {
        val app = context.applicationContext
        val method = method(app)
        when (method) {
            Method.WRITE_SECURE_SETTINGS -> writeWithSecureSettings(app)
            Method.NONE -> Log.d(TAG, "cannot arm adbd: no WRITE_SECURE_SETTINGS")
        }
        return method
    }

    /**
     * Re-arm only `adb_wifi_enabled` (0 → 1 recovery path). Used by the unlock
     * receiver, the mDNS observer and [moe.shizuku.manager.worker.WifiDebugReassert].
     * Never throws, so it is safe to call from a receiver.
     */
    fun armWifiEnabled(context: Context): Boolean {
        val app = context.applicationContext
        return when (method(app)) {
            Method.WRITE_SECURE_SETTINGS -> putInt(app, ADB_WIFI_ENABLED, 1)
            Method.NONE -> false
        }
    }

    fun disarmWifiEnabled(context: Context): Boolean {
        val app = context.applicationContext
        return when (method(app)) {
            Method.WRITE_SECURE_SETTINGS -> putInt(app, ADB_WIFI_ENABLED, 0)
            Method.NONE -> false
        }
    }

    /** Turn USB debugging off (honours the auto-disable-USB-debugging toggle). */
    fun disarmUsbDebugging(context: Context): Boolean {
        return setUsbDebuggingEnabled(context, false)
    }

    fun setUsbDebuggingEnabled(context: Context, enabled: Boolean): Boolean {
        val app = context.applicationContext
        val expected = if (enabled) 1 else 0
        val written = when (method(app)) {
            Method.WRITE_SECURE_SETTINGS ->
                putInt(app, Settings.Global.ADB_ENABLED, expected)
            Method.NONE -> false
        }
        return written && runCatching {
            Settings.Global.getInt(app.contentResolver, Settings.Global.ADB_ENABLED, 0) == expected
        }.getOrDefault(false)
    }

    /**
     * 0 means the wireless-debugging key never expires. The system default
     * when this is unset is 7 days, after which adbd drops the key and 5555
     * stops accepting connections. This must be written after the server has
     * granted WRITE_SECURE_SETTINGS; an earlier arm() cannot stick.
     */
    private fun writeWithSecureSettings(context: Context): Boolean {
        return try {
            val cr = context.contentResolver
            Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Settings.Global.putInt(cr, ADB_WIFI_ENABLED, 1)
            }
            Settings.Global.putLong(cr, ADB_ALLOWED_CONNECTION_TIME, 0L)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "arming adbd via WRITE_SECURE_SETTINGS failed", e)
            false
        }
    }

    private fun putInt(context: Context, key: String, value: Int): Boolean {
        return try {
            Settings.Global.putInt(context.contentResolver, key, value)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "writing $key=$value failed", e)
            false
        }
    }
}
