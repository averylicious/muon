@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.datasource.cache.Cache

/** Prepared migration adapter: source/native lifetime is borrowed but its exclusion permit survives
 * unknown raw/native close. All producers/removals/moves/played/readers must participate in the same
 * barrier before the app may use it. No source deletion or automatic retry/recovery authority.
 */
internal class BarrierCacheMigration(private val barrier:SavedStorageBarrier) {
    private data class Retained(val source:Cache,val publication:CacheMigrationPublication,val failure:Throwable)
    fun run(control:CacheMigrationControl,source:Cache,key:String,publication:CacheMigrationPublication,
        availableBytes:()->Long,checkpoint:()->Unit):MigrationRecord {
        val permit=barrier.exclusive()
        var quarantined=false
        try {
            return control.run(source,key,publication,availableBytes) {
                permit.check(); checkpoint(); permit.check()
            }
        } catch(failure:Throwable) {
            if(failure is MigrationIoUncertain || publication.ownershipUncertain) {
                permit.quarantine(Retained(source,publication,failure)); quarantined=true
            }
            throw failure
        } finally {
            // Quarantine deliberately retains the permit; never let its refusal replace the cause.
            if(!quarantined) permit.close()
        }
    }
}
