package dev.avery.muon

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads and closes a finite response, with cancellation attached until its body is consumed.
 * Blocking: callers must already be on IO, as the API, artwork and discovery callers are.
 * Returning an open Response first would lose cancellation while the caller blocks reading it.
 * Keep synchronous execution so callers on limited IO lanes retain their concurrency bound.
 */
internal suspend fun <T> Call.readCancellable(read: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        if (continuation.isActive) {
            try {
                val value = execute().use(read)
                continuation.resume(value)
            } catch (failure: Exception) {
                // Socket errors caused by cancellation belong to the cancelled coroutine.
                if (continuation.isActive) continuation.resumeWithException(failure)
            }
        }
    }
