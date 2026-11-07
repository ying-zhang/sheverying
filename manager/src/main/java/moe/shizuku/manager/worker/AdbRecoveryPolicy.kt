package moe.shizuku.manager.worker

/** Scheduling decisions for pending work; running discoveries must be preserved. */
internal object AdbRecoveryPolicy {
    fun needsWifiLink(isTelevision: Boolean, hasLocalListener: Boolean): Boolean =
        !isTelevision && !hasLocalListener

    data class Attempt(val running: Boolean, val retries: Int, val generation: String?)

    fun shouldResetBackoff(currentGeneration: String, pending: List<Attempt>): Boolean =
        pending.none { it.running } && pending.any {
            it.retries > 0 && it.generation != currentGeneration
        }

    fun shouldAdvance(wake: Boolean, readiness: Boolean, pending: List<Attempt>): Boolean =
        wake && pending.none { it.running } && (!readiness || pending.any { it.retries > 0 })
}
