package moe.shizuku.manager.worker

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.R
import moe.shizuku.manager.adb.AdbArm
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbStarter
import moe.shizuku.manager.adb.LocalAdbDiscovery
import moe.shizuku.manager.receiver.SheveryControlReceiver
import moe.shizuku.manager.module.ModuleSettings
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import moe.shizuku.manager.starter.Starter
import moe.shizuku.server.IShizukuService
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.service.WatchdogManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.shizuku.manager.utils.awaitCompletion

class AdbStartWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val UNIQUE_WORK_NAME = "adb_start_worker"
        private const val GENERATION_PREFIX = "adb-recovery-generation:"
        private const val SOURCE = "adb-start-source"
        private const val REVISION = "adb-start-revision"
        private const val REVISION_PREF = "adb_start_revision"
        private const val SOURCE_REVISION = "adb-start-source-revision"
        private val intentLock = Any()
        private const val RECOVERY_POLICY_TAG = "adb-recovery-policy:2"
        private const val ENQUEUE_TIMEOUT_MS = 4_500L
        private val scheduling = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val enqueueLock = Mutex()
        @Volatile private var lastNeedsWifiLink: Boolean? = null

        /** Includes time spent waiting for other scheduling operations. */
        suspend fun enqueue(context: Context): Boolean = enqueueWithPolicy(context, AdbStartPolicy.Source.BOOT)

        /** Explicit user retry may replace a delayed attempt to run immediately. */
        fun enqueueNow(context: Context) {
            WatchdogManager.clearUserStopRequest(context)
            schedule(context, AdbStartPolicy.Source.MANUAL, wake = true)
        }

        fun enqueueForWatchdog(context: Context,
                               expectedSourceRevision: Long = sourceRevision(AdbStartPolicy.Source.WATCHDOG)) {
            schedule(context, AdbStartPolicy.Source.WATCHDOG, expectedSourceRevision = expectedSourceRevision)
        }

        private fun currentRevision(): Long = ShizukuSettings.getPreferences().getLong(REVISION_PREF, 0)
        internal fun intentRevision(): Long = currentRevision()
        internal fun sourceRevision(source: AdbStartPolicy.Source): Long =
            ShizukuSettings.getPreferences().getLong("$SOURCE_REVISION:${source.name}", 0)

        internal fun cancelSource(context: Context, source: AdbStartPolicy.Source) {
            val invalidRevision = synchronized(intentLock) {
                val revision = sourceRevision(source) + 1
                ShizukuSettings.getPreferences().edit()
                    .putLong("$SOURCE_REVISION:${source.name}", revision).apply()
                moe.shizuku.manager.adb.CancellableIo.closeActive(source)
                revision
            }
            val app = context.applicationContext
            scheduling.launch {
                try {
                    withTimeout(5_000L) {
                        enqueueLock.withLock {
                            val wm = WorkManager.getInstance(app)
                            wm.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).awaitCompletion()
                                .filter { !it.state.isFinished }.forEach { info ->
                                    val taskSource = info.tags.firstOrNull { it.startsWith("$SOURCE:") }
                                        ?.substringAfter(':') ?: "BOOT"
                                    val revision = info.tags.firstOrNull { it.startsWith("$SOURCE_REVISION:") }
                                        ?.substringAfter(':')?.toLongOrNull() ?: 0
                                    if (taskSource == source.name && revision < invalidRevision) {
                                        wm.cancelWorkById(info.id).result.awaitCompletion()
                                    }
                                }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(AppConstants.TAG, "Could not cancel $source ADB recovery", e)
                }
            }
        }

        /** Invalidate immediately, then serialize cancellation with pending enqueues. */
        fun cancelPending(context: Context, automaticOnly: Boolean = false) {
            if (automaticOnly) {
                cancelSource(context, AdbStartPolicy.Source.BOOT)
                return
            }
            val invalidRevision = synchronized(intentLock) {
                ShizukuSettings.getPreferences().edit().putLong(REVISION_PREF, currentRevision() + 1).apply()
                moe.shizuku.manager.adb.CancellableIo.closeActive()
                currentRevision()
            }
            val app = context.applicationContext
            scheduling.launch {
                try {
                    withTimeout(5_000L) {
                        enqueueLock.withLock {
                            val wm = WorkManager.getInstance(app)
                            val pending = wm.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).awaitCompletion()
                            for (info in pending.filter { !it.state.isFinished }) {
                                val revisionTag = info.tags.firstOrNull { it.startsWith("$REVISION:") }
                                val revision = revisionTag?.substringAfter(':')?.toLongOrNull() ?: 0L
                                val isStale = revision < invalidRevision || info.tags.none { it.startsWith("$REVISION:") }
                                if (isStale) {
                                    wm.cancelWorkById(info.id).result.awaitCompletion()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(AppConstants.TAG, "Could not cancel ADB recovery", e)
                }
            }
        }

        private fun allowed(source: AdbStartPolicy.Source): Boolean = AdbStartPolicy.allowed(
            source, WatchdogManager.isUserStopRequested() ||
                moe.shizuku.manager.utils.UserHandleCompat.myUserId() > 0,
            ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ADB,
            ShizukuSettings.getStartOnBootAdb(), ModuleSettings.isWatchdogEnabled())

        /** Advances delayed work, or creates BOOT safety-net work if enabled; never replaces running work. */
        fun onWirelessReady(context: Context) {
            schedule(context, AdbStartPolicy.Source.BOOT, wake = true, readiness = true)
        }

        /** A restored Wi-Fi link should advance a delayed retry without replacing active discovery. */
        fun onConnectivityAvailable(context: Context) {
            schedule(context, AdbStartPolicy.Source.BOOT, wake = true, readiness = true)
        }

        internal suspend fun recoverIfNeeded(context: Context): Boolean =
            enqueueWithPolicy(context, AdbStartPolicy.Source.BOOT, wake = true, readiness = true)

        private fun schedule(context: Context, source: AdbStartPolicy.Source,
                             wake: Boolean = false, readiness: Boolean = false,
                             expectedSourceRevision: Long? = null) {
            val app = context.applicationContext
            // Capture intent epochs before dispatching, so disabling/re-enabling cannot revive a trigger.
            val revision = currentRevision()
            val epochs = sourceEpochs(source, expectedSourceRevision)
            scheduling.launch { enqueueWithPolicy(app, source, wake, readiness, revision, epochs) }
        }

        private fun sourceEpochs(source: AdbStartPolicy.Source, expected: Long?) =
            AdbStartPolicy.Source.entries.associateWith {
                if (it == source && expected != null) expected else sourceRevision(it)
            }

        private suspend fun enqueueWithPolicy(context: Context, source: AdbStartPolicy.Source,
                                              wake: Boolean = false, readiness: Boolean = false,
                                              revision: Long = currentRevision(),
                                              epochs: Map<AdbStartPolicy.Source, Long> = sourceEpochs(source, null)): Boolean {
            if (context.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked != true) {
                Log.i(AppConstants.TAG, "ADB work deferred until first unlock")
                return false
            }
            return try {
                withTimeout(ENQUEUE_TIMEOUT_MS) {
                    enqueueLock.withLock {
                        if (revision != currentRevision() || epochs[source] != sourceRevision(source)) false
                        else scheduleWork(context, source, revision, epochs, wake, readiness)
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.w(AppConstants.TAG, "ADB work scheduling timed out; persistence is unconfirmed", e)
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(AppConstants.TAG, "Could not schedule ADB recovery", e)
                false
            }
        }

        private suspend fun scheduleWork(context: Context, requestedSource: AdbStartPolicy.Source,
                                 revision: Long, sourceEpochs: Map<AdbStartPolicy.Source, Long>,
                                 wake: Boolean, readiness: Boolean): Boolean {
            val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
            val bootIdentity = if (boot >= 0) boot.toString() else
                runCatching { java.io.File("/proc/sys/kernel/random/boot_id").readText().trim() }
                    .getOrDefault("unknown")
            val generation = "$GENERATION_PREFIX${BuildConfig.VERSION_CODE}:$bootIdentity"
            val workManager = WorkManager.getInstance(context)
            val pending = workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME)
                .awaitCompletion().filter { !it.state.isFinished }
            // Preserve the source when a readiness signal advances an existing manual/watchdog retry.
            val source = if (readiness) pending.firstOrNull()?.tags
                ?.firstOrNull { it.startsWith("$SOURCE:") }?.substringAfter(':')
                ?.let { runCatching { AdbStartPolicy.Source.valueOf(it) }.getOrNull() }
                ?: requestedSource else requestedSource
            val sourceEpoch = sourceEpochs.getValue(source)
            if (!allowed(source) || revision != currentRevision() || sourceEpoch != sourceRevision(source)) return false
            if (readiness && pending.isEmpty() && !ShizukuSettings.getStartOnBootAdb()) return false
            val resetBackoff = AdbRecoveryPolicy.shouldResetBackoff(generation, pending.map {
                AdbRecoveryPolicy.Attempt(
                    it.state == WorkInfo.State.RUNNING,
                    it.runAttemptCount,
                    it.tags.firstOrNull { tag -> tag.startsWith(GENERATION_PREFIX) }
                )
            })
            val advance = AdbRecoveryPolicy.shouldAdvance(wake, readiness, pending.map {
                AdbRecoveryPolicy.Attempt(it.state == WorkInfo.State.RUNNING, it.runAttemptCount, null)
            })
            // A manual start must not be swallowed by an invalid old automatic request.
            val replaceInvalid = requestedSource == AdbStartPolicy.Source.MANUAL && pending.any { info ->
                val oldSource = info.tags.firstOrNull { it.startsWith("$SOURCE:") }
                    ?.substringAfter(':')?.let { runCatching { AdbStartPolicy.Source.valueOf(it) }.getOrNull() }
                    ?: AdbStartPolicy.Source.BOOT
                ("$REVISION:$revision" !in info.tags) || !allowed(oldSource)
            }
            // Migrate pending requests that still carry the old, immutable Wi-Fi snapshot.
            val replaceOldConstraints = pending.none { it.state == WorkInfo.State.RUNNING } &&
                pending.any { RECOVERY_POLICY_TAG !in it.tags }
            val policy = if (advance || resetBackoff || replaceInvalid || replaceOldConstraints)
                ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            if (resetBackoff) {
                Log.i(AppConstants.TAG, "Resetting ADB backoff from an earlier boot or app version; generation=$generation")
            }
            // A local listener can appear after enqueue even while Wi-Fi remains off.
            // Check readiness inside doWork instead of persisting a network snapshot.
            val needsWifiLink = refreshNeedsWifiLink()

            val request = OneTimeWorkRequestBuilder<AdbStartWorker>()
                .addTag(generation)
                .addTag(RECOVERY_POLICY_TAG)
                .addTag("$SOURCE:${source.name}")
                .addTag("$REVISION:$revision")
                .addTag("$SOURCE_REVISION:$sourceEpoch")
                .setInputData(workDataOf(SOURCE to source.name, REVISION to revision,
                    SOURCE_REVISION to sourceEpoch))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30_000L, TimeUnit.MILLISECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            currentCoroutineContext().ensureActive()
            if (!allowed(source) || revision != currentRevision() || sourceEpoch != sourceRevision(source)) return false
            workManager.enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                policy,
                request
            ).result.awaitCompletion()
            // UI errors must never turn a confirmed database write into scheduling failure.
            runCatching { ShizukuReceiverStarter.updateNotification(context, bannerStateFor(context, needsWifiLink)) }
                .onFailure { Log.w(AppConstants.TAG, "Could not update recovery notification", it) }
            return true
        }

        /**
         * Wi-Fi transport only. The builder adds INTERNET by default; drop it so
         * an associated network still matches when airplane mode has not validated it.
         */
        fun wifiNetworkRequest(): NetworkRequest {
            return NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        }

        /** True when some network is on the Wi-Fi transport, even if it is not
         *  the default network and has no validated internet. */
        fun isWifiConnected(context: Context): Boolean {
            return try {
                val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
                val onWifi = cm.allNetworks.any { network ->
                    cm.getNetworkCapabilities(network)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                }
                if (onWifi) return true
                @Suppress("DEPRECATION")
                val info = context.applicationContext
                    .getSystemService(WifiManager::class.java)
                    ?.connectionInfo
                info != null && info.networkId != -1
            } catch (_: Exception) {
                false
            }
        }

        /** Called only on an I/O dispatcher; main-thread banners never probe sockets. */
        private fun refreshNeedsWifiLink(): Boolean = AdbRecoveryPolicy.needsWifiLink(
            EnvironmentUtils.isTelevision(), EnvironmentUtils.getLiveAdbTcpPorts().isNotEmpty()
        ).also { lastNeedsWifiLink = it }

        fun bannerStateFor(context: Context, needsWifiLink: Boolean =
            lastNeedsWifiLink ?: !EnvironmentUtils.isTelevision()): ShizukuReceiverStarter.WorkerState =
            if (needsWifiLink && !isWifiConnected(context)) {
                ShizukuReceiverStarter.WorkerState.AWAITING_WIFI
            } else {
                ShizukuReceiverStarter.WorkerState.RUNNING
            }

    }

    /**
     * Expedited work on API <31 runs inside a foreground service, and WorkManager
     * fetches its notification through this method BEFORE doWork() runs. The default
     * implementation throws IllegalStateException, which crashed every
     * watchdog-triggered ADB restart on Android 7-11. Reuses the starter channel
     * and NOTIFICATION_ID so the worker's own progress updates replace it.
     * WorkManager only calls this on API <31 (it skips the foreground path on
     * 31+), so the typeless form is used: no FGS-type bits that those releases
     * don't know. Manifest's SystemForegroundService declaration is unaffected.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        ShizukuReceiverStarter.ensureChannel(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, ShizukuReceiverStarter.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(applicationContext.getString(R.string.wadb_notification_title))
            .setOngoing(true)
            .setSilent(true)
            .build()
        @Suppress("DEPRECATION")
        return ForegroundInfo(ShizukuReceiverStarter.NOTIFICATION_ID, notification)
    }

    override suspend fun doWork(): Result {
        val source = runCatching {
            AdbStartPolicy.Source.valueOf(inputData.getString(SOURCE) ?: "BOOT")
        }.getOrDefault(AdbStartPolicy.Source.BOOT)
        val revision = inputData.getLong(REVISION, 0)
        val sourceEpoch = inputData.getLong(SOURCE_REVISION, 0)
        fun stillAllowed() = revision == currentRevision() && sourceEpoch == sourceRevision(source) && allowed(source)
        try {
            if (!stillAllowed()) {
                ShizukuReceiverStarter.updateNotification(applicationContext, ShizukuReceiverStarter.WorkerState.STOPPED)
                return Result.success()
            }
            if (Shizuku.pingBinder()) {
                ShizukuReceiverStarter.updateNotification(applicationContext, ShizukuReceiverStarter.WorkerState.STOPPED)
                return Result.success()
            }
            ShizukuReceiverStarter.updateNotification(
                applicationContext,
                ShizukuReceiverStarter.WorkerState.RUNNING
            )

            // Gate ALL start paths (TCP fast-path, TV, mDNS) behind unlock.
            // Android tears down plain-TCP adb while the keyguard is up, so starting
            // behind the lockscreen (as the watchdog did) just loops. Waitfor
            // USER_PRESENT like the reference implementation, bounded so a locked
            // device falls through to backoff retries instead of wedging the worker.
            val keyguardManager = applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (keyguardManager?.isKeyguardLocked == true) {
                Log.d(AppConstants.TAG, "AdbStartWorker: device locked -- waiting for USER_PRESENT before starting ADB")
                val unlocked = withTimeoutOrNull(30_000L) {
                    suspendCancellableCoroutine<Boolean> { cont ->
                        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
                        val unlockReceiver = object : BroadcastReceiver() {
                            override fun onReceive(context: Context, intent: Intent) {
                                if (intent.action == Intent.ACTION_USER_PRESENT) {
                                    runCatching { applicationContext.unregisterReceiver(this) }
                                    if (cont.isActive) cont.resumeWith(kotlin.Result.success(true))
                                }
                            }
                        }
                        val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            ContextCompat.RECEIVER_EXPORTED
                        } else {
                            ContextCompat.RECEIVER_NOT_EXPORTED
                        }
                        ContextCompat.registerReceiver(applicationContext, unlockReceiver, filter, receiverFlags)
                        // Re-check after registration: the device may have unlocked between
                        // the check above and now, so USER_PRESENT fired and was missed.

                        if (keyguardManager?.isKeyguardLocked == false) {
                            runCatching { applicationContext.unregisterReceiver(unlockReceiver) }
                            if (cont.isActive) cont.resumeWith(kotlin.Result.success(true))
                        }
                        cont.invokeOnCancellation {
                            runCatching { applicationContext.unregisterReceiver(unlockReceiver) }
                        }
                    }
                }
                if (unlocked != true) {
                    Log.d(AppConstants.TAG, "AdbStartWorker: device stayed locked; parking worker for backoff retry")
                    return Result.retry()
                }
            }

            // No FGS promotion here:the reference (thedjchi/Shizuku( only foregrounds
            // to wait out an *unbounded* keyguard unlock; every wait in our fork is
            // bounded (60s discovery, 30s unlock) plus retries, so a background
            // startForegroundService would only throw ForegroundServiceStartNotAllowed
            // on Android  12+, looping forever. The expedited request gives a best-effort
            // execution window; process death mid-attempt is covered by Result.retry().



            val cr = applicationContext.contentResolver

            // WRITE_SECURE_SETTINGS arms adbd and prevents pairing-key expiry.
            AdbArm.arm(applicationContext)
            val needsWifiLink = refreshNeedsWifiLink()
            if (needsWifiLink && !isWifiConnected(applicationContext)) {
                ShizukuReceiverStarter.updateNotification(applicationContext,
                    ShizukuReceiverStarter.WorkerState.AWAITING_WIFI)
                Log.i(AppConstants.TAG, "No local ADB listener or Wi-Fi link; deferring discovery")
                return Result.retry()
            }

            val excludedPorts = moe.shizuku.manager.adb.AdbCandidateExclusions()
            suspend fun discoverPort(): Int {
                val tcpPort = EnvironmentUtils.getAdbTcpPort()
                val liveTcpPort = EnvironmentUtils.getLiveAdbTcpPorts().firstOrNull { it !in excludedPorts } ?: -1

                return if (EnvironmentUtils.isTelevision()) {
                    // TV devices with a configured/static TCP port use TCP directly;
                    // avoid mDNS discovery which is unreliable on LEANBACK.
                    if (tcpPort > 0 && tcpPort !in excludedPorts) tcpPort else throw SecurityException("TV device requires TCP ADB port to be configured")
                } else if (!needsWifiLink && liveTcpPort > 0) {

                    // A configured/static TCP port that is actually live can be used directly.

                    // NOTE: TCP mode alone does NOT imply the port is live: adbd's wireless
                    // debugging port is random per boot,and 5555 (TCP_MODE_PORT( exists only
                    // AFTER the first successful start rebinds adbd to it. When a configured port
                    // is stale (e.g., fresh reboot before the service started(, fall through to mDNS
                    // so the worker still discovers the live random wireless port.
                    liveTcpPort
                } else {
                    // mDNS advert can go stale when Wi-Fi drops and reconnects (the
                    // Framework never re-publishes _adb-tls-connect(; adbd's TLS
                    // listener usually survives on loopback though,cached last port probe
                    // finds it instantly,and a wrong-service connect dies fast (
                    // TLS/A_AUTH handshake(, so this fallback is safe.
                    if (liveTcpPort > 0) liveTcpPort else withTimeoutOrNull(60_000L) { callbackFlow {
                        val adbMdns = AdbMdns(applicationContext, AdbMdns.TLS_CONNECT) { p ->
                            if (p > 0 && p !in excludedPorts) trySend(p)
                        }
                        // Ask this phone's responder directly as well: a fleet boot
                        // can wedge the framework's shared resolver on remote records.
                        val localDiscoveryJob = launch(Dispatchers.IO) {
                            repeat(8) {
                                val localPort = LocalAdbDiscovery.findPort(excludedPorts.toIntArray())
                                if (localPort > 0) {
                                    Log.i(AppConstants.TAG, "Local ADB discovery found port=$localPort")
                                    trySend(localPort)
                                    return@launch
                                }
                                delay(5_000)
                            }
                        }

                        var awaitingAuth = false
                        var timeoutJob: Job? = null
                        var authWaitJob: Job? = null
                        var unlockReceiver: BroadcastReceiver? = null

                        fun startDiscoveryWithTimeout() {
                            adbMdns.start()
                            authWaitJob?.cancel()
                            authWaitJob = null
                            timeoutJob?.cancel()
                            timeoutJob = this.launch {
                                delay(45_000)
                                close(TimeoutException("Timed out during mDNS port discovery"))
                            }
                        }

                        fun handleAuth() {
                            val km = applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                            timeoutJob?.cancel()
                            timeoutJob = null
                            adbMdns.stop()
                            authWaitJob?.cancel()
                            authWaitJob = null
                            if (km.isKeyguardLocked) {
                                if (unlockReceiver == null) {
                                    val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
                                    unlockReceiver = object : BroadcastReceiver() {
                                        override fun onReceive(context: Context, intent: Intent) {
                                            if (intent.action == Intent.ACTION_USER_PRESENT) {
                                                context.unregisterReceiver(this)
                                                unlockReceiver = null
                                                AdbArm.armWifiEnabled(context)
                                            }
                                        }
                                    }
                                    val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        ContextCompat.RECEIVER_EXPORTED
                                    } else {
                                        ContextCompat.RECEIVER_NOT_EXPORTED
                                    }
                                    ContextCompat.registerReceiver(
                                        applicationContext,
                                        unlockReceiver,
                                        filter,
                                        receiverFlags
                                    )
                                }
                                // Bound the unlock wait: an uncapped wait wedges the worker
                                // when the flag flaps during Wi-Fi bring-up. This timeout is
                                // transient, so a later retry (or the unlock) resumes us.
                                authWaitJob = this.launch {
                                    delay(30_000)
                                    close(TimeoutException("Timed out waiting for unlock to authorize wireless debugging"))
                                }
                            } else {
                                // System cleared adb_wifi_enabled mid-run (typical during
                                // Wi-Fi bring-up). Wait for it to restore the flag (it does
                                // once the wireless stack settles; the observer below re-arms
                                // discovery), instead of re-asserting now and racing its cleanup
                                // — a mid-flight clear would otherwise wedge us in a retry
                                // loop just when the network is coming back.

                                awaitingAuth = true
                                // A ROM may leave the flag at 0 permanently. Never
                                // cancel discovery's deadline without replacing it:
                                // KEEP on Wi-Fi recovery otherwise preserves this wedged worker.
                                authWaitJob = this.launch {
                                    delay(15_000)
                                    close(TimeoutException("Wireless debugging stayed disabled; retry arming adbd"))
                                }
                            }
                        }

                        val observer = object : ContentObserver(null) {
                            override fun onChange(selfChange: Boolean) {
                                when (Settings.Global.getInt(cr, "adb_wifi_enabled", 0)) {
                                    0 -> if (awaitingAuth) {
                                        close(TimeoutException("Wireless debugging was disabled again mid-run"))
                                    } else {
                                        handleAuth()
                                    }
                                    1 -> {
                                        awaitingAuth = false
                                        startDiscoveryWithTimeout()
                                    }
                                }
                            }
                        }

                        AdbArm.armWifiEnabled(applicationContext)
                        val uri = Settings.Global.getUriFor("adb_wifi_enabled")
                        if (uri != null) {
                            cr.registerContentObserver(uri, false, observer)
                        }
                        startDiscoveryWithTimeout()

                        awaitClose {
                            localDiscoveryJob.cancel()
                            adbMdns.stop()
                            authWaitJob?.cancel()
                            timeoutJob?.cancel()
                            cr.unregisterContentObserver(observer)
                            unlockReceiver?.let {
                                try {
                                    applicationContext.unregisterReceiver(it)
                                } catch (_: Exception) {}
                            }
                        }
                    }.first() } ?: throw TimeoutException("Wireless discovery exceeded its overall deadline")
                }

            }
            val started = withTimeoutOrNull(120_000L) {
                repeat(8) {
                    currentCoroutineContext().ensureActive()
                    if (!stillAllowed() || Shizuku.pingBinder()) return@withTimeoutOrNull true
                    val port = discoverPort()
                    try {
                        AdbStarter.start("127.0.0.1", port, applicationContext,
                            connectionAttempts = 2, preferTcp = AdbStarter.TCP_MODE_PORT !in excludedPorts,
                            skipIfRunning = true,
                            source = source,
                            checkAllowed = { check(stillAllowed()) { "ADB start intent changed" } })
                        return@withTimeoutOrNull true
                    } catch (e: moe.shizuku.manager.adb.AdbEndpointException) {
                        // A valid TLS candidate must remain available when the
                        // preferred 5555 endpoint was the one that failed.
                        excludedPorts.record(e)
                        Log.w(AppConstants.TAG, "ADB candidate ${e.port}: ${e.kind}; protocol errors are excluded, other errors are retried", e)
                        delay(if (e.kind == moe.shizuku.manager.adb.AdbEndpointException.Kind.AUTH_PENDING) 1_000L else 500L)
                    }
                }
                false
            } ?: false
            if (!stillAllowed()) return Result.success()
            check(started) { "No authenticated ADB endpoint within recovery budget" }
            if (!Starter.waitForBinder()) {
                // waitForBinder can time out while the binder actually arrived;
                // re-ping once before treating this as a failure.
                if (Shizuku.pingBinder()) {
                    reassertWifiFlagIfEnabled()
                    ShizukuReceiverStarter.updateNotification(
                        applicationContext,
                        ShizukuReceiverStarter.WorkerState.STOPPED
                    )
                    return Result.success()
                }
                throw TimeoutException("Failed to receive binder within 30 seconds")
            }
            reassertWifiFlagIfEnabled()

            ShizukuReceiverStarter.updateNotification(
                applicationContext,
                ShizukuReceiverStarter.WorkerState.STOPPED
            )

            return Result.success()
        } catch (e: CancellationException) {
            Log.i(
                AppConstants.TAG,
                "AdbStartWorker cancelled; stopReason=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) getStopReason() else "unknown"}"
            )
            val state = if (!stillAllowed() || Shizuku.pingBinder()) {
                ShizukuReceiverStarter.WorkerState.STOPPED
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                ShizukuReceiverStarter.WorkerState.AWAITING_RETRY
            } else {
                when (getStopReason()) {
                    WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> ShizukuReceiverStarter.WorkerState.AWAITING_WIFI
                    WorkInfo.STOP_REASON_CANCELLED_BY_APP -> ShizukuReceiverStarter.WorkerState.STOPPED
                    else -> ShizukuReceiverStarter.WorkerState.AWAITING_RETRY
                }
            }
            ShizukuReceiverStarter.updateNotification(applicationContext, state)
            throw e
        } catch (e: Exception) {
            if (!stillAllowed()) {
                ShizukuReceiverStarter.updateNotification(applicationContext, ShizukuReceiverStarter.WorkerState.STOPPED)
                return Result.success()
            }
            // Only ambiguous post-command failures need a brief Binder grace period.
            if (ShizukuStateMachine.get() == ShizukuStateMachine.State.STARTING &&
                ShizukuStateMachine.awaitRunning(5_000L)) {
                ShizukuReceiverStarter.updateNotification(applicationContext, ShizukuReceiverStarter.WorkerState.STOPPED)
                return Result.success()
            }
            Log.w(AppConstants.TAG, "ADB startup attempt $runAttemptCount failed", e)
            // Matches the reference implementation (thedjchi/Shizuku): the
            // auto-start worker never terminally fails. Anything below a
            // running binder — adbd restarts, TLS/key flaps, mDNS misses,
            // Wi-Fi bring-up races — heals with backoff, so retry until the
            // binder is up. Failing here strands the device until the next
            // boot or a manual start.
            val ignored = listOf(
                java.io.EOFException::class,
                SecurityException::class,
                TimeoutException::class,
                javax.net.ssl.SSLException::class,
                java.net.UnknownHostException::class
            )
            if (ignored.none { it.isInstance(e) }) showErrorNotification(applicationContext, e)

            if (ShizukuStateMachine.update() == ShizukuStateMachine.State.RUNNING) {
                // Binder arrived during unwind — cancel any stale progress UI, then succeed.
                ShizukuReceiverStarter.updateNotification(applicationContext, ShizukuReceiverStarter.WorkerState.STOPPED)
                return Result.success()
            }
            // Do not put the raw exception text in the user-facing notification.
            ShizukuReceiverStarter.updateNotification(
                applicationContext,
                ShizukuReceiverStarter.WorkerState.AWAITING_RETRY
            )
            return Result.retry()
        }
    }

    // Opt-in hammer for hostile ROMs: runs AFTER AdbStarter.start (incl. the
    // tcpip:5555 rebind), because some ROMs clear adb_wifi_enabled when legacy
    // TCP mode activates — a write before the bind lands in the wiped window.
    // Shells out through the Shizuku service (same as ADB modules: shell UID),
    // NOT the app ContentResolver — the app UID typically lacks
    // WRITE_SECURE_SETTINGS. Reads first and NEVER writes 0: a manufactured
    // disable event mid-connection makes hostile ROMs tear down the live socket
    // (visible "turns on and off" flapping) — watchdog restarts just re-fire it.

    // Only re-arm with a bare put 1 when the ROM actually wiped it (0 -> 1
    // state change at the provider level, no socket disruption).
    //
    // TIMING MATTERS more than the transport: the ROM wipes the flag
    // asynchronously around the rebind, so an immediate toggle fires too early
    // and gets wiped again. Wait for the wipe to land first, toggle, read the
    // value back, and retry once if the ROM cleared it again.
    private suspend fun reassertWifiFlagIfEnabled() {
        if (!ModuleSettings.isWifiReassertEnabled()) return
        withContext(Dispatchers.IO) {
            try {
                if (!Shizuku.pingBinder()) {
                    Log.d(AppConstants.TAG, "AdbStartWorker: binder down, skipping wifi re-assert")
                    return@withContext
                }
                // Let the ROM's post-bind wipe land before touching the flag.
                delay(3_000)
                if (!Shizuku.pingBinder()) {
                    Log.d(AppConstants.TAG, "AdbStartWorker: binder died during wifi re-assert settle wait")
                    return@withContext
                }
                val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
                ensureWifiFlag(service)
                delay(1_000)
                var stuck = readWifiFlag()
                if (stuck != 1) {
                    Log.d(AppConstants.TAG, "AdbStartWorker: adb_wifi_enabled read back as $stuck after re-arm, retrying once")
                    delay(2_000)
                    ensureWifiFlag(service)
                    delay(1_000)
                    stuck = readWifiFlag()
                }
                Log.d(AppConstants.TAG, "AdbStartWorker: adb_wifi_enabled final value=$stuck")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(AppConstants.TAG, "AdbStartWorker: wifi re-assert failed: ${e.message}")
            }
        }
    }

    private suspend fun ensureWifiFlag(service: IShizukuService): Boolean {
        // Read first: if the flag is already armed there is nothing to do —and,
        // vitally, nothing to disturb. A live wireless-debugging session must
        // never see a synthetic 0.
        if (readWifiFlag() == 1) {
            Log.d(AppConstants.TAG, "AdbStartWorker: adb_wifi_enabled already 1, no-op")
            return true
        }
        // Bare re-arm (no 0-first):the ROM cleared the flag (typically to 0),
        // so this single put is a real 0 -> 1 state change at the provider level.
        val process = service.newProcess(
            arrayOf(
                "sh", "-c",
                "settings put global adb_wifi_enabled 1 >/dev/null 2>&1"
            ),
            null,
            null
        )
        val exitCode = try {
            withTimeoutOrNull(5_000) {
                runInterruptible { process.waitFor() }
            } ?: run {
                Log.d(AppConstants.TAG, "AdbStartWorker: wifi flag re-arm timed out; killed shell")
                -1
            }
        } finally {
            // Outer worker cancellation must also release the remote shell.
            runCatching { process.destroy() }
        }
        Log.d(AppConstants.TAG, "AdbStartWorker: wifi flag re-arm exit=$exitCode")
        return exitCode == 0
    }

    private fun readWifiFlag(): Int {
        return try {
            Settings.Global.getInt(applicationContext.contentResolver, "adb_wifi_enabled", 0)
        } catch (_: Exception) {
            -1
        }
    }

    private fun showErrorNotification(context: Context, e: Exception) {
        // Use ShizukuReceiverStarter's channel to avoid duplicate channel creation
        ShizukuReceiverStarter.ensureChannel(context)

        val intent = Intent(context, SheveryControlReceiver::class.java).apply {
            action = SheveryControlReceiver.ACTION_START_SERVER
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0x7F010006, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, ShizukuReceiverStarter.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(context.getString(R.string.wadb_error_title))
            .setContentText(context.getString(R.string.wadb_error_notify_dev))
            .setContentIntent(pendingIntent)
            .setSilent(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ShizukuReceiverStarter.NOTIFICATION_ID, notification)
    }
}
