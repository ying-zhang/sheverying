package moe.shizuku.manager.system

import android.os.Handler
import android.os.Looper
import android.util.Log
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Reboots the phone from the Sheverying service. `reboot` is allowed for the
 * shell uid that service runs as, the same way `adb reboot` is. The app uid
 * cannot call it.
 */
object PhoneReboot {

    private const val TAG = "PhoneReboot"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "phone-reboot").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    fun request(onDone: (Boolean) -> Unit) {
        if (!Shizuku.pingBinder()) {
            Log.i(TAG, "service is not running")
            main.post { onDone(false) }
            return
        }
        executor.execute {
            val started = runCatching { execReboot() }.getOrElse {
                Log.w(TAG, "reboot failed", it)
                false
            }
            main.post { onDone(started) }
        }
    }

    private fun execReboot(): Boolean {
        val process = IShizukuService.Stub.asInterface(Shizuku.getBinder())
            .newProcess(
                arrayOf(
                    "sh", "-c",
                    "if [ -x /system/bin/reboot ]; then /system/bin/reboot; else reboot; fi"
                ),
                null,
                null
            ) ?: return false
        val finished = process.waitForTimeout(4_000, TimeUnit.MILLISECONDS.name)
        if (!finished) {
            // The phone is going down and the process did not exit in time.
            return true
        }
        val code = process.exitValue()
        if (code != 0) {
            Log.w(TAG, "reboot exit=$code")
        }
        return code == 0
    }
}
