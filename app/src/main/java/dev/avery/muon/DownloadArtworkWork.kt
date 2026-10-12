package dev.avery.muon

import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.io.IOException

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
    private val barrier:SavedStorageBarrier?=null,
    /** True only for a known executor whose rejection proves execute did not accept this task. */
    private val knownRejectionBeforeSubmission:Boolean=false,
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
    private var retained:Any?=null
    val isUncertain:Boolean get()=synchronized(lock) { retained!=null }
    private fun quarantine(owner:Any)=synchronized(lock) {
        if(retained==null) retained=owner
        pending.clear(); bytes=active?.bytes ?: 0L
        barrier?.invalidate()
    }

    /** False means this optional cover was not queued. Existing audio and covers are untouched. */
    fun fetch(id: String, url: String): Boolean = synchronized(lock) {
        if(retained!=null) return@synchronized false
        val existing = active?.takeIf { it.id == id } ?: pending.firstOrNull { it.id == id }
        if (existing != null) return@synchronized !existing.cancelled && existing.url == url
        val job = Job(id, url)
        if (!fits(job)) return@synchronized false
        enqueue(job)
    }

    fun remove(id: String) {
        val queued = synchronized(lock) {
            if(retained!=null) return
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
        if (!queued) protected { optional { removeCover(id) } }
    }

    /** No queued cleanup/replacement work: caller confirms deletion now under the SAME exclusive
     * gate. A shared fetch cannot be active under that permit; cancelled jobs cannot republish. */
    internal fun cancelForConfirmedRemoval(id:String,permit:SavedStorageBarrier.Lease) {
        val gate=barrier ?: throw IOException("Confirmed artwork removal needs its actual gate")
        if(!permit.exclusive || !permit.belongsTo(gate)) throw IOException("Artwork removal needs exclusive ownership")
        permit.check()
        synchronized(lock) {
            if(retained!=null) throw IOException("Artwork ownership uncertain")
            active?.takeIf {it.id==id}?.cancelled=true
            val iterator=pending.iterator()
            while(iterator.hasNext()) {
                val job=iterator.next()
                if(job.id==id) {job.cancelled=true; bytes-=job.bytes; iterator.remove()}
            }
        }
        permit.check()
    }
    private fun fits(job: Job) = pending.size + (if (active == null) 0 else 1) < maxJobs &&
        job.bytes <= maxTextBytes - bytes

    /** Under lock. Only one task enters the executor, regardless of how many covers wait. */
    private fun enqueue(job: Job): Boolean {
        pending.addLast(job)
        bytes += job.bytes
        if (scheduled) return true
        scheduled = true
        return try { worker.execute(::drain); true } catch (failure: Throwable) {
            if(failure is RejectedExecutionException && knownRejectionBeforeSubmission) {
                pending.remove(job); bytes -= job.bytes; scheduled = false
            } else {
                // execute may have accepted or even run the task before throwing. No retry/delete.
                quarantine(job)
            }
            false
        }
    }

    private fun drain() {
        while (true) {
            val job = synchronized(lock) {
                if (retained!=null || pending.isEmpty()) { scheduled = false; return }
                pending.removeFirst().also { active = it }
            }
            try {
                protected {
                    if (job.url == null) optional { removeCover(job.id) }
                    else if (!job.cancelled) optional { fetchCover(job.id, job.url) }
                    // Still under the SAME permit, after the actual fetch/close. Unknown close throws
                    // before this cleanup; no deletion is justified by a swallowed close failure.
                    if (job.cancelled && job.url != null) optional { removeCover(job.id) }
                }
            } finally {
                synchronized(lock) { active = null; if(retained==null) bytes -= job.bytes else bytes=0 }
            }
        }
    }
    /** Covers are optional. Known failures release admission; exact unknown owners stop all work. */
    private fun optional(work:()->Unit) {
        try { work() } catch(unknown:SavedCoverCloseUncertain) { throw unknown } catch(_:Exception) { }
    }
    private fun protected(work:()->Unit) {
        if(synchronized(lock) { retained!=null }) return
        val permit=try { barrier?.shared() } catch(_:IOException) { return }
        var unknown=false
        try { permit?.check(); work(); permit?.check() }
        catch(failure:SavedCoverCloseUncertain) {
            unknown=true; permit?.quarantine(failure.owner); quarantine(failure.owner)
        } catch(_:IOException) { /* Admission lost; refuse without retaining another task. */ }
        catch(failure:Throwable) {
            unknown=true; permit?.quarantine(failure); quarantine(failure)
        }
        finally { if(!unknown) permit?.close() }
    }
}
