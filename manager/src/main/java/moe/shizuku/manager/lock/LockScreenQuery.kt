package moe.shizuku.manager.lock

internal data class LockScreenCommandResult(
    val exit: Int?,
    val text: String,
    val durationMs: Long,
    val timedOut: Boolean = false,
    val transientFailure: Boolean = false,
) {
    fun value(): Boolean? {
        if (exit != 0 || timedOut) return null
        return when (text.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    fun retryable(): Boolean = timedOut || transientFailure ||
        (exit == 0 && text.isBlank()) || text.contains("Can't find service: lock_settings")

    fun diagnostic(): String = "exit=$exit timeout=$timedOut durationMs=$durationMs output=${text.trim().take(240)}"
}

/** Retry reads only. A failed or unrecognized read never authorizes a setting change. */
internal class LockScreenQuery(
    private val read: () -> LockScreenCommandResult?,
    private val pause: (Long) -> Unit,
    private val log: (String) -> Unit,
) {
    fun disabled(): Boolean? {
        repeat(3) { attempt ->
            val result = read()
            result?.value()?.let { return it }
            log("locksettings get-disabled failed attempt=${attempt + 1}/3 ${result?.diagnostic() ?: "service unavailable"}")
            if (result?.retryable() != true || attempt == 2) return null
            pause(1_000L * (attempt + 1))
        }
        return null
    }
}
