package moe.shizuku.manager.adb

import android.util.Log
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.EnvironmentUtils
import kotlinx.coroutines.runBlocking
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Preserve a live TCP listener without assuming shell can write persist properties.
 * Recover a missing listener through the already paired wireless ADB endpoint.
 */
object TcpPortKeep {

    private const val TAG = "TcpPortKeep"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tcp-port-keep").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)
    private fun service() = IShizukuService.Stub.asInterface(Shizuku.getBinder())
    private val maintenance = TcpPortMaintenance(
        readPersistentPort = { service().getSystemProperty("persist.adb.tcp.port", "") },
        writePersistentPort = { service().setSystemProperty("persist.adb.tcp.port", it) },
        serverUid = { Shizuku.getUid() },
        isPortLive = { EnvironmentUtils.isAdbPortLive(AdbStarter.TCP_MODE_PORT) },
        restoreListener = {
            val wirelessPort = LocalAdbDiscovery.findPort(intArrayOf(AdbStarter.TCP_MODE_PORT))
            check(wirelessPort > 0) { "No paired wireless ADB endpoint available for TCP recovery" }
            AdbStarter.restoreTcpPort(wirelessPort)
        },
        report = { Log.i(TAG, it) },
    )

    fun ensure() {
        if (!ShizukuSettings.isTcpMode()) return
        if (!Shizuku.pingBinder()) return
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try {
                if (ShizukuSettings.isTcpMode() && Shizuku.pingBinder() &&
                    !moe.shizuku.manager.service.WatchdogManager.isUserStopRequested()) {
                    runBlocking { maintenance.ensure(AdbStarter.TCP_MODE_PORT) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "TCP listener recovery unavailable; wireless recovery remains enabled", e)
            } finally {
                running.set(false)
            }
        }
    }

}
