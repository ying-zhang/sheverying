package moe.shizuku.manager.utils

import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Await a database operation without blocking a thread or cancelling an already submitted write. */
internal suspend fun <T> ListenableFuture<T>.awaitCompletion(): T = suspendCancellableCoroutine { continuation ->
    addListener({
        try {
            continuation.resume(get())
        } catch (e: ExecutionException) {
            continuation.resumeWithException(e.cause ?: e)
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }, Executor { it.run() })
}
