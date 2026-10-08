package dev.avery.muon

import java.util.concurrent.Executor
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
) {
    private data class Copy(val id: String, val run: (PlayedCopyCancellation) -> Unit)
    private val lock = Any()
    private var pending: Copy? = null
    private var clear: (() -> Unit)? = null
    private var resize: (() -> Unit)? = null
    private var active: Pair<String, PlayedCopyCancellation>? = null
    private var scheduled = false

    fun copy(id: String, run: (PlayedCopyCancellation) -> Unit) = synchronized(lock) {
        val running = active
        if (running?.first == id && !running.second.isCancelled) return@synchronized
        pending = Copy(id, run)
        schedule()
    }

    fun clear(run: () -> Unit) {
        val cancel = synchronized(lock) {
            pending = null
            clear = run
            schedule()
            active?.second
        }
        cancel?.cancel()
    }

    fun resize(run: () -> Unit) {
        val cancel = synchronized(lock) {
            resize = run
            schedule()
            active?.second
        }
        cancel?.cancel()
    }

    /** Called under lock; only a single draining task is ever submitted. */
    private fun schedule() {
        if (scheduled || (pending == null && clear == null && resize == null)) return
        scheduled = true
        try { worker.execute(::drain) } catch (e: RuntimeException) { scheduled = false; throw e }
    }

    private fun drain() {
        try {
            while (true) {
                val run = synchronized(lock) {
                    clear?.also { clear = null } ?: resize?.also { resize = null } ?: pending?.let { copy ->
                        pending = null
                        val token = PlayedCopyCancellation()
                        active = copy.id to token
                        val runCopy: () -> Unit = {
                            val expiry = deadlines.schedule(Runnable { token.cancel() }, lifetimeMillis, TimeUnit.MILLISECONDS)
                            try { if (!token.isCancelled) copy.run(token) }
                            finally { expiry.cancel(false); synchronized(lock) { active = null } }
                        }
                        runCopy
                    }
                } ?: return
                run()
            }
        } finally {
            // A task exception must not leave pending maintenance stranded behind a stale busy flag.
            synchronized(lock) { active = null; scheduled = false; schedule() }
        }
    }
}
