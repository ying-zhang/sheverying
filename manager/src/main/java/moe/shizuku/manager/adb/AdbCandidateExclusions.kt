package moe.shizuku.manager.adb

import java.util.concurrent.ConcurrentHashMap

/** Only confirmed protocol mismatches are excluded for the entire discovery attempt. */
internal class AdbCandidateExclusions(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : AbstractSet<Int>() {
    private val deadlines = ConcurrentHashMap<Int, Long>()

    fun record(error: AdbEndpointException) {
        val cooldown = when (error.kind) {
            AdbEndpointException.Kind.PROTOCOL -> Long.MAX_VALUE
            AdbEndpointException.Kind.TRANSIENT -> now() + 2_000L
            AdbEndpointException.Kind.TLS_AUTH -> now() + 5_000L
            AdbEndpointException.Kind.AUTH_PENDING -> return
        }
        deadlines[error.port] = cooldown
    }

    private fun snapshot(): Set<Int> {
        val time = now()
        deadlines.entries.forEach { if (it.value <= time) deadlines.remove(it.key, it.value) }
        return deadlines.keys.toSet()
    }

    override val size: Int get() = snapshot().size
    override fun iterator(): Iterator<Int> = snapshot().iterator()
    override fun contains(element: Int): Boolean {
        val deadline = deadlines[element] ?: return false
        return deadline > now()
    }
}
