package dev.avery.muon

import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Application-thread queue feedback: replace only obsolete display waits, including native mutex
 * waiters behind unrelated app notices. Already-chosen Undo results and actual queue operations
 * remain owned by their caller. Render host data through [observeAction]: Material's public action
 * adapter records a tap BEFORE its continuation can be cancelled by a same-turn replacement.
 * No constructor job, shared-host ownership assumption or private framework API is needed.
 */
internal class QueueNotices(private val scope: CoroutineScope, private val host: SnackbarHostState) {
    private class Visuals(override val message: String, override val actionLabel: String?,
        override val duration: SnackbarDuration) : SnackbarVisuals {
        override val withDismissAction = false
        var actionChosen = false
    }
    private class Pending(val visuals: Visuals, val display: Deferred<SnackbarResult>)
    private var pending: Pending? = null

    /** Preserve the default Snackbar UI/semantics and every unrelated host caller unchanged. */
    fun observeAction(data: SnackbarData): SnackbarData {
        val visuals = data.visuals as? Visuals ?: return data
        return object : SnackbarData by data {
            override fun performAction() {
                visuals.actionChosen = true
                data.performAction()
            }
        }
    }

    suspend fun show(message: String, actionLabel: String? = null,
        duration: SnackbarDuration = SnackbarDuration.Short): SnackbarResult {
        currentCoroutineContext().ensureActive()
        pending?.let { if (!it.visuals.actionChosen) it.display.cancel() }
        // Before async: an immediate dispatcher can display the new item inline.
        host.currentSnackbarData?.dismiss()
        val visuals = Visuals(message, actionLabel, duration)
        val next = Pending(visuals, scope.async(start = CoroutineStart.UNDISPATCHED) { host.showSnackbar(visuals) })
        pending = next
        try { return next.display.await() }
        catch (cancelled: CancellationException) {
            // Replacement cancels only the display child; caller/screen cancellation still escapes.
            currentCoroutineContext().ensureActive()
            return SnackbarResult.Dismissed
        } finally {
            if (pending === next) pending = null
            next.display.cancel() // Also remove a native waiter when only its caller has closed.
        }
    }
}
