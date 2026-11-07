package moe.shizuku.manager.accessibility

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.util.LinkedHashSet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Turns wireless debugging on through the accessibility service:
 * confirms the system "allow wireless debugging" dialog and, when the
 * pairing screen is showing, reads the 6-digit code and pairs locally.
 */
object WirelessDebugAccess {

    private const val TAG = "WirelessDebugAccess"
    private const val KEY_ENABLED = "a11y_wireless_debug"
    private const val PLACEHOLDER = "com.example.placeholder/.None"
    private val placeholderName: ComponentName? = ComponentName.unflattenFromString(PLACEHOLDER)

    private val repairLock = Any()
    private val repairGeneration = AtomicInteger()
    private val repairExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wireless-debug-a11y-repair").apply { isDaemon = true }
    }

    fun isEnabled(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        if (enabled) {
            ShizukuSettings.getPreferences().edit().putBoolean(KEY_ENABLED, true).apply()
            ensureEnabled(app)
            return
        }
        synchronized(repairLock) {
            repairGeneration.incrementAndGet()
            ShizukuSettings.getPreferences().edit().putBoolean(KEY_ENABLED, false).apply()
            AccessibilityManager.disableService(app, serviceId(app))
            AccessibilityKeepAliveStore.removePinned(serviceId(app))
            AccessibilityKeepAliveStore.setKeepAliveEnabled(false)
        }
        AccessibilityDaemonService.reconcile(app)
    }

    fun serviceId(context: Context): String {
        return ComponentName(context, WirelessDebugAccessibilityService::class.java).flattenToString()
    }

    /**
     * Best-effort enable. Succeeds once this app can write secure settings
     * (after the privileged service has granted it). Also pins the service so
     * the keep-alive daemon puts it back if a ROM clears the list.
     */
    fun ensureEnabled(context: Context) {
        if (!isEnabled()) return
        val app = context.applicationContext
        val id = serviceId(app)
        AccessibilityKeepAliveStore.setKeepAliveEnabled(true)
        AccessibilityKeepAliveStore.addPinned(id)
        if (!AccessibilityManager.isServiceEnabled(app, id)) {
            AccessibilityManager.enableService(app, id)
        }
        if (!isEnabled()) {
            AccessibilityManager.disableService(app, id)
            AccessibilityKeepAliveStore.removePinned(id)
            AccessibilityKeepAliveStore.setKeepAliveEnabled(false)
            AccessibilityDaemonService.reconcile(app)
            return
        }
        scheduleRepair(app, id)
        AccessibilityDaemonService.reconcile(app)
    }

    private fun scheduleRepair(context: Context, id: String) {
        val generation = repairGeneration.get()
        repairExecutor.execute {
            try {
                repairThroughShell(context, id, generation)
            } catch (t: Throwable) {
                Log.w(TAG, "accessibility repair failed: ${t.message}")
            }
        }
    }

    /**
     * Puts this app's wireless-debugging accessibility service back on the
     * enabled list. If a ROM has parked it in `Crashed services` (seen on
     * vivo and Huawei), writing the same list does not clear that set. A
     * placeholder value is written first so the framework treats the component
     * as removed, then the real list is restored.
     *
     * The two-second wait stays in this process. Turning the switch off bumps
     * [repairGeneration] under [repairLock], so a repair that is already
     * waiting does not write the old list back. The final list is read again
     * under that lock and merged with the pre-reset snapshot.
     */
    private fun repairThroughShell(context: Context, id: String, generation: Int) {
        if (!stillWanted(generation)) return
        if (!Shizuku.pingBinder()) return
        val component = ComponentName.unflattenFromString(id) ?: return
        val crashed = isCrashed(component)
        val present = AccessibilityManager.isServiceEnabled(context, id)
        if (!crashed && present) return
        if (!stillWanted(generation)) return

        val before = if (crashed) {
            AccessibilityManager.getEnabledServices(context)
        } else {
            emptySet()
        }
        if (crashed) {
            if (!putEnabledServices(PLACEHOLDER)) return
            try {
                Thread.sleep(2_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        val planned = synchronized(repairLock) {
            val wanted = stillWanted(generation)
            val now = AccessibilityManager.getEnabledServices(context)
            val merged = LinkedHashSet<ComponentName>()
            if (crashed) collectOthers(before, component, merged)
            collectOthers(now, component, merged)
            if (wanted) merged.add(component)
            merged.joinToString(":") { it.flattenToString() }
        }
        if (!putEnabledServices(planned)) {
            if (crashed) {
                val fallback = LinkedHashSet<ComponentName>()
                collectOthers(before, component, fallback)
                if (stillWanted(generation)) fallback.add(component)
                putEnabledServices(fallback.joinToString(":") { it.flattenToString() })
            }
            return
        }
        if (!stillWanted(generation)) {
            synchronized(repairLock) {
                if (!stillWanted(generation)) {
                    AccessibilityManager.disableService(context, id)
                }
            }
        }
    }

    private fun stillWanted(generation: Int): Boolean {
        return generation == repairGeneration.get() && isEnabled()
    }

    private fun collectOthers(
        services: Set<ComponentName>,
        ours: ComponentName,
        into: LinkedHashSet<ComponentName>
    ) {
        for (item in services) {
            if (item == ours || item == placeholderName) continue
            into.add(item)
        }
    }

    private fun isCrashed(component: ComponentName): Boolean {
        val output = runShell(
            "dumpsys accessibility 2>/dev/null | tr '\\n' ' ' | grep -oE 'Crashed services:\\{[^}]*\\}' || true",
            8_000
        )?.stdout ?: return false
        return output.contains(component.flattenToString()) ||
            output.contains(component.flattenToShortString())
    }

    private fun putEnabledServices(value: String): Boolean {
        val script = if (value.isEmpty()) {
            "settings put secure enabled_accessibility_services ''"
        } else {
            "settings put secure accessibility_enabled 1 && settings put secure enabled_accessibility_services ${shellQuote(value)}"
        }
        val result = runShell(script, 5_000) ?: return false
        return result.exit == 0
    }

    private data class ShellResult(val exit: Int, val stdout: String)

    private fun runShell(script: String, timeoutMs: Long): ShellResult? {
        if (!Shizuku.pingBinder()) return null
        val process = try {
            IShizukuService.Stub.asInterface(Shizuku.getBinder())
                .newProcess(arrayOf("sh", "-c", script), null, null)
        } catch (_: Throwable) {
            return null
        } ?: return null
        return try {
            val finished = process.waitForTimeout(timeoutMs, TimeUnit.MILLISECONDS.name)
            if (!finished) {
                runCatching { process.destroy() }
                null
            } else {
                val text = ParcelFileDescriptor.AutoCloseInputStream(process.inputStream)
                    .bufferedReader()
                    .use { it.readText() }
                ShellResult(process.exitValue(), text)
            }
        } catch (_: Throwable) {
            runCatching { process.destroy() }
            null
        }
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    fun openSystemSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
