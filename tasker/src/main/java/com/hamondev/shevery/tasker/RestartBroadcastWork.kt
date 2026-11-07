package com.hamondev.shevery.tasker

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A Binder death triggers restart; independent timers bound fallback and receiver lifetime. */
internal class RestartBroadcastWork(
    private val worker: Executor,
    private val timer: ScheduledExecutorService,
    private val fallbackMs: Long = 3_500L,
    private val deadlineMs: Long = 6_000L,
) {
    fun run(
        isAlive: () -> Boolean,
        addDeadListener: (() -> Unit) -> Unit,
        removeDeadListener: () -> Unit,
        stop: () -> Unit,
        start: () -> Unit,
        finish: () -> Unit,
        onFailure: (Throwable) -> Unit,
        onDeadline: () -> Unit,
    ) {
        val finished = AtomicBoolean(false)
        val triggered = AtomicBoolean(false)
        val registered = AtomicBoolean(false)
        val stopDispatched = AtomicBoolean(false)
        val deathObserved = AtomicBoolean(false)
        val fallback = AtomicReference<ScheduledFuture<*>?>()
        fun cleanup() {
            if (registered.compareAndSet(true, false)) {
                try { removeDeadListener() } catch (failure: Throwable) { onFailure(failure) }
            }
        }
        fun finishOnce() {
            if (finished.compareAndSet(false, true)) finish()
        }
        val deadline = timer.schedule({
            if (finished.compareAndSet(false, true)) {
                try { onDeadline() } finally { finish() }
                fallback.get()?.cancel(false)
                worker.execute { cleanup() }
            }
        }, deadlineMs, TimeUnit.MILLISECONDS)
        val restart = {
            if (triggered.compareAndSet(false, true)) {
                fallback.get()?.cancel(false)
                worker.execute {
                    try { if (!finished.get()) start() }
                    catch (failure: Throwable) { onFailure(failure) }
                    finally {
                        try { cleanup() } finally { finishOnce(); deadline.cancel(false) }
                    }
                }
            }
            Unit
        }
        val onDead = {
            deathObserved.set(true)
            if (stopDispatched.get() && !finished.get()) restart()
            Unit
        }
        worker.execute {
            try {
                if (!finished.get()) {
                    if (!isAlive()) restart()
                    else {
                        addDeadListener(onDead)
                        registered.set(true)
                        if (finished.get() || triggered.get()) cleanup()
                        if (!finished.get() && !triggered.get()) {
                            stop()
                            stopDispatched.set(true)
                            if (deathObserved.get() && !finished.get()) restart()
                            if (!finished.get() && !triggered.get()) {
                                val scheduled = timer.schedule({ restart() }, fallbackMs, TimeUnit.MILLISECONDS)
                                fallback.set(scheduled)
                                if (finished.get() || triggered.get()) scheduled.cancel(false)
                            }
                        }
                    }
                }
            } catch (failure: Throwable) {
                onFailure(failure)
                try { cleanup() } finally { finishOnce(); deadline.cancel(false) }
            } finally {
                if (finished.get() || triggered.get()) cleanup()
            }
        }
    }

    companion object {
        val shared = RestartBroadcastWork(
            Executors.newCachedThreadPool { task -> Thread(task, "tasker-restart").apply { isDaemon = true } },
            Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "tasker-restart-deadline").apply { isDaemon = true } },
        )
    }
}
