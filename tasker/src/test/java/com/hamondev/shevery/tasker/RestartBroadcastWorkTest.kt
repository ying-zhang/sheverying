package com.hamondev.shevery.tasker

import java.util.concurrent.*
import java.util.concurrent.atomic.*
import org.junit.Assert.*
import org.junit.Test

class RestartBroadcastWorkTest {
    private fun scenario(body: (ExecutorService, ScheduledExecutorService) -> Unit) {
        val workers = Executors.newCachedThreadPool()
        val timer = Executors.newSingleThreadScheduledExecutor()
        try { body(workers, timer) }
        finally { workers.shutdownNow(); timer.shutdownNow() }
    }

    @Test fun deathDuringStopStartsOnceAfterStopDispatch() = scenario { workers, timer ->
        val callback = AtomicReference<() -> Unit>()
        val stopReturned = AtomicBoolean(false)
        val starts = AtomicInteger()
        val finishes = AtomicInteger()
        val done = CountDownLatch(1)
        val removed = CountDownLatch(1)
        RestartBroadcastWork(workers, timer, 100, 2_000).run(
            { true }, { callback.set(it) }, { removed.countDown() },
            { callback.get()(); callback.get()(); stopReturned.set(true) },
            { assertTrue(stopReturned.get()); starts.incrementAndGet() },
            { finishes.incrementAndGet(); done.countDown() },
            { throw AssertionError(it) }, { fail("unexpected deadline") },
        )
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertTrue(removed.await(1, TimeUnit.SECONDS))
        assertEquals(1, starts.get()); assertEquals(1, finishes.get())
    }

    @Test fun fallbackRestartsWithoutDeathNotification() = scenario { workers, timer ->
        val stopped = AtomicBoolean(false)
        val starts = AtomicInteger()
        val done = CountDownLatch(1)
        RestartBroadcastWork(workers, timer, 100, 2_000).run(
            { true }, {}, {}, { stopped.set(true) },
            { assertTrue(stopped.get()); starts.incrementAndGet() }, { done.countDown() },
            { throw AssertionError(it) }, { fail("unexpected deadline") },
        )
        assertTrue(done.await(3, TimeUnit.SECONDS)); assertEquals(1, starts.get())
    }

    @Test fun blockedStopStillFinishesAndDoesNotSendLateStart() = scenario { workers, timer ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val removed = CountDownLatch(1)
        val finishes = AtomicInteger()
        val starts = AtomicInteger()
        val timeouts = AtomicInteger()
        RestartBroadcastWork(workers, timer, 50, 300).run(
            { true }, {}, { removed.countDown() },
            { entered.countDown(); release.await(3, TimeUnit.SECONDS) },
            { starts.incrementAndGet() }, { finishes.incrementAndGet(); done.countDown() },
            {}, { timeouts.incrementAndGet() },
        )
        assertTrue(entered.await(2, TimeUnit.SECONDS)); assertTrue(done.await(2, TimeUnit.SECONDS))
        release.countDown(); assertTrue(removed.await(2, TimeUnit.SECONDS))
        workers.shutdown(); assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS))
        assertEquals(1, finishes.get()); assertEquals(1, timeouts.get()); assertEquals(0, starts.get())
    }

    @Test fun blockedStartCannotHoldBroadcastPastDeadline() = scenario { workers, timer ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val finishes = AtomicInteger()
        val timeouts = AtomicInteger()
        RestartBroadcastWork(workers, timer, 50, 300).run(
            { false }, {}, {}, { fail("already stopped") },
            { entered.countDown(); release.await(3, TimeUnit.SECONDS) },
            { finishes.incrementAndGet(); done.countDown() }, {}, { timeouts.incrementAndGet() },
        )
        assertTrue(entered.await(2, TimeUnit.SECONDS)); assertTrue(done.await(2, TimeUnit.SECONDS))
        release.countDown(); workers.shutdown(); assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS))
        assertEquals(1, finishes.get()); assertEquals(1, timeouts.get())
    }
}
