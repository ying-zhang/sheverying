package moe.shizuku.manager.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.ShizukuStateMachine
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/** Durable root boot startup; never run su synchronously inside a broadcast. */
class RootStartWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (moe.shizuku.manager.utils.UserHandleCompat.myUserId() > 0 ||
            Shizuku.pingBinder()) return@withContext Result.success()
        if (moe.shizuku.manager.service.WatchdogManager.isUserStopRequested()) return@withContext Result.success()
        if (!ShizukuSettings.getStartOnBoot() ||
            ShizukuSettings.getLastLaunchMode() != ShizukuSettings.LaunchMethod.ROOT) {
            return@withContext Result.success()
        }
        try {
            if (!Shell.getShell().isRoot) {
                Shell.getCachedShell()?.close()
                throw IllegalStateException("Root shell is not available yet")
            }
            if (moe.shizuku.manager.service.WatchdogManager.isUserStopRequested() ||
                !ShizukuSettings.getStartOnBoot() ||
                ShizukuSettings.getLastLaunchMode() != ShizukuSettings.LaunchMethod.ROOT) {
                return@withContext Result.success()
            }
            ShizukuStateMachine.onStartRequested()
            val result = Shell.cmd(Starter.internalCommand).exec()
            check(result.isSuccess) { "Root starter exited with ${result.code}" }
            check(Starter.waitForBinder()) { "Root starter did not deliver binder" }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("RootStartWorker", "Root boot startup failed; retrying", e)
            ShizukuStateMachine.onStartFailed()
            Result.retry()
        }
    }

    companion object {
        fun enqueue(context: Context): Operation =
            WorkManager.getInstance(context).enqueueUniqueWork(
                "root_boot_start", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RootStartWorker>()
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
            )
    }
}
