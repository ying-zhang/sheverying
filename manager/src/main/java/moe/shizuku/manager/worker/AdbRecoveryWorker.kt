package moe.shizuku.manager.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.service.WatchdogManager
import rikka.shizuku.Shizuku

/** Best-effort fallback when connectivity/settings events were missed. Timing is not exact. */
class AdbRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (moe.shizuku.manager.utils.UserHandleCompat.myUserId() > 0 ||
            !ShizukuSettings.getStartOnBootAdb() ||
            ShizukuSettings.getLastLaunchMode() != ShizukuSettings.LaunchMethod.ADB ||
            WatchdogManager.isUserStopRequested()) return Result.success()
        if (Shizuku.pingBinder()) {
            moe.shizuku.manager.adb.TcpPortKeep.ensure()
            return Result.success()
        }
        return if (AdbStartWorker.recoverIfNeeded(applicationContext)) Result.success() else Result.retry()
    }

    companion object {
        fun schedule(context: Context): Operation = WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "adb_recovery_safety_net", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AdbRecoveryWorker>(15, TimeUnit.MINUTES)
                .setInitialDelay(15, TimeUnit.MINUTES)
                .build(),
        )
    }
}
