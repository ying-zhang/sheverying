package moe.shizuku.manager.module.update

import android.app.Application
import android.content.Context
import androidx.work.*
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SheveryUpdateCheckWorkerTest {
    @Test fun `previously persisted update work terminates without a network request`() = runBlocking {
        val executor = Executor { it.run() }
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? = null
        }
        val params = WorkerParameters(UUID.randomUUID(),Data.EMPTY,emptyList(),WorkerParameters.RuntimeExtras(),
            0,0,executor,Dispatchers.Default,WorkManagerTaskExecutor(executor),factory,
            ProgressUpdater { _,_,_ -> throw UnsupportedOperationException() },
            ForegroundUpdater { _,_,_ -> throw UnsupportedOperationException() })
        assertEquals(ListenableWorker.Result.success(),SheveryUpdateCheckWorker(RuntimeEnvironment.getApplication(),params).doWork())
    }
}
