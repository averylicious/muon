package dev.avery.muon

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Queue-screen/application-thread owner: one displayed notice and only its newest replacement.
 * Keep one native host caller instead of accumulating suspended showSnackbar calls. Dismiss the
 * current display without cancelling its coroutine: an Undo tap that already won must still reach
 * its caller. Removal/admission/Undo work stays outside this owner and is never cancelled by a
 * newer message. The screen's existing scope owns the worker and closes the mailbox on disposal.
 */
internal class QueueNotices(scope: CoroutineScope, private val host: SnackbarHostState) {
    private class Request(val message: String, val action: String?, val duration: SnackbarDuration) {
        val result = CompletableDeferred<SnackbarResult>()
    }
    private val requests = Channel<Request>(Channel.CONFLATED, onUndeliveredElement = {
        it.result.complete(SnackbarResult.Dismissed)
    })
    private var active: Request? = null
    private var newest: Request? = null

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                for (request in requests) {
                    // A receive may already be scheduled while a burst replaces the mailbox item.
                    if (request !== newest || !request.result.isActive) {
                        request.result.complete(SnackbarResult.Dismissed)
                        if (newest === request) newest = null
                        continue
                    }
                    active = request
                    try {
                        request.result.complete(host.showSnackbar(request.message,
                            actionLabel = request.action, duration = request.duration))
                    } catch (failure: Throwable) {
                        request.result.completeExceptionally(failure)
                        throw failure
                    } finally {
                        active = null
                        if (newest === request) newest = null
                        request.result.complete(SnackbarResult.Dismissed)
                    }
                }
            } finally { newest = null; requests.cancel() }
        }
    }

    suspend fun show(message: String, actionLabel: String? = null,
        duration: SnackbarDuration = SnackbarDuration.Short): SnackbarResult {
        currentCoroutineContext().ensureActive()
        val request = Request(message, actionLabel, duration)
        newest = request
        // Dismiss before send: an immediate dispatcher may show the new item inline on send.
        host.currentSnackbarData?.dismiss()
        if (requests.trySend(request).isFailure) {
            if (newest === request) newest = null
            return SnackbarResult.Dismissed
        }
        try { return request.result.await() }
        finally {
            // Caller cancellation must clear only its own display, not a later notice or operation.
            if (!request.result.isCompleted) {
                request.result.cancel()
                if (active === request) host.currentSnackbarData?.dismiss()
            }
        }
    }
}
