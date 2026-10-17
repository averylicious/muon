package dev.avery.muon

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID

/** Exact CLOSED allocation observed for an explicit Delete, not a fabricated Move destination.
 * A Delete may remove an owned incomplete audio tree; unlike Move it cannot promise a full copy.
 * This binding grants no deletion by itself: actual gate/volume/row/alias/cover checks still apply.
 */
internal data class PartitionSavedDeleteBinding(val volume:String,val rootIdentity:String,
    val ticket:PartitionSaveTicket,val uid:Long,val completionIdentity:String,val length:Long,
    val tree:PartitionMoveTree,val cover:SavedMoveCoverIdentity) {
    val key:String get()=ticket.allocation.key
    fun validate() {
        if(volume.isEmpty() || volume.length>1024 || rootIdentity.isEmpty() || rootIdentity.length>256 ||
            !key.startsWith(NEW_SAVE_PREFIX) || key==NEW_SAVE_PREFIX || key.length.toLong()*2>MIGRATION_KEY_BYTES ||
            uid<0 || length<=0) throw IOException("Saved Delete identity exceeds budget")
        for(value in listOf(ticket.allocation.directory,ticket.token)) {
            val parsed=try {UUID.fromString(value)} catch(_:IllegalArgumentException) {throw IOException("Saved Delete allocation malformed")}
            if(parsed.toString()!=value) throw IOException("Saved Delete allocation is not canonical")
        }
        if(completionIdentity.length!=64 || completionIdentity.any {it !in '0'..'9' && it !in 'a'..'f'})
            throw IOException("Saved Delete completion digest malformed")
        tree.requireUid(uid);cover.validate()
    }
    fun encode():ByteArray {
        validate();val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use {out ->
            out.writeInt(1);out.writeUTF(volume);out.writeUTF(rootIdentity);out.writeUTF(key)
            out.writeUTF(ticket.allocation.directory);out.writeUTF(ticket.token);out.writeLong(uid)
            out.writeUTF(completionIdentity);out.writeLong(length);tree.write(out)
            out.writeLong(cover.length);out.writeBoolean(cover.digest!=null);cover.digest?.let(out::writeUTF)
        }
        return bytes.toByteArray().also {if(it.size>PARTITION_SAVED_DELETE_BYTES) throw IOException("Saved Delete binding exceeds budget")}
    }
    companion object {
        fun decode(bytes:ByteArray):PartitionSavedDeleteBinding {
            if(bytes.size>PARTITION_SAVED_DELETE_BYTES) throw IOException("Saved Delete binding exceeds budget")
            return DataInputStream(ByteArrayInputStream(bytes)).use {input ->
                if(input.readInt()!=1) throw IOException("Unsupported saved Delete binding version")
                val volume=input.readUTF();val root=input.readUTF()
                val ticket=PartitionSaveTicket(CachePartitionAllocation(input.readUTF(),input.readUTF()),input.readUTF())
                val uid=input.readLong();val completed=input.readUTF();val length=input.readLong()
                val tree=PartitionMoveTree.read(input)
                val cover=SavedMoveCoverIdentity(input.readLong(),if(input.readBoolean()) input.readUTF() else null)
                if(input.available()!=0) throw IOException("Saved Delete binding has trailing bytes")
                PartitionSavedDeleteBinding(volume,root,ticket,uid,completed,length,tree,cover).also {it.validate()}
            }
        }
    }
}
