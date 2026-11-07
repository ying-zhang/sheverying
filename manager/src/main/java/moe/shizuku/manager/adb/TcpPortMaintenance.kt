package moe.shizuku.manager.adb

/** Property persistence is optional; a working listener and recovery are independent. */
internal class TcpPortMaintenance(
    private val readPersistentPort: () -> String,
    private val writePersistentPort: (String) -> Unit,
    private val serverUid: () -> Int,
    private val isPortLive: () -> Boolean,
    private val restoreListener: suspend () -> Unit,
    private val report: (String) -> Unit,
) {
    private var persistenceUnavailable = false

    suspend fun ensure(port: Int) {
        if (!persistenceUnavailable) {
            try {
                if (readPersistentPort() != port.toString()) {
                    if (serverUid() == 0) {
                        writePersistentPort(port.toString())
                        persistenceUnavailable = readPersistentPort() != port.toString()
                        report(if (persistenceUnavailable) "Persistent port readback differs; using listener recovery"
                               else "Persistent port verified: $port")
                    } else {
                        persistenceUnavailable = true
                        report("Shell mode: persistence is unavailable; using wireless ADB listener recovery")
                    }
                }
            } catch (e: Exception) {
                persistenceUnavailable = true
                report("Persistent port unavailable (${e.javaClass.simpleName}); using listener recovery")
            }
        }
        if (!isPortLive()) {
            restoreListener()
            check(isPortLive()) { "ADB listener still unavailable after recovery" }
            report("ADB listener restored on $port")
        }
    }
}
