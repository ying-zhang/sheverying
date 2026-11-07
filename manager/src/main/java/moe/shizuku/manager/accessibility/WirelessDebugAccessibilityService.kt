package moe.shizuku.manager.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.worker.AdbStartWorker
import java.util.concurrent.Executors

/**
 * Confirms the system wireless-debugging consent dialog and, when Developer
 * options shows a pairing code plus host:port, pairs without typing the code.
 */
class WirelessDebugAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!WirelessDebugAccess.isEnabled() || event == null) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != SYSTEM_UI && pkg != SETTINGS) return
        val root = rootInActiveWindow ?: return
        try {
            val nodes = collect(root)
            val texts = nodes.mapNotNull { label(it).takeIf { text -> text.isNotEmpty() } }
            if (pkg == SYSTEM_UI) {
                confirmWirelessConsent(nodes, texts, event.className?.toString().orEmpty())
            }
            maybePair(texts.joinToString("\n"))
        } finally {
            @Suppress("DEPRECATION")
            runCatching { root.recycle() }
        }
    }

    override fun onInterrupt() = Unit

    private fun confirmWirelessConsent(
        nodes: List<AccessibilityNodeInfo>,
        texts: List<String>,
        eventClass: String
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastConfirmedAt < CONFIRM_GAP_MS) return
        if (!isWirelessConsent(eventClass, texts)) return
        val always = nodes.firstOrNull { it.isCheckable && isAlwaysAllow(label(it)) }
        if (always != null && !always.isChecked) {
            always.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        val allow = nodes.firstOrNull { isAllowButton(label(it)) } ?: return
        if (allow.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            lastConfirmedAt = now
            Log.i(TAG, "Confirmed wireless debugging dialog")
            moe.shizuku.manager.worker.AdbNetworkObserver.onWirelessDebugEnabled(applicationContext)
        }
    }

    private fun maybePair(blob: String) {
        if (!ShizukuSettings.isAutoPairWirelessDebug()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val pairing = WirelessPairingReader.find(blob) ?: return
        val key = "${pairing.code}@${pairing.port}"
        val now = SystemClock.elapsedRealtime()
        if (key == lastPairKey && now - lastPairAt < PAIR_GAP_MS) return
        if (!pairingGate.compareAndSet(false, true)) return
        lastPairKey = key
        lastPairAt = now
        try {
            pairExecutor.execute {
                try {
                    val adbKey = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
                    val client = AdbPairingClient("127.0.0.1", pairing.port, pairing.code, adbKey)
                    val ok = try {
                        client.start()
                    } finally {
                        client.close()
                    }
                    Log.i(TAG, "Auto pair port=${pairing.port} ok=$ok")
                    if (ok) {
                        AdbStartWorker.onWirelessReady(applicationContext)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Auto pair failed: ${t.message}")
                } finally {
                    pairingGate.set(false)
                }
            }
        } catch (t: Throwable) {
            pairingGate.set(false)
            Log.w(TAG, "Auto pair was not started: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "WirelessDebugA11y"
        private const val SYSTEM_UI = "com.android.systemui"
        private const val SETTINGS = "com.android.settings"
        private const val CONFIRM_GAP_MS = 2_000L
        private const val PAIR_GAP_MS = 20_000L

        @Volatile
        private var lastConfirmedAt = 0L

        @Volatile
        private var lastPairAt = 0L

        @Volatile
        private var lastPairKey = ""

        private val pairingGate = java.util.concurrent.atomic.AtomicBoolean(false)
        private val pairExecutor = Executors.newSingleThreadExecutor()

        @Volatile
        var instance: WirelessDebugAccessibilityService? = null
            private set

        internal fun isWirelessConsent(eventClass: String, texts: Collection<String>): Boolean {
            if (eventClass.contains("UsbDebugging")) return false
            val lines = texts.map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.any { it.contains("USB 调试") || it.contains("Allow USB debugging", ignoreCase = true) }) {
                return false
            }
            if (eventClass.contains("WifiDebugging")) return true
            val blob = lines.joinToString("\n")
            val question = blob.contains("无线调试") || blob.contains("wireless debugging", ignoreCase = true)
            return question && lines.any { isAllowButton(it) }
        }

        internal fun isAlwaysAllow(text: String): Boolean {
            val value = text.trim()
            return value.contains("始终允许") || value.contains("一律允许") ||
                value.contains("Always allow", ignoreCase = true)
        }

        internal fun isAllowButton(text: String): Boolean {
            val value = text.trim()
            return value == "允许" || value.equals("Allow", ignoreCase = true)
        }
    }
}

internal object WirelessPairingReader {
    private val codePattern = Regex("(?<!\\d)(\\d{6})(?!\\d)")
    private val endpointPattern = Regex("(\\d{1,3}(?:\\.\\d{1,3}){3})\\s*[:：]\\s*(\\d{2,5})")

    data class Pairing(val code: String, val host: String, val port: Int)

    fun find(text: String): Pairing? {
        val pairingScreen = text.contains("配对") || text.contains("pairing", ignoreCase = true)
        if (!pairingScreen) return null
        val code = codePattern.find(text)?.groupValues?.getOrNull(1) ?: return null
        val endpoint = endpointPattern.find(text) ?: return null
        val port = endpoint.groupValues[2].toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return Pairing(code, endpoint.groupValues[1], port)
    }
}

private fun collect(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
    val out = ArrayList<AccessibilityNodeInfo>()
    val stack = ArrayDeque<AccessibilityNodeInfo>()
    stack.add(root)
    while (stack.isNotEmpty()) {
        val node = stack.removeLast()
        out.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { stack.add(it) }
        }
    }
    return out
}

private fun label(node: AccessibilityNodeInfo): String {
    val text = node.text?.toString()?.trim().orEmpty()
    if (text.isNotEmpty()) return text
    return node.contentDescription?.toString()?.trim().orEmpty()
}
