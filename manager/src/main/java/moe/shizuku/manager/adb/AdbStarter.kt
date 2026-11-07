package moe.shizuku.manager.adb

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.ShizukuStateMachine
import java.io.IOException

object AdbStarter {

    private val launches = AdbLaunchCoordinator(
        awaitIdle = { CancellableIo.awaitIdle() },
        awaitReady = { Starter.waitForBinder() },
        isRunning = { rikka.shizuku.Shizuku.pingBinder() })

    const val TCP_MODE_PORT = 5555

    @Suppress("UNUSED_PARAMETER")
    internal suspend fun start(
        host: String = "127.0.0.1",
        port: Int,
        context: Context? = null,
        listener: ((ByteArray) -> Unit)? = null,
        log: ((String) -> Unit)? = null,
        connectionAttempts: Int = 8,
        preferTcp: Boolean = true,
        skipIfRunning: Boolean = false,
        source: moe.shizuku.manager.worker.AdbStartPolicy.Source = moe.shizuku.manager.worker.AdbStartPolicy.Source.MANUAL,
        checkAllowed: () -> Unit = {},
    ) {
        val intentRevision = moe.shizuku.manager.worker.AdbStartWorker.intentRevision()
        val sourceRevision = moe.shizuku.manager.worker.AdbStartWorker.sourceRevision(source)
        val guard = {
            check(intentRevision == moe.shizuku.manager.worker.AdbStartWorker.intentRevision() &&
                sourceRevision == moe.shizuku.manager.worker.AdbStartWorker.sourceRevision(source) &&
                !moe.shizuku.manager.service.WatchdogManager.isUserStopRequested()) { "ADB start intent changed" }
            checkAllowed()
        }
        launches.launch(skipIfRunning, guard) { markPossiblyDispatched ->
            ShizukuStateMachine.onStartRequested()
            val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
            val tcpMode = ShizukuSettings.isTcpMode() && preferTcp
            val targetPort = if (tcpMode) TCP_MODE_PORT else port
            val isTargetAlreadyLive = EnvironmentUtils.isAdbPortLive(targetPort)
            if (tcpMode && port != targetPort && !isTargetAlreadyLive) {
                log?.invoke("Switching ADB from port $port to TCP port $targetPort...")
                switchToTcp(host, port, targetPort, key, source, guard)
            }

            log?.invoke("Connecting to ADB on port $targetPort...")
            connectWithRetry(host, targetPort, key, connectionAttempts, source, guard) { client, checkActive ->
                ShizukuSettings.setLastLaunchMode(ShizukuSettings.LaunchMethod.ADB)
                ShizukuSettings.setLastAdbPort(targetPort)
                client.shellCommand(Starter.internalCommand, listener,
                    checkAllowed = { checkActive(); guard() }, markPossiblyDispatched = markPossiblyDispatched)
            }
            // Do not clear adb_wifi_enabled here. On many phones that write restarts
            // adbd and drops service.adb.tcp.port, so 5555 disappears. The port is
            // maintained through the paired wireless endpoint; shell cannot assume
            // permission to write persist.adb.tcp.port.
        }
        if (ShizukuSettings.isTcpMode() && preferTcp) TcpPortKeep.ensure()
    }

    suspend fun switchToTcpMode(
        host: String = "127.0.0.1",
        currentPort: Int,
        targetPort: Int = TCP_MODE_PORT,
    ) {
        val revision = moe.shizuku.manager.worker.AdbStartWorker.intentRevision()
        val guard = {
            check(revision == moe.shizuku.manager.worker.AdbStartWorker.intentRevision() &&
                !moe.shizuku.manager.service.WatchdogManager.isUserStopRequested()) { "ADB start intent changed" }
        }
        launches.withGate(guard) {
            ShizukuStateMachine.onStartRequested()
            val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
            switchToTcp(host, currentPort, targetPort, key, moe.shizuku.manager.worker.AdbStartPolicy.Source.MANUAL, guard)
        }
    }

    internal suspend fun restoreTcpPort(currentPort: Int) {
        val revision = moe.shizuku.manager.worker.AdbStartWorker.intentRevision()
        val guard = {
            check(revision == moe.shizuku.manager.worker.AdbStartWorker.intentRevision() &&
                ShizukuSettings.isTcpMode() && rikka.shizuku.Shizuku.pingBinder() &&
                !moe.shizuku.manager.service.WatchdogManager.isUserStopRequested()) { "TCP recovery intent changed" }
        }
        launches.withGate(guard) {
            val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
            switchToTcp("127.0.0.1", currentPort, TCP_MODE_PORT, key,
                moe.shizuku.manager.worker.AdbStartPolicy.Source.WATCHDOG, guard)
            repeat(20) {
                guard()
                if (EnvironmentUtils.isAdbPortLive(TCP_MODE_PORT)) return@withGate
                delay(250)
            }
            error("ADB TCP listener did not return")
        }
    }

    private suspend fun switchToTcp(host: String, currentPort: Int, targetPort: Int, key: AdbKey,
                                    source: moe.shizuku.manager.worker.AdbStartPolicy.Source,
                                    checkAllowed: () -> Unit) {
        val client = AdbClient(host, currentPort, key)
        CancellableIo.run(client, source) { checkActive ->
            client.connect()
            checkActive()
            checkAllowed()
            try {
                client.command("tcpip:$targetPort")
            } catch (e: IOException) {
                // Expected: adbd restarts (dropping TCP/TLS socket) when switching to TCP mode.
            }
        }
    }

    private suspend fun connectWithRetry(
        host: String,
        port: Int,
        key: AdbKey,
        retries: Int = 8,
        source: moe.shizuku.manager.worker.AdbStartPolicy.Source,
        checkAllowed: () -> Unit,
        block: (AdbClient, () -> Unit) -> Unit,
    ) {
        var lastError: Throwable? = null
        repeat(retries) { attempt ->
            currentCoroutineContext().ensureActive()
            checkAllowed()
            try {
                val client = AdbClient(host, port, key)
                CancellableIo.run(client, source) { checkActive ->
                    client.connect()
                    checkActive()
                    checkAllowed()
                    block(client, checkActive)
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                // Once a command was sent, retrying it may kill the server it just spawned.
                if (e !is AdbEndpointException) throw e
                lastError = e
                if (attempt == retries - 1) throw e
                delay(500L * (attempt + 1))
            }
        }
        lastError?.let { throw it }
    }
}
