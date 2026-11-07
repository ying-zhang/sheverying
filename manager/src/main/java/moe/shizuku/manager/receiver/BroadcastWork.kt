package moe.shizuku.manager.receiver

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The deadline runs independently of the work, including blocked Binder calls. */
internal class BroadcastWork(
    private val scope: CoroutineScope,
    private val timer: ScheduledExecutorService,
    private val timeoutMs: Long,
) {
    fun run(finish: () -> Unit, onTimeout: () -> Unit, work: suspend () -> Unit): Job {
        val finished = AtomicBoolean(false)
        fun finishOnce() {
            if (finished.compareAndSet(false, true)) finish()
        }
        val job = scope.launch(start = CoroutineStart.LAZY) { work() }
        val deadline = timer.schedule({
            if (finished.compareAndSet(false, true)) {
                try {
                    onTimeout()
                } finally {
                    // Release the receiver at the deadline, but let the
                    // scheduling coroutine finish its bounded persistence call.
                    // Cancelling here can stop it before enqueueUniqueWork().
                    finish()
                }
            }
        }, timeoutMs, TimeUnit.MILLISECONDS)
        job.invokeOnCompletion {
            deadline.cancel(false)
            finishOnce()
        }
        job.start()
        return job
    }

    companion object {
        val boot = create("boot-broadcast-deadline")
        val control = create("control-broadcast-deadline")

        private fun create(threadName: String) = BroadcastWork(
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
            Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, threadName).apply { isDaemon = true }
            },
            6_000L,
        )
    }
}
