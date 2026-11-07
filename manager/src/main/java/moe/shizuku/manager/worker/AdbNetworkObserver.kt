package moe.shizuku.manager.worker

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.content.Context
import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import rikka.shizuku.Shizuku
import moe.shizuku.manager.utils.awaitCompletion

/**
 * Safety net for the "boot on cellular, no auto-restart on Wi-Fi" bug.
 *
 * The boot worker checks the live local port and Wi-Fi link before discovery.
 * This observer
 * re-enqueues a start worker whenever Wi-Fi is associated, including airplane
 * mode where the link is up but not a validated default network.
 */
object AdbNetworkObserver {

    private const val TAG = "AdbNetworkObserver"

    @Volatile
    private var registered = false

    /** Suppresses back-to-back connectivity callbacks. */
    @Volatile
    private var lastTriggerMs = 0L
    private const val DEBOUNCE_MS =  5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Strong ref: ConnectivityManager does not retain the callback; an
     *  anonymous instance would be GC'd and silently kill the observer. */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val readinessCheck = java.util.concurrent.atomic.AtomicBoolean(false)
    private var wirelessObserver: ContentObserver? = null

    /** Never unregistered: process-scoped singleton, registered once at
     *  Application.onCreate; process death cleans it up,and the
     *  registered flag guards double-observation on re-entry. */
    fun register(app: Application) {
        // Wireless debugging needs API 30+; on older devices there is
        // nothing to observe, so skip installing the callback entirely.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (registered) return
        synchronized(this) {
            if (registered) return
            try {
                val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
                val request = AdbStartWorker.wifiNetworkRequest()
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        onUnmeteredAvailable(app)
                    }
                }
                networkCallback = callback
                cm.registerNetworkCallback(request, callback)
                val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        if (Settings.Global.getInt(app.contentResolver, "adb_wifi_enabled", 0) == 1) {
                            onWirelessDebugEnabled(app)
                        }
                    }
                }
                wirelessObserver = observer
                app.contentResolver.registerContentObserver(
                    Settings.Global.getUriFor("adb_wifi_enabled"), false, observer)
                if (Settings.Global.getInt(app.contentResolver, "adb_wifi_enabled", 0) == 1) {
                    onWirelessDebugEnabled(app)
                }
                registered = true
            } catch (e: Exception) {
                // Observing is best-effort; the worker and periodic fallback can recover.
                Log.w(TAG, "registerNetworkCallback failed", e)

            }
        }
    }

    /** Check actual loopback readiness before advancing a delayed recovery. */
    fun onWirelessDebugEnabled(context: Context) {
        val app = context.applicationContext
        if (app.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked != true ||
            rikka.shizuku.Shizuku.pingBinder() ||
            moe.shizuku.manager.service.WatchdogManager.isUserStopRequested()) return
        if (!readinessCheck.compareAndSet(false, true)) return
        scope.launch {
            try {
                kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                    repeat(15) {
                        if (Settings.Global.getInt(app.contentResolver, "adb_wifi_enabled", 0) == 1 &&
                            (moe.shizuku.manager.utils.EnvironmentUtils.getLiveAdbTcpPort() > 0 ||
                                moe.shizuku.manager.adb.LocalAdbDiscovery.findPort() > 0)) {
                            AdbStartWorker.onWirelessReady(app)
                            return@withTimeoutOrNull
                        }
                        delay(500)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Wireless readiness check failed", e)
            } finally {
                readinessCheck.set(false)
            }
        }
    }

    private fun onUnmeteredAvailable(app: Application) {
        if (app.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked != true) return
        // Hostile ROMs clear adb_wifi_enabled when wifi drops and never restore
        // it: a live session dies and stays dead. Re-arm after the stack
        // settles (gated by the toggle; idempotent; no-ops when off or flag-1(.
        reArmWifiAfterSettle(app)
        if (Shizuku.pingBinder()) {
            // "awaiting Wi-Fi" banner is definitively stale — clear it. This
            // check runs before the debounce so a no-op binder-up flap doesn't
            // burn the 5s window a real unstarted case would then wait out.

            ShizukuReceiverStarter.updateNotification(
                app.applicationContext,
                ShizukuReceiverStarter.WorkerState.STOPPED
            )
            return
        }
        if (!ShizukuSettings.getStartOnBootAdb()) return
        if (ShizukuSettings.getLastLaunchMode() != ShizukuSettings.LaunchMethod.ADB) return
        if (moe.shizuku.manager.service.WatchdogManager.isUserStopRequested()) return

        synchronized(this) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastTriggerMs < DEBOUNCE_MS) return

            lastTriggerMs = now
        }
        scope.launch {
            try {
                // Advance delayed retries after a real connectivity event. The scheduler
                // rechecks state and preserves running and freshly queued discoveries.
                val state = withTimeout(5_000L) {
                    val infos = WorkManager.getInstance(app.applicationContext)
                        .getWorkInfosForUniqueWork(AdbStartWorker.UNIQUE_WORK_NAME)
                        .awaitCompletion()
                    // Unique work queries also return old completed/cancelled
                    // rows. Their order is unspecified: never use a terminal
                    // row to decide that a running discovery can be replaced.
                    val info = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                        ?: infos.firstOrNull { !it.state.isFinished }
                    if (info?.state != WorkInfo.State.RUNNING) {
                        AdbStartWorker.onConnectivityAvailable(app.applicationContext)
                    }

                    when (info?.state) {
                        WorkInfo.State.RUNNING -> ShizukuReceiverStarter.WorkerState.RUNNING

                        // The enqueue may still be pending; use the last background
                        // readiness check until the worker refreshes it.
                        else -> AdbStartWorker.bannerStateFor(app.applicationContext)
                    }
                }
                ShizukuReceiverStarter.updateNotification(app.applicationContext, state)
            } catch (e: Exception) {
                Log.w(TAG, "onUnmeteredAvailable enqueue failed", e)
            }
        }
    }

    private fun reArmWifiAfterSettle(app: Application) {
        scope.launch {
            delay(3_000)
            WifiDebugReassert.reassertIfEnabled(app.applicationContext)
        }
    }
}
