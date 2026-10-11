package dev.avery.muon

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Optional prefetch may give up; foreground audio and explicit downloads have separate lifetimes. */
internal const val PLAYED_COPY_LIFETIME_MILLIS = 120_000L

/** Cancellation is specific to one copy, including a network call installed after cancellation. */
internal class PlayedCopyCancellation {
    @Volatile private var cancelled = false
    private var action: (() -> Unit)? = null
    val isCancelled: Boolean get() = cancelled

    fun onCancel(action: () -> Unit) {
        val alreadyCancelled = synchronized(this) { this.action = action; cancelled }
        if (alreadyCancelled) action()
    }

    fun cancel() {
        val cancel = synchronized(this) {
            if (cancelled) return
            cancelled = true
            action
        }
        cancel?.invoke()
    }
}

/**
 * One active optional copy and at most the latest waiting song. Maintenance is coalesced and runs
 * before another copy, after cancellation releases the current writer. Nothing mutates its cache
 * concurrently with that writer. The executor remains the store's single existing copy worker.
 */
internal class PlayedCopyWork(
    private val worker: Executor,
    private val deadlines: ScheduledExecutorService,
    private val lifetimeMillis: Long = PLAYED_COPY_LIFETIME_MILLIS,
    private val knownRejectionBeforeSubmission: Boolean = false,
) {
    private data class Copy(val id: String, val barrier: SavedStorageBarrier?, val run: (PlayedCopyCancellation) -> Unit)
    private data class Maintenance(val barrier: SavedStorageBarrier?, val run: () -> Unit)
    private val lock = Any()
    private var pending: Copy? = null
    private var clear: Maintenance? = null
    private var resize: Maintenance? = null
    private var active: Pair<String, PlayedCopyCancellation>? = null
    private var scheduled = false
    private var retained:Any?=null
    /** One exact uncertain source survives; discard queued closures and refuse maintenance/retry. */
    fun quarantine(owner:Any)=synchronized(lock) {
        if(retained==null) retained=owner
        pending=null; clear=null; resize=null
    }
    val isUncertain:Boolean get()=synchronized(lock) { retained!=null }

    fun copy(id: String, barrier: SavedStorageBarrier? = null, run: (PlayedCopyCancellation) -> Unit) = synchronized(lock) {
        if(retained!=null) return@synchronized
        val running = active
        if (running?.first == id && !running.second.isCancelled) return@synchronized
        pending = Copy(id, barrier, run)
        schedule()
    }

    fun clear(barrier: SavedStorageBarrier? = null, run: () -> Unit) {
        val cancel = synchronized(lock) {
            if(retained!=null) return
            pending = null
            clear = Maintenance(barrier, run)
            schedule()
            active?.second
        }
        cancel?.cancel()
    }

    fun resize(barrier: SavedStorageBarrier? = null, run: () -> Unit) {
        val cancel = synchronized(lock) {
            if(retained!=null) return
            resize = Maintenance(barrier, run)
            schedule()
            active?.second
        }
        cancel?.cancel()
    }

    /** Called under lock; only a single draining task is ever submitted. */
    private fun schedule() {
        if (retained!=null || scheduled || (pending == null && clear == null && resize == null)) return
        scheduled = true
        val owners = listOfNotNull(pending, clear, resize).toTypedArray()
        val barriers = listOfNotNull(pending?.barrier, clear?.barrier, resize?.barrier)
        try { worker.execute(::drain) } catch (failure: Throwable) {
            if (failure is RejectedExecutionException && knownRejectionBeforeSubmission) {
                scheduled = false
                pending = null; clear = null; resize = null
            } else {
                // An arbitrary Executor may accept the runnable and then throw. Keep the exact
                // bounded operation set reachable; a late drain must never run or submit a retry.
                barriers.forEach { it.invalidate() }
                quarantine(owners)
            }
            throw failure
        }
    }

    /** Hold the shared permit through the real closure, including source close and cleanup. */
    private fun protected(barrier: SavedStorageBarrier?, run: () -> Unit) {
        if (isUncertain) return
        val lease = if (barrier == null) null else try { barrier.shared() } catch (_: java.io.IOException) { return }
        try {
            if (isUncertain) return
            lease?.check()
            try { run() } catch (failure: PlayedCopyCloseUncertain) {
                quarantine(failure.owner)
                throw failure
            }
        } finally {
            val owner = synchronized(lock) { retained }
            if (owner != null) { barrier?.invalidate(); lease?.quarantine(owner) }
            else lease?.close()
        }
    }

    private fun drain() {
        try {
            while (true) {
                val run = synchronized(lock) {
                    if (retained != null) return
                    clear?.let { task -> clear = null; { protected(task.barrier, task.run) } }
                        ?: resize?.let { task -> resize = null; { protected(task.barrier, task.run) } }
                        ?: pending?.let { copy ->
                            pending = null
                            val token = PlayedCopyCancellation()
                            active = copy.id to token
                            val runCopy: () -> Unit = {
                                try {
                                    protected(copy.barrier) {
                                        val expiry = deadlines.schedule(Runnable { token.cancel() }, lifetimeMillis, TimeUnit.MILLISECONDS)
                                        try { if (!token.isCancelled) copy.run(token) }
                                        finally { expiry.cancel(false) }
                                    }
                                } finally { synchronized(lock) { active = null } }
                            }
                            runCopy
                        }
                } ?: return
                run()
            }
        } finally {
            // Known task failures release their permit; an uncertain writer retains it and blocks
            // maintenance. No new work is submitted just because an execute call threw.
            synchronized(lock) { active = null; scheduled = false; schedule() }
        }
    }
}
