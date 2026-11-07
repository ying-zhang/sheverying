package moe.shizuku.manager.worker

/** Manual requests survive the boot toggle; automatic requests must respect current intent. */
internal object AdbStartPolicy {
    enum class Source { BOOT, WATCHDOG, MANUAL }

    fun allowed(source: Source, stopped: Boolean, adbMode: Boolean, bootEnabled: Boolean,
                watchdogEnabled: Boolean): Boolean = !stopped && when (source) {
        Source.MANUAL -> true
        Source.BOOT -> adbMode && bootEnabled
        Source.WATCHDOG -> adbMode && watchdogEnabled
    }
}
