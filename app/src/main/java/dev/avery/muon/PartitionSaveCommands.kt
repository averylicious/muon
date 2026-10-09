@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.common.C
import androidx.media3.common.util.Util
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Prepared production-command owner. A process token binds one exact supported request to one fresh
 * allocation; only that request may cross into the manager/factory. Holds bounded scalar receipts,
 * not request.data/URI/cover payloads. Restart grants no replay/recovery authority. The production
 * caller still supplies app-wide source/move/removal/eviction exclusion and complete legacy census.
 * This is not a separate service path: integrate through OfflineStore's existing delivery gate.
 */
internal class PartitionSaveCommands(private val catalog:CachePartitionCatalog,
    private val saves:PartitionSaveJournal,private val migrations:CacheMigrationJournal,
    private val native:PartitionNativeOwner,
    /** Must include all legacy/published/index claims, under the production exclusion barrier. */
    private val unclaimed:(String)->Boolean,
    private val available:()->Boolean,
    private val capacity:Int=DOWNLOAD_COMMAND_COUNT) {
    init { require(capacity in 1..DOWNLOAD_COMMAND_COUNT) }
    internal data class Command(val token:String,val ticket:PartitionSaveTicket)
    private enum class Phase { Prepared, Forwarding, Submitted, Unconfirmed }
    private class Entry(val command:Command,val digest:ByteArray,var phase:Phase=Phase.Prepared)
    private val entries=LinkedHashMap<String,Entry>()
    val active:Int @Synchronized get()=entries.size
    private fun live() { if(!available()) throw IOException("Saved volume unavailable") }
    private fun owned(entry:Entry) {
        live()
        val ticket=entry.command.ticket
        if(catalog.find(ticket.allocation.key)!=ticket.allocation || saves.find(ticket.allocation.key)?.ticket!=ticket ||
            migrations.find(ticket.allocation.key)!=null) throw IOException("Save command allocation changed")
    }
    @Synchronized fun prepare(request:DownloadRequest):Command {
        live(); val key=partitionSaveRequestKey(request)
        if(entries.size>=capacity) throw PartitionCacheBusy()
        if(entries.values.any { it.command.ticket.allocation.key==key } || catalog.find(key)!=null ||
            saves.find(key)!=null || migrations.find(key)!=null || !unclaimed(key))
            throw IOException("Save key is already owned or its census is unavailable")
        val identity=partitionSaveRequestDigest(request)
        val ticket=saves.begin(catalog.reserve(key))
        val command=Command(UUID.randomUUID().toString(),ticket)
        owned(Entry(command,identity))
        entries[command.token]=Entry(command,identity)
        return command
    }
    /** Before manager.addDownload; invalid/replayed tokens never touch a manager/index/cover. */
    @Synchronized fun forward(command:Command,request:DownloadRequest,previous:Download?):Boolean {
        val entry=entries[command.token] ?: return false
        if(entry.command!=command || entry.phase!=Phase.Prepared || previous!=null) return false
        return try {
            val key=partitionSaveRequestKey(request); owned(entry)
            if(key!=command.ticket.allocation.key || !MessageDigest.isEqual(entry.digest,partitionSaveRequestDigest(request)) ||
                saves.find(key)?.phase!=PartitionSavePhase.Reserved || !unclaimed(key)) return false
            entry.phase=Phase.Forwarding; true
        } catch(_:Exception) { false }
    }
    /** Service-return acknowledgement is distinct from persisted audio. Unknown forwarding stays owned. */
    @Synchronized fun delivered(command:Command,accepted:Boolean,unconfirmed:Boolean=false) {
        val entry=entries[command.token] ?: return
        if(entry.command!=command || entry.phase!=Phase.Forwarding) return
        if(accepted) entry.phase=Phase.Submitted
        else if(unconfirmed) entry.phase=Phase.Unconfirmed
        else entries.remove(command.token) // Reservation/journal remain; never adopt/reuse/delete them.
    }
    /** Validate the actual full manager request before constructing its native downloader. */
    @Synchronized fun download(request:DownloadRequest) {
        val key=partitionSaveRequestKey(request)
        val entry=entries.values.firstOrNull { it.command.ticket.allocation.key==key } ?: throw IOException("No current save command")
        owned(entry)
        if(entry.phase==Phase.Prepared || !MessageDigest.isEqual(entry.digest,partitionSaveRequestDigest(request)))
            throw IOException("Save request was not forwarded by its exact owner")
    }
    @Synchronized fun open(key:String):Cache {
        val entry=entries.values.firstOrNull { it.command.ticket.allocation.key==key } ?: throw IOException("No current save command")
        owned(entry)
        if(entry.phase==Phase.Prepared) throw IOException("Save command not forwarded")
        return when(saves.find(key)?.phase) {
            PartitionSavePhase.Reserved -> native.openNewSave(entry.command.ticket)
            PartitionSavePhase.Closed -> native.openSaved(key)
            else -> throw IOException("Save has no known clean opening authority")
        }
    }
    /** Exact terminal callback releases only scalar process receipts, never bytes/rows/covers. */
    @Synchronized fun terminal(download:Download):Boolean {
        val entry=entries.values.firstOrNull { it.command.ticket.allocation.key==download.request.id } ?: return false
        try { partitionSaveRequestKey(download.request) } catch(_:Exception) { return false }
        if(entry.phase==Phase.Prepared || !MessageDigest.isEqual(entry.digest,partitionSaveRequestDigest(download.request))) return false
        if(download.state!=Download.STATE_COMPLETED && download.state!=Download.STATE_FAILED) return false
        if(download.state==Download.STATE_COMPLETED) {
            try { partitionSaveRequestKey(download.request); owned(entry) } catch(_:Exception) { return false }
            if(saves.find(download.request.id)?.phase!=PartitionSavePhase.Closed) return false
        }
        entries.remove(entry.command.token); return true
    }
}

internal fun partitionSaveRequestKey(request:DownloadRequest):String {
    val key=request.customCacheKey
    if(key==null || key!=request.id || !key.startsWith(NEW_SAVE_PREFIX) ||
        key.length.toLong()*2>MIGRATION_KEY_BYTES || request.uri.toString().length.toLong()*2>MIGRATION_KEY_BYTES ||
        (request.mimeType?.length ?: 0).toLong()*2>MIGRATION_KEY_BYTES ||
        request.keySetId!=null || request.streamKeys.isNotEmpty() || request.byteRange!=null || request.timeRange!=null ||
        Util.inferContentTypeForUriAndMimeType(request.uri,request.mimeType)!=C.CONTENT_TYPE_OTHER || !moveCommandFits(request))
        throw IOException("Save command needs a bounded full progressive request")
    return key
}
/** Length-prefixed UTF-16 scalars/raw data, fixed digest retained. Not remote-audio authentication. */
internal fun partitionSaveRequestDigest(request:DownloadRequest):ByteArray {
    val hash=MessageDigest.getInstance("SHA-256")
    fun number(n:Int) { for(shift in 24 downTo 0 step 8) hash.update((n ushr shift).toByte()) }
    fun text(s:String?) {
        if(s==null) { number(-1); return }; number(s.length)
        for(c in s) { hash.update((c.code ushr 8).toByte()); hash.update(c.code.toByte()) }
    }
    text(request.id); text(request.uri.toString()); text(request.customCacheKey); text(request.mimeType)
    number(request.data.size); hash.update(request.data)
    return hash.digest()
}
