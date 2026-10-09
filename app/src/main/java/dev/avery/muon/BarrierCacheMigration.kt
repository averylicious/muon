@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.datasource.cache.Cache

/** Prepared migration adapter: source/native lifetime is borrowed but exclusion survives unknown
 * raw/native close. All production participants must share this gate before use. No source deletion,
 * forced close or automatic retry/recovery authority. Projection opens only AFTER exclusive admission.
 */
internal class BarrierCacheMigration(private val barrier:SavedStorageBarrier,
    /** Only known-idle native retirement under exclusive ownership; failure is uncertainty. */
    private val prepare:()->Unit={}) {
    private data class Retained(val adapter:BarrierCacheMigration,val source:Cache?,
        val sourceFactory:Any,val publication:CacheMigrationPublication,val failure:Throwable)
    fun run(control:CacheMigrationControl,source:Cache,key:String,publication:CacheMigrationPublication,
        availableBytes:()->Long,checkpoint:()->Unit):MigrationRecord =
        runProjected(control,key,publication,availableBytes,checkpoint) { source }

    /** A bounded read-only legacy source may need inspection before it exists as a Cache. Acquire
     * exclusive ownership first; factory uses the supplied checked callback throughout projection.
     * Failed creation must clean up known handles or throw MigrationIoUncertain retaining unknowns.
     */
    fun runProjected(control:CacheMigrationControl,key:String,publication:CacheMigrationPublication,
        availableBytes:()->Long,checkpoint:()->Unit,sourceFactory:((()->Unit))->Cache):MigrationRecord {
        val permit=barrier.exclusive()
        var quarantined=false
        var preparing=false
        var source:Cache?=null
        fun checked() { permit.check(); checkpoint(); permit.check() }
        try {
            return control.runProjected(key,publication,availableBytes,::checked) { budgeted ->
                budgeted(); preparing=true; prepare(); preparing=false; budgeted()
                source=sourceFactory(budgeted); budgeted()
                requireNotNull(source)
            }
        } catch(failure:Throwable) {
            if(preparing || failure is MigrationIoUncertain || failure is PartitionOwnershipUncertain || publication.ownershipUncertain) {
                permit.quarantine(Retained(this,source,sourceFactory,publication,failure)); quarantined=true
            }
            throw failure
        } finally { if(!quarantined) permit.close() }
    }
}
