package dev.avery.muon

import android.content.SharedPreferences
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.Base64
import java.util.UUID

internal enum class PartitionStartupPhase { Planned, Preparing, Ready }
internal data class PartitionStartupChoice(val token:String,val phase:PartitionStartupPhase,
    val phoneVolume:String,val cardVolume:String?,val start:PartitionShelfStart)

/** Explicit first opt-in receipt, saved BEFORE any provider/root allocation. Preparing after a
 * restart is an interrupted setup, not permission to retry Fresh. Renewed explicit Resume preserves
 * partial roots and lets actual strict owners refuse missing/unknown journals; no repair/fallback.
 * Ready always selects Resume. This owner must be retained by StorageStartup; a failed commit is
 * uncertain in this process even if SharedPreferences already changed its in-memory map.
 * UI/default startup selection remains disabled until production controls/lifecycle are finished. */
internal class PartitionStartupSettings(private val prefs:SharedPreferences,
    private val token:()->String={UUID.randomUUID().toString()}) {
    private var uncertain=false
    private fun available() {if(uncertain) throw IOException("Partition startup choice write is uncertain; fully restart Muon")}
    private fun valid(choice:PartitionStartupChoice) {
        val id=try {UUID.fromString(choice.token)} catch(_:IllegalArgumentException) {throw IOException("Partition startup token is malformed")}
        if(id.toString()!=choice.token) throw IOException("Partition startup token is not canonical")
        for(volume in listOfNotNull(choice.phoneVolume,choice.cardVolume))
            if(volume.isBlank() || volume.length>512 || volume.any {it.isISOControl()})
                throw IOException("Partition startup volume is unsupported")
        if(choice.cardVolume==choice.phoneVolume || choice.phase==PartitionStartupPhase.Ready && choice.start!=PartitionShelfStart.Resume)
            throw IOException("Partition startup phase or volumes differ")
    }
    private fun encode(choice:PartitionStartupChoice):String {
        valid(choice);val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use {out ->
            out.writeInt(1);out.writeUTF(choice.token);out.writeByte(choice.phase.ordinal)
            out.writeUTF(choice.phoneVolume);out.writeBoolean(choice.cardVolume!=null);choice.cardVolume?.let(out::writeUTF)
            out.writeByte(choice.start.ordinal)
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray()).also {
            if(it.length>MAX_CHOICE_CHARS) throw IOException("Partition startup choice exceeds budget")
        }
    }
    private fun decode(value:String):PartitionStartupChoice {
        if(value.isEmpty() || value.length>MAX_CHOICE_CHARS) throw IOException("Partition startup choice exceeds budget")
        try {
            val bytes=Base64.getDecoder().decode(value)
            if(Base64.getEncoder().encodeToString(bytes)!=value) throw IOException("Partition startup encoding differs")
            return DataInputStream(ByteArrayInputStream(bytes)).use {input ->
                if(input.readInt()!=1) throw IOException("Partition startup choice version differs")
                val token=input.readUTF();val phase=PartitionStartupPhase.entries.getOrNull(input.readUnsignedByte())
                    ?: throw IOException("Partition startup phase differs")
                val phone=input.readUTF();val card=if(input.readBoolean()) input.readUTF() else null
                val start=PartitionShelfStart.entries.getOrNull(input.readUnsignedByte())
                    ?: throw IOException("Partition startup root mode differs")
                if(input.read()!=-1) throw IOException("Partition startup choice has trailing data")
                PartitionStartupChoice(token,phase,phone,card,start).also {
                    valid(it);if(encode(it)!=value) throw IOException("Partition startup representation differs")
                }
            }
        } catch(failure:IllegalArgumentException) {throw IOException("Partition startup choice is malformed",failure)}
    }
    @Synchronized fun read():PartitionStartupChoice {
        available()
        if(StorageBackendSelection.read(prefs)!=StorageBackendSelection.PartitionV1)
            throw IOException("Partition startup was not explicitly selected")
        val value=try {prefs.getString(KEY,null)} catch(_:ClassCastException) {throw IOException("Partition startup choice has wrong type")}
            ?: throw IOException("Partition startup receipt is missing")
        return decode(value)
    }
    private fun current(expected:PartitionStartupChoice) {
        if(read()!=expected) throw IOException("Partition startup choice changed since confirmation")
    }
    private fun write(choice:PartitionStartupChoice):PartitionStartupChoice {
        available();val value=encode(choice)
        try {
            if(!prefs.edit().putString(StorageBackendSelection.KEY,"partition-v1").putString(KEY,value).commit())
                throw IOException("Partition startup choice was not durably confirmed")
        } catch(failure:Exception) {uncertain=true;throw IOException("Partition startup choice write is uncertain",failure)}
        return choice
    }
    @Synchronized fun request(plan:PartitionStorePlan,explicitConsent:Boolean):PartitionStartupChoice {
        available()
        if(!explicitConsent) throw IOException("Partition startup needs explicit opt-in")
        if(StorageBackendSelection.read(prefs)!=StorageBackendSelection.Legacy || prefs.contains(KEY))
            throw IOException("Existing partition startup choice cannot be replaced")
        plan.validate()
        if(plan.phone.start!=PartitionShelfStart.Fresh || plan.card?.start==PartitionShelfStart.Resume)
            throw IOException("First partition opt-in requires an explicit Fresh plan")
        return write(PartitionStartupChoice(token(),PartitionStartupPhase.Planned,plan.phone.volume,plan.card?.volume,PartitionShelfStart.Fresh))
    }
    /** Called before the actual app preparer opens even its provider. No filesystem inference. */
    @Synchronized fun begin(expected:PartitionStartupChoice,plan:PartitionStorePlan):PartitionStartupChoice {
        current(expected);plan.validate()
        if(expected.phase==PartitionStartupPhase.Preparing)
            throw IOException("Partition setup was interrupted; explicitly review Resume before retrying")
        if(plan.phone.volume!=expected.phoneVolume || (plan.card?.volume ?: plan.missingCardVolume)!=expected.cardVolume ||
            plan.phone.start!=expected.start || plan.card?.let {it.start!=expected.start}==true)
            throw IOException("Partition startup plan differs from the confirmed choice")
        if(plan.missingCardVolume!=null && expected.phase!=PartitionStartupPhase.Ready)
            throw IOException("Only completed setup may resume with its SD card absent")
        return if(expected.phase==PartitionStartupPhase.Ready) expected else write(expected.copy(phase=PartitionStartupPhase.Preparing))
    }
    /** Only the actual preparer calls this after every selected root/journal is owned and ready,
     * before any manager binding. A failed write leaves actual resources retained and no fallback. */
    @Synchronized fun prepared(expected:PartitionStartupChoice):PartitionStartupChoice {
        current(expected)
        if(expected.phase==PartitionStartupPhase.Planned) throw IOException("Partition setup was never begun")
        return if(expected.phase==PartitionStartupPhase.Ready) expected
            else write(expected.copy(phase=PartitionStartupPhase.Ready,start=PartitionShelfStart.Resume))
    }
    @Synchronized fun reviewResume(expected:PartitionStartupChoice,explicitConsent:Boolean):PartitionStartupChoice {
        current(expected)
        if(!explicitConsent || expected.phase!=PartitionStartupPhase.Preparing)
            throw IOException("Interrupted partition setup needs explicit renewed Resume confirmation")
        return write(expected.copy(phase=PartitionStartupPhase.Planned,start=PartitionShelfStart.Resume))
    }
    companion object {
        internal const val KEY="partitionStartupChoiceV1"
        internal const val MAX_CHOICE_CHARS=4096
    }
}
