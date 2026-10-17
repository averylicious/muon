package dev.avery.muon

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Main-thread load ownership shared by online refresh and opening downloads. */
internal class LibraryLoads(private val scope: CoroutineScope, private val loading: (Boolean) -> Unit) {
    private var current: Load? = null

    inner class Load internal constructor() {
        internal var job: Job? = null

        /** Check after suspension, before publishing data, errors or progress. */
        suspend fun ensureCurrent() {
            currentCoroutineContext().ensureActive()
            if (current !== this) throw CancellationException("Library load replaced")
        }
    }

    fun start(block: suspend Load.() -> Unit) {
        if (current != null) return
        val load = Load()
        current = load
        loading(true)
        // Enter try/finally even if the scope is already cancelled. The token is installed before
        // launch: synchronous completion must not depend on assigning the returned Job first.
        load.job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { load.ensureCurrent(); load.block() }
            finally {
                if (current === load) {
                    current = null
                    loading(false)
                }
            }
        }
    }

    fun cancel() {
        val old = current
        current = null // Revoke ownership before cancellation can run old cleanup inline.
        old?.job?.cancel()
        loading(false)
    }
}
