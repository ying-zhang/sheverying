package moe.shizuku.manager.lock

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Hides or restores the swipe lock by running `locksettings` inside the
 * Sheverying service. That process is shell (uid 2000) or root, which is the
 * same identity `adb shell locksettings` uses. The app uid cannot call it.
 *
 * A PIN, pattern, or password makes the command fail; the existing credential
 * is left as it is. Some vendor lock screens ignore the flag even when the
 * command succeeds, so the result is checked with `get-disabled`.
 */
object LockScreenKeep {

    private const val TAG = "LockScreenKeep"
    private const val TIMEOUT_MS = 8_000L

    enum class Outcome {
        APPLIED,
        SERVICE_DOWN,
        FAILED
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lock-screen-keep").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    fun apply(onDone: ((Outcome) -> Unit)? = null) {
        if (!Shizuku.pingBinder()) {
            Log.i(TAG, "service is not running")
            deliver(onDone, Outcome.SERVICE_DOWN)
            return
        }
        executor.execute {
            val outcome = runCatching { applyNow() }.getOrElse {
                Log.w(TAG, "lock screen command failed", it)
                Outcome.FAILED
            }
            deliver(onDone, outcome)
        }
    }

    private fun applyNow(): Outcome {
        val disable = ShizukuSettings.isDisableLockScreen()
        val current = queryDisabledWithRetry()
        if (current == null) {
            return Outcome.FAILED
        }
        if (current == disable) {
            Log.i(TAG, "lock screen disabled=$current, unchanged")
            return Outcome.APPLIED
        }
        val verb = if (disable) "true" else "false"
        val set = runShell("set-disabled $verb")
        if (set == null || set.exit != 0) {
            Log.w(TAG, "set-disabled $verb failed ${set?.diagnostic() ?: "service unavailable"}")
            return Outcome.FAILED
        }
        val after = queryDisabledWithRetry()
        if (after != disable) {
            Log.w(TAG, "set-disabled $verb did not stick, now=$after")
            return Outcome.FAILED
        }
        Log.i(TAG, "lock screen disabled=$after")
        return Outcome.APPLIED
    }

    private fun queryDisabledWithRetry(): Boolean? = LockScreenQuery(
        read = { runShell("get-disabled") },
        pause = { Thread.sleep(it) },
        log = { Log.w(TAG, it) },
    ).disabled()

    private fun runShell(args: String): LockScreenCommandResult? {
        if (!Shizuku.pingBinder()) return null
        val started = SystemClock.elapsedRealtime()
        val script = "if [ -x /system/bin/locksettings ]; then LS=/system/bin/locksettings; else LS=locksettings; fi; \"\$LS\" $args 2>&1"
        val process = try {
            IShizukuService.Stub.asInterface(Shizuku.getBinder())
                .newProcess(arrayOf("sh", "-c", script), null, null)
        } catch (t: Throwable) {
            return LockScreenCommandResult(null, "newProcess failed: ${t.javaClass.simpleName}: ${t.message}",
                SystemClock.elapsedRealtime() - started, transientFailure = t is RemoteException)
        } ?: return null
        return try {
            val finished = process.waitForTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS.name)
            if (!finished) {
                runCatching { process.destroy() }
                LockScreenCommandResult(null, "", SystemClock.elapsedRealtime() - started, timedOut = true)
            } else {
                val text = ParcelFileDescriptor.AutoCloseInputStream(process.inputStream)
                    .bufferedReader()
                    .use { it.readText() }
                LockScreenCommandResult(process.exitValue(), text, SystemClock.elapsedRealtime() - started)
            }
        } catch (t: Throwable) {
            runCatching { process.destroy() }
            LockScreenCommandResult(null, "read failed: ${t.javaClass.simpleName}: ${t.message}",
                SystemClock.elapsedRealtime() - started, transientFailure = t is RemoteException)
        }
    }

    private fun deliver(onDone: ((Outcome) -> Unit)?, outcome: Outcome) {
        if (onDone == null) return
        main.post { onDone(outcome) }
    }

}
