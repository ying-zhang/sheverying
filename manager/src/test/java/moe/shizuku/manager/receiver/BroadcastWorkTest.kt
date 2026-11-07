package moe.shizuku.manager.receiver

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BroadcastWorkTest {
    @Test fun deadlineFinishesBroadcastEvenWhileWorkIsBlocked() = runBlocking {
        val timer = Executors.newSingleThreadScheduledExecutor()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val finishes = AtomicInteger()
        val timeouts = AtomicInteger()
        try {
            val job = BroadcastWork(scope, timer, 300).run(
                finish = { finishes.incrementAndGet(); finished.countDown() },
                onTimeout = { timeouts.incrementAndGet() },
            ) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) // Simulate an uninterruptible Binder call.
            }
            check(entered.await(3, TimeUnit.SECONDS))
            check(finished.await(3, TimeUnit.SECONDS))
            assertFalse(job.isCompleted)
            assertEquals(1, timeouts.get())
            release.countDown()
            withTimeout(3_000) { job.join() }
            assertEquals(1, finishes.get())
        } finally {
            release.countDown()
            scope.cancel()
            timer.shutdownNow()
        }
    }

    @Test fun successfulWorkFinishesOnceWithoutTimeout() = runBlocking {
        val timer = Executors.newSingleThreadScheduledExecutor()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val finishes = AtomicInteger()
        val timeouts = AtomicInteger()
        val finished = CountDownLatch(1)
        try {
            val job = BroadcastWork(scope, timer, 5_000).run(
                { finishes.incrementAndGet(); finished.countDown() }, { timeouts.incrementAndGet() }, {})
            withTimeout(3_000) { job.join() }
            // join() can resume before an invokeOnCompletion handler has returned.
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            assertEquals(1, finishes.get())
            assertEquals(0, timeouts.get())
        } finally {
            scope.cancel()
            timer.shutdownNow()
        }
    }

    @Test fun deadlineFinishesBeforeAStillRunningSchedulingJob() = runBlocking {
        val timer = Executors.newSingleThreadScheduledExecutor()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val finished = CountDownLatch(1)
        val finishes = AtomicInteger()
        val timeouts = AtomicInteger()
        try {
            val job = BroadcastWork(scope, timer, 100).run(
                { finishes.incrementAndGet(); finished.countDown() },
                { timeouts.incrementAndGet() },
            ) { delay(250) }
            check(finished.await(3, TimeUnit.SECONDS))
            withTimeout(3_000) { job.join() }
            assertEquals(1, timeouts.get())
            assertEquals(1, finishes.get())
        } finally {
            scope.cancel()
            timer.shutdownNow()
        }
    }
}
