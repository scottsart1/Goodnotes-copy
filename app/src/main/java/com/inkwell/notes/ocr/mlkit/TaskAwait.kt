package com.inkwell.notes.ocr.mlkit

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val DirectExecutor = Executor { it.run() }

/** Suspends until the Play services task completes; failures are rethrown. */
internal suspend fun <T> Task<T>.await(): T {
    if (isComplete) {
        exception?.let { throw it }
        if (isCanceled) throw CancellationException("Task was cancelled")
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
    return suspendCancellableCoroutine { cont ->
        addOnCompleteListener(DirectExecutor) { t ->
            val e = t.exception
            when {
                e != null -> cont.resumeWithException(e)
                t.isCanceled -> cont.cancel()
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    cont.resume(t.result as T)
                }
            }
        }
    }
}
