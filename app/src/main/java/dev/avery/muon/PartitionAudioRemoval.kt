@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.datasource.cache.Cache
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** Caller owns the exact native cache and actual exclusive storage gate. Media3's published
 * CachedContent.removeSpan ignores File.delete=false, so its empty in-memory spans alone cannot
 * authorize deleting a saved record. Retain actual native/I/O owners if this function fails. */
internal fun removePartitionAudioConfirmed(cache:Cache,key:String,checkpoint:()->Unit) {
    data class Selected(val span:androidx.media3.datasource.cache.CacheSpan,val file:File,
        val identity:Any,val modified:java.nio.file.attribute.FileTime)
    val ownership=MigrationIoOwnership()
    checkpoint()
    val spans=cache.getCachedSpans(key)
    if(spans.size>MIGRATION_RANGES) throw IOException("Partition removal span budget exceeded")
    val selected=spans.map {span ->
        checkpoint(); val file=span.file ?: throw IOException("Partition removal has no actual file")
        if(file.absoluteFile!=file.canonicalFile || Files.isSymbolicLink(file.toPath()) ||
            span.key!=key || span.length<=0) throw IOException("Partition removal path differs")
        val attributes=Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        if(!attributes.isRegularFile || attributes.size()!=span.length)
            throw IOException("Partition removal file changed")
        Selected(span,file,attributes.fileKey() ?: throw IOException("Partition removal identity unavailable"),attributes.lastModifiedTime())
    }
    for(entry in selected) {
        checkpoint()
        val attributes=Files.readAttributes(entry.file.toPath(),BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        if(!attributes.isRegularFile || attributes.fileKey()!=entry.identity || attributes.size()!=entry.span.length ||
            attributes.lastModifiedTime()!=entry.modified || entry.file.absoluteFile!=entry.file.canonicalFile)
            throw IOException("Partition removal file changed before unlink")
        cache.removeSpan(entry.span)
        if(Files.exists(entry.file.toPath(),LinkOption.NOFOLLOW_LINKS))
            throw IOException("Native span removal did not delete its actual file")
        checkpoint()
    }
    // Clear the separately owned full metadata only after every selected audio file is absent.
    checkpoint(); cache.removeResource(key); checkpoint()
    if(cache.getCachedSpans(key).isNotEmpty()) throw IOException("Native partition spans remain")
    for(parent in selected.map {requireNotNull(it.file.parentFile)}.distinct()) {
        checkpoint()
        if(parent.absoluteFile!=parent.canonicalFile || Files.isSymbolicLink(parent.toPath()))
            throw IOException("Partition removal directory changed")
        val before=Files.readAttributes(parent.toPath(),BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        if(!before.isDirectory || before.fileKey()==null) throw IOException("Partition removal directory identity unavailable")
        ownership.useOwned({FileChannel.open(parent.toPath(),StandardOpenOption.READ)}) {it.force(true)}
        val after=Files.readAttributes(parent.toPath(),BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        if(!after.isDirectory || before.fileKey()!=after.fileKey()) throw IOException("Partition removal directory replaced")
    }
    for(entry in selected) if(Files.exists(entry.file.toPath(),LinkOption.NOFOLLOW_LINKS))
        throw IOException("Partition removal file reappeared")
    checkpoint()
}
