package moe.shizuku.manager

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import moe.shizuku.manager.ktx.logd
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.core.util.BuildUtils.atLeast30
import rikka.material.app.LocaleDelegate

lateinit var application: ShizukuApplication

class ShizukuApplication : Application(), androidx.work.Configuration.Provider {

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // A process created during Direct Boot may reach BOOT_COMPLETED without
    // WorkManager's non-Direct-Boot initializer having run in that process.
    // getInstance(context) can initialize lazily after the unlock guards pass.
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().build()

    companion object {

        init {
            logd("ShizukuApplication", "init")

            Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_REDIRECT_STDERR))
            if (Build.VERSION.SDK_INT >= 28) {
                HiddenApiBypass.setHiddenApiExemptions("")
            }
            if (atLeast30) {
                System.loadLibrary("adb")
            }
        }
    }

    private fun init(context: Context?) {
        ShizukuSettings.initialize(context)
        LocaleDelegate.defaultLocale = ShizukuSettings.getLocale()
        // Value 3 is a legacy "follow system" constant once shipped in
        // night_mode_value; AppCompat only understands -1/1/2. Normalize once
        // at startup so devices that stored 3 get real FOLLOW_SYSTEM.
        val nightMode = when (ShizukuSettings.getNightMode()) {
            AppCompatDelegate.MODE_NIGHT_YES,
            AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM -> ShizukuSettings.getNightMode()
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    override fun onCreate() {
        super.onCreate()
        application = this
        init(this)
        if (getSystemService(android.os.UserManager::class.java)?.isUserUnlocked == true) {
            startupScope.launch {
                moe.shizuku.manager.utils.LegacyUpdateCleanup.cancel(this@ShizukuApplication)
                if (moe.shizuku.manager.utils.UserHandleCompat.myUserId() == 0) {
                    moe.shizuku.manager.worker.AdbRecoveryWorker.schedule(this@ShizukuApplication)
                }
            }
        }
        moe.shizuku.manager.service.WatchdogManager.init(this)
        moe.shizuku.manager.service.WatchdogManager.reconcileService(this)
        moe.shizuku.manager.service.SheveryNotificationManager.setup(this)
        moe.shizuku.manager.service.StartupNotificationManager.setup(this)
        moe.shizuku.manager.worker.AdbNetworkObserver.register(this)
        moe.shizuku.manager.adb.UsbDebugKeep.register(this)
        rikka.shizuku.Shizuku.addBinderReceivedListenerSticky {
            if (moe.shizuku.manager.service.WatchdogManager.isUserStopRequested()) {
                return@addBinderReceivedListenerSticky
            }
            // Permission is granted during this attach, so the first arm()
            // during startup often ran too early to stick.
            moe.shizuku.manager.adb.AdbArm.arm(this)
            moe.shizuku.manager.accessibility.WirelessDebugAccess.ensureEnabled(this)
            moe.shizuku.manager.adb.UsbDebugKeep.reassert(this)
            moe.shizuku.manager.adb.TcpPortKeep.ensure()
            moe.shizuku.manager.lock.LockScreenKeep.apply()
        }
    }

}
