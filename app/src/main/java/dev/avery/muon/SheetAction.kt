package dev.avery.muon

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Cancellation, failure or a refused hide must not perform the user's selected side effect. */
internal suspend fun completeSheetAction(hide: suspend () -> Unit, hidden: () -> Boolean,
    dismiss: () -> Unit, action: () -> Unit) {
    hide()
    currentCoroutineContext().ensureActive()
    if (!hidden()) return
    dismiss()
    action()
}
