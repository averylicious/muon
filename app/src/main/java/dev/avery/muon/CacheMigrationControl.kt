@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.datasource.cache.Cache
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean

internal enum class MigrationWorkPhase { Waiting, Preparing, Running, Ready, Cancelled, Expired, Failed, Uncertain }
internal data class MigrationWorkProgress(val phase:MigrationWorkPhase, val copyBytes:Long=0)

/** Single-use synchronous control for an explicitly opted-in resource, not a scheduler or source
 * exclusion barrier. Caller runs off the UI thread and holds source/volume/writer/reader/removal and
 * eviction exclusion throughout. Exactly one scalar status survives; no progress event queue,
 * Throwable, metadata or history is retained. Cancellation/deadline are cooperative BETWEEN I/O;
 * they cannot interrupt a blocked native operation or undo an already published Ready receipt.
 * Free-space admission is conservative headroom, not a reservation or a promise writes cannot fail.
 */
internal class CacheMigrationControl(timeoutMillis:Long,
    private val headroomBytes:Long=16L*1024*1024,
    private val nanoTime:()->Long=System::nanoTime) {
    private val durationNanos:Long
    private val used=AtomicBoolean(false)
    private val cancelled=AtomicBoolean(false)
    @Volatile var progress=MigrationWorkProgress(MigrationWorkPhase.Waiting)
        private set
    init {
        require(timeoutMillis in 1L..24L*60*60*1000) { "Migration timeout outside supported range" }
        require(headroomBytes>=0) { "Migration headroom cannot be negative" }
        durationNanos=timeoutMillis*1_000_000
    }
    fun cancel() { cancelled.set(true) }

    /** availableBytes must observe the destination volume, not an unrelated internal filesystem.
     * Cancellation before run performs no reservation; cancellation after copying preserves both
     * original and uncertain target through CacheMigrationPublication's existing journal behavior.
     */
    fun run(source:Cache,key:String,publication:CacheMigrationPublication,
        availableBytes:()->Long,checkpoint:()->Unit):MigrationRecord =
        runProjected(key,publication,availableBytes,checkpoint) { source }

    /** Source projection/scanning belongs to the same single-use cancel/deadline budget as copying.
     * The factory must use its supplied checkpoint throughout and retain unknown close ownership.
     * This grants no storage exclusion; BarrierCacheMigration owns that separately.
     */
    fun runProjected(key:String,publication:CacheMigrationPublication,availableBytes:()->Long,
        checkpoint:()->Unit,sourceFactory:((()->Unit))->Cache):MigrationRecord {
        if(!used.compareAndSet(false,true)) throw IOException("Migration control already consumed")
        val started=nanoTime()
        var bytes=0L
        var terminal=MigrationWorkPhase.Failed
        fun budget() {
            if(cancelled.get()) { terminal=MigrationWorkPhase.Cancelled; throw InterruptedIOException("Migration cancelled") }
            val elapsed=nanoTime()-started
            if(elapsed<0 || elapsed>=durationNanos) { terminal=MigrationWorkPhase.Expired; throw InterruptedIOException("Migration deadline reached") }
        }
        fun checked() {
            budget(); checkpoint(); budget()
        }
        try {
            progress=MigrationWorkProgress(MigrationWorkPhase.Preparing)
            checked()
            val source=sourceFactory(::checked)
            checked()
            for(range in CacheMigrationPreparation.ranges(source,key,::checked)) {
                if(range.length>Long.MAX_VALUE-bytes) throw IOException("Migration copy size overflows")
                bytes+=range.length
            }
            if(headroomBytes>Long.MAX_VALUE-bytes) throw IOException("Migration space requirement overflows")
            checked()
            val free=availableBytes()
            checked()
            if(free<0 || free<bytes+headroomBytes) throw IOException("Insufficient migration temporary space")
            progress=MigrationWorkProgress(MigrationWorkPhase.Running,bytes)
            val ready=publication.migrate(source,key,::checked)
            // The durable publication is the commit point. A later cancellation does not turn a
            // successful migration into a failure or authorize deleting either copy.
            progress=MigrationWorkProgress(MigrationWorkPhase.Ready,bytes)
            return ready
        } catch(failure:Throwable) {
            progress=MigrationWorkProgress(
                if(failure is MigrationIoUncertain || publication.ownershipUncertain) MigrationWorkPhase.Uncertain else terminal,bytes)
            throw failure
        }
    }
}
