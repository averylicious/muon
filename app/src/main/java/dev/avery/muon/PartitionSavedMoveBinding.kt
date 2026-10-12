package dev.avery.muon

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Fixed source/destination audio + own-cover proof. Explicit durable intent still needs actual
 * same-gate owners, renewed recovery consent and target revalidation before every source mutation. */
internal data class PartitionSavedMoveBinding(val source:PartitionMoveDestination,
    val destination:PartitionMoveDestination,val cover:SavedMoveCoverIdentity,val sourceTree:PartitionMoveTree?=null) {
    val key:String get()=source.ticket.allocation.key
    fun validate() {
        source.validate(); destination.validate(); cover.validate(); sourceTree?.requireUid(source.uid)
        if(key==destination.ticket.allocation.key || source.rootIdentity==destination.rootIdentity ||
            source.ticket.allocation.directory==destination.ticket.allocation.directory ||
            source.length!=destination.length || source.audioDigest!=destination.audioDigest ||
            source.metadataDigest!=destination.metadataDigest)
            throw IOException("Saved Move needs distinct exact owners and equal verified audio/metadata")
    }
    fun encode():ByteArray {
        validate(); val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use {out ->
            out.writeInt(if(sourceTree==null) 1 else 2); source.write(out); destination.write(out)
            out.writeLong(cover.length); out.writeBoolean(cover.digest!=null); cover.digest?.let(out::writeUTF)
            sourceTree?.write(out)
        }
        return bytes.toByteArray().also {if(it.size>PARTITION_SAVED_MOVE_BYTES) throw IOException("Saved Move binding exceeds budget")}
    }
    companion object {
        fun decode(bytes:ByteArray):PartitionSavedMoveBinding {
            if(bytes.size>PARTITION_SAVED_MOVE_BYTES) throw IOException("Saved Move binding exceeds budget")
            return DataInputStream(ByteArrayInputStream(bytes)).use {input ->
                val version=input.readInt(); if(version !in 1..2) throw IOException("Unsupported saved Move binding version")
                val source=PartitionMoveDestination.read(input); val destination=PartitionMoveDestination.read(input)
                val cover=SavedMoveCoverIdentity(input.readLong(),if(input.readBoolean()) input.readUTF() else null)
                val tree=if(version==2) PartitionMoveTree.read(input) else null
                if(input.available()!=0) throw IOException("Saved Move binding has trailing bytes")
                PartitionSavedMoveBinding(source,destination,cover,tree).also {it.validate()}
            }
        }
    }
}
