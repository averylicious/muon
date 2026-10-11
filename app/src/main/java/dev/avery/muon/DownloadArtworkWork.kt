package dev.avery.muon

import java.util.ArrayDeque
import java.util.concurrent.Executor

internal const val DOWNLOAD_ARTWORK_JOBS = 128
internal const val DOWNLOAD_ARTWORK_TEXT_BYTES = 256L * 1024

/**
 * Optional saved covers: one worker invocation, bounded jobs/text including the active job, no
 * per-song closures on the executor. Saturation refuses only a cover, never its audio command.
 * A removal cancels a waiting/running fetch; its final cleanup cannot be overtaken by that fetch.
 * If cleanup cannot queue, delete that one internal cover on the caller instead of retaining an
 * unlimited removal backlog. No network or audio/index operation runs in that fallback.
 */
internal class DownloadArtworkWork(
    private val worker: Executor,
    private val fetchCover: (String, String) -> Unit,
    private val removeCover: (String) -> Unit,
    private val maxJobs: Int = DOWNLOAD_ARTWORK_JOBS,
    private val maxTextBytes: Long = DOWNLOAD_ARTWORK_TEXT_BYTES,
) {
    private class Job(val id: String, val url: String?) {
        @Volatile var cancelled = false
        val bytes: Long get() = 64L + 2L * (id.length.toLong() + (url?.length ?: 0))
    }
    private val lock = Any()
    private val pending = ArrayDeque<Job>()
    private var active: Job? = null
    private var bytes = 0L
    private var scheduled = false

    /** False means this optional cover was not queued. Existing audio and covers are untouched. */
    fun fetch(id: String, url: String): Boolean = synchronized(lock) {
        val existing = active?.takeIf { it.id == id } ?: pending.firstOrNull { it.id == id }
        if (existing != null) return@synchronized !existing.cancelled && existing.url == url
        val job = Job(id, url)
        if (!fits(job)) return@synchronized false
        enqueue(job)
    }

    fun remove(id: String) {
        val queued = synchronized(lock) {
            active?.takeIf { it.id == id }?.cancelled = true
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val job = iterator.next()
                if (job.id != id) continue
                if (job.url == null) return // A cleanup for this entry already waits.
                bytes -= job.bytes
                iterator.remove()
            }
            // Cleanup can displace an optional waiting fetch, but never another cleanup.
            val job = Job(id, null)
            val iterator2 = pending.iterator()
            while (!fits(job) && iterator2.hasNext()) {
                val old = iterator2.next()
                if (old.url != null) { bytes -= old.bytes; iterator2.remove() }
            }
            fits(job) && enqueue(job)
        }
        if (!queued) runCatching { removeCover(id) }
    }

    private fun fits(job: Job) = pending.size + (if (active == null) 0 else 1) < maxJobs &&
        job.bytes <= maxTextBytes - bytes

    /** Under lock. Only one task enters the executor, regardless of how many covers wait. */
    private fun enqueue(job: Job): Boolean {
        pending.addLast(job)
        bytes += job.bytes
        if (scheduled) return true
        scheduled = true
        return try { worker.execute(::drain); true } catch (_: RuntimeException) {
            // With no submitted drain there cannot be any other active/waiting task here.
            pending.remove(job); bytes -= job.bytes; scheduled = false; false
        }
    }

    private fun drain() {
        while (true) {
            val job = synchronized(lock) {
                if (pending.isEmpty()) { scheduled = false; return }
                pending.removeFirst().also { active = it }
            }
            try {
                if (job.url == null) runCatching { removeCover(job.id) }
                else if (!job.cancelled) runCatching { fetchCover(job.id, job.url) }
            } finally {
                // A caller-side deletion can race a blocking fetch's publication. Delete again after
                // that fetch has finished; no permanent per-ID tombstones or cancellation map.
                if (job.cancelled && job.url != null) runCatching { removeCover(job.id) }
                synchronized(lock) { active = null; bytes -= job.bytes }
            }
        }
    }
}
