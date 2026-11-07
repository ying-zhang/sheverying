package moe.shizuku.manager.utils

import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WorkCompletionTest {
    @Test fun cancellationStopsWaitingWithoutCancellingAnAlreadySubmittedWrite() = runBlocking {
        val write = SettableFuture.create<String>()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { write.awaitCompletion() }
        waiter.cancelAndJoin()
        assertFalse(write.isCancelled)
        assertFalse(write.isDone)
        write.set("persisted")
        assertEquals("persisted", write.get())
    }

    @Test fun listenerAwaitsWriteCompletionAndUnwrapsFailure() = runBlocking {
        val write = SettableFuture.create<String>()
        val failure = IllegalStateException("database unavailable")
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { write.awaitCompletion() }
        }
        assertFalse(waiter.isCompleted)
        write.setException(failure)
        // Coroutine stack recovery may copy the exception; verify the actual cause, not identity.
        val actual = waiter.await().exceptionOrNull()
        assertTrue(actual is IllegalStateException)
        assertEquals(failure.message, actual?.message)
    }
}
