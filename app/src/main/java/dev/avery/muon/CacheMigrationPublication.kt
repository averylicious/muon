@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.datasource.cache.Cache
import java.io.File
import java.io.IOException

/** Unwired one-resource opt-in coordinator. The caller owns the source and MUST hold availability,
 * writer, reader/removal and eviction exclusion until return. checkpoint verifies it plus cancellation
 * and deadline. open creates exclusively owned fresh targets and cleans up its own failed creation.
 * Neither originals nor uncertain replacements are deleted. A Ready receipt is not removal authority.
 * Pending journal rows survive restart; each retry uses a new target, never adopts a partial one. */
internal class CacheMigrationPublication(private val catalog:CachePartitionCatalog,
    private val journal:CacheMigrationJournal,private val open:(File)->Cache) {
    private var healthy=true
    @Synchronized fun migrate(source:Cache,key:String,checkpoint:()->Unit):MigrationRecord {
        if(!healthy) throw IOException("Migration coordinator unavailable after uncertain native ownership")
        checkpoint()
        val sourceUid=source.uid
        if(sourceUid<0) throw IOException("Migration source identity unavailable")
        val old=journal.find(key)
        if(old?.phase==MigrationPhase.Ready) throw IOException("Migration resource is already published")
        val candidate=catalog.reserveFresh(key)
        checkpoint()
        val ticket=journal.begin(candidate,sourceUid,old?.ticket?.token)
        fun checked() {
            checkpoint()
            if(source.uid!=sourceUid) throw IOException("Migration source identity changed")
            val row=journal.find(key)
            if(row?.ticket!=ticket || row.phase==MigrationPhase.Ready || row.phase==MigrationPhase.Uncertain)
                throw IOException("Migration ticket no longer owns this copy")
        }
        try {
            checked()
            val directory=catalog.directory(candidate)
            if(directory.exists()) throw IOException("Migration target appeared after reservation")
            val first=opening(directory,sourceUid)
            val uid:Long
            val copied:MigrationCopyEvidence
            try {
                uid=first.uid
                if(uid<0) throw IOException("Migration target identity unavailable")
                copied=CacheMigrationPreparation.copy(source,first,key,::checked)
            } finally { closing(first) }
            checked()
            val reopened=opening(directory,sourceUid)
            val verified:MigrationCopyEvidence
            try {
                if(reopened.uid!=uid) throw IOException("Migration target identity changed on reopen")
                verified=CacheMigrationPreparation.verify(source,reopened,key,::checked)
                if(verified!=copied) throw IOException("Migration copy receipt changed")
            } finally { closing(reopened) }
            checked()
            journal.verified(ticket,uid,verified)
            checked()
            return journal.publish(ticket)
        } catch(failure:Throwable) {
            try { journal.uncertain(ticket) } catch(recordFailure:Throwable) { failure.addSuppressed(recordFailure) }
            throw failure
        }
    }
    private fun opening(directory:File,sourceUid:Long):Cache=try {
        open(directory).also {
            // An alias of the source is not an independently owned target. Do not release it: that
            // could close the caller's source and its active readers. Refuse all further admission.
            if(it.uid==sourceUid) throw IOException("Migration target aliases the source")
        }
    } catch(failure:Throwable) { healthy=false; throw failure }
    private fun closing(cache:Cache) {
        try { cache.release() } catch(failure:Throwable) { healthy=false; throw failure }
    }
}
