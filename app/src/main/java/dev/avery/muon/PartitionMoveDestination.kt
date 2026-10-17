@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.DefaultContentMetadata
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Bounded persisted Move identity, not a grant to delete. The actual destination owner must
 * reobserve its volume/root, exact Closed allocation/UID, completed row, full metadata and bytes
 * under the SAME exclusive gate before any source retirement or explicit recovery. */
internal data class PartitionMoveDestination(val volume:String,val rootIdentity:String,
    val ticket:PartitionSaveTicket,val uid:Long,val completionIdentity:String,val length:Long,
    val metadataDigest:String,val audioDigest:String) {
    fun validate() {
        val key=ticket.allocation.key
        if(volume.isEmpty() || volume.length>1024 || rootIdentity.isEmpty() || rootIdentity.length>256 ||
            !key.startsWith(NEW_SAVE_PREFIX) || key==NEW_SAVE_PREFIX || key.length.toLong()*2>MIGRATION_KEY_BYTES ||
            uid<0 || length<=0) throw IOException("Move destination exceeds supported identity")
        for(value in listOf(ticket.allocation.directory,ticket.token)) {
            val parsed=try {UUID.fromString(value)} catch(_:IllegalArgumentException) {throw IOException("Move destination allocation is malformed")}
            if(parsed.toString()!=value) throw IOException("Move destination allocation is noncanonical")
        }
        for(value in listOf(completionIdentity,metadataDigest,audioDigest))
            if(value.length!=64 || value.any {it !in '0'..'9' && it !in 'a'..'f'})
                throw IOException("Move destination digest is malformed")
    }
    fun write(out:DataOutputStream) {
        validate(); out.writeUTF(volume); out.writeUTF(rootIdentity); out.writeUTF(ticket.allocation.key)
        out.writeUTF(ticket.allocation.directory); out.writeUTF(ticket.token); out.writeLong(uid)
        out.writeUTF(completionIdentity); out.writeLong(length); out.writeUTF(metadataDigest); out.writeUTF(audioDigest)
    }
    companion object {
        fun read(input:DataInputStream)=PartitionMoveDestination(input.readUTF(),input.readUTF(),
            PartitionSaveTicket(CachePartitionAllocation(input.readUTF(),input.readUTF()),input.readUTF()),
            input.readLong(),input.readUTF(),input.readLong(),input.readUTF(),input.readUTF()).also {it.validate()}
    }
}

/** Stable full-field digest. Measure BEFORE sorting; no concatenated metadata/blob copy is built. */
internal fun partitionMoveMetadataDigest(metadata:DefaultContentMetadata):String {
    PartitionResourceCache.measured(metadata,PartitionResourceLimits())
    val hash=MessageDigest.getInstance("SHA-256")
    fun size(n:Int) {for(shift in 24 downTo 0 step 8) hash.update((n ushr shift).toByte())}
    for((name,value) in metadata.entrySet().sortedBy {it.key}) {
        size(name.length); for(c in name) {hash.update((c.code ushr 8).toByte()); hash.update(c.code.toByte())}
        size(value.size); hash.update(value)
    }
    return hash.digest().joinToString("") {"%02x".format(it)}
}

/** One actual local reader, one close attempt. A digest is returned only AFTER real close. Unknown
 * construction/close retains the exact factory/reader through a typed owner; caller retains native
 * and borrowed provider/legacy owners too. No retry, upstream fallback, deletion or recovery grant. */
internal class SavedAudioDigest {
    private var used=false
    private var source:DataSource?=null
    private var construction:DataSource.Factory?=null
    private var uncertainty:Throwable?=null
    /** Full immutable binding checks bracket actual reading AND known close. The caller keeps
     * its real exclusive lease; every block still checks fresh storage/cancellation. No digest
     * escapes after a changed binding, and unknown construction/close retains this same owner. */
    fun readVerified(spec:DataSpec,factory:DataSource.Factory,checkpoint:()->Unit,verifyBinding:()->Unit):String {
        verifyBinding()
        val digest=read(spec,factory,checkpoint)
        verifyBinding()
        return digest
    }
    fun read(spec:DataSpec,factory:DataSource.Factory,checkpoint:()->Unit):String {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Saved digest requires a worker"}
        if(used || spec.position!=0L || spec.length<=0) throw IOException("Saved digest requires one exact full read")
        used=true; var failure:Throwable?=null
        val hash=MessageDigest.getInstance("SHA-256")
        try {
            checkpoint(); construction=factory; source=factory.createDataSource(); construction=null
            val actual=requireNotNull(source); actual.open(spec)
            val bytes=ByteArray(LOCAL_SAVED_READ_BYTES); var left=spec.length
            while(left>0) {
                checkpoint(); val count=actual.read(bytes,0,minOf(left,bytes.size.toLong()).toInt())
                if(count<=0) throw IOException("Saved digest audio ended before its exact length")
                hash.update(bytes,0,count); left-=count
            }
            checkpoint()
            if(actual.read(bytes,0,1)!=androidx.media3.common.C.RESULT_END_OF_INPUT)
                throw IOException("Saved digest audio exceeds its exact length")
        } catch(error:Throwable) {failure=error}
        finally {
            if(construction!=null) uncertainty=failure ?: IOException("Saved digest construction is unknown")
            source?.let {actual ->
                try {actual.close(); source=null}
                catch(close:Throwable) {
                    if(failure!=null && failure!==close) close.addSuppressed(failure)
                    uncertainty=close
                }
            }
        }
        uncertainty?.let {throw PartitionOwnershipUncertain(this,it)}
        failure?.let {throw it}
        checkpoint(); return hash.digest().joinToString("") {"%02x".format(it)}
    }
}
