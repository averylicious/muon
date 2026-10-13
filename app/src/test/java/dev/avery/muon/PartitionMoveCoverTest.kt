package dev.avery.muon

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PartitionMoveCoverTest {
    @get:Rule val folders=TemporaryFolder()
    private fun entry(art:DownloadArt,id:String):File=DownloadArt::class.java
        .getDeclaredMethod("entryFile",String::class.java).apply {isAccessible=true}.invoke(art,id) as File
    private val old="saved/old-fixture"
    private val new=NEW_SAVE_PREFIX+"new-fixture"
    @Test fun verifiedCopyPreservesOriginalAndOlderSharedCoverAndCancelsQueuedReplacement() {
        val dir=folders.newFolder(); val gate=SavedStorageBarrier(); val art=DownloadArt(dir,barrier=gate)
        val bytes=ByteArray(20000) {(it%127).toByte()}; val source=entry(art,old).apply {writeBytes(bytes)}
        val older=File(dir,"42").apply {writeBytes(byteArrayOf(8,9))}
        var queued:Runnable?=null; var fetched=0
        val work=DownloadArtworkWork(Executor {queued=it},{_,_ ->fetched++},art::removeEntry,barrier=gate)
        assertTrue(work.fetch(new,"http://127.0.0.1/cover"))
        val permit=gate.exclusive()
        try {
            work.cancelForConfirmedRemoval(old,permit); work.cancelForConfirmedRemoval(new,permit)
            val receipt=art.copyEntryConfirmed(old,new,permit); receipt.validate()
            assertEquals(bytes.size.toLong(),receipt.length); assertNotNull(receipt.digest)
            assertEquals(receipt,art.copyEntryConfirmed(old,new,permit))
            art.verifyCopiedEntry(old,new,receipt,permit)
            assertArrayEquals(bytes,source.readBytes()); assertArrayEquals(bytes,entry(art,new).readBytes())
            assertArrayEquals(byteArrayOf(8,9),older.readBytes())
            assertFalse(dir.listFiles()!!.any {it.name.endsWith(".part")})
        } finally {permit.close()}
        requireNotNull(queued).run(); assertEquals(0,fetched); assertTrue(gate.quiescent)
    }
    @Test fun differentExistingTargetOrMissingOriginalNeverOverwritesOrAdoptsIt() {
        val dir=folders.newFolder(); val gate=SavedStorageBarrier(); val art=DownloadArt(dir,barrier=gate)
        val source=entry(art,old).apply {writeBytes(byteArrayOf(1,2))}
        val target=entry(art,new).apply {writeBytes(byteArrayOf(3,4))}; val permit=gate.exclusive()
        try {
            assertThrows(IOException::class.java) {art.copyEntryConfirmed(old,new,permit)}
            assertArrayEquals(byteArrayOf(1,2),source.readBytes()); assertArrayEquals(byteArrayOf(3,4),target.readBytes())
            source.delete(); assertThrows(IOException::class.java) {art.copyEntryConfirmed(old,new,permit)}
            assertArrayEquals(byteArrayOf(3,4),target.readBytes())
            target.delete(); assertEquals(SavedMoveCoverIdentity(0,null),art.copyEntryConfirmed(old,new,permit))
        } finally {permit.close()}
    }
    @Test fun foreignGateSymlinkAndOversizedOriginalRefuseBeforeTargetCreation() {
        val dir=folders.newFolder(); val gate=SavedStorageBarrier(); val art=DownloadArt(dir,barrier=gate)
        val source=entry(art,old).apply {writeBytes(byteArrayOf(1))}
        val foreign=SavedStorageBarrier().exclusive()
        try {assertThrows(IOException::class.java) {art.copyEntryConfirmed(old,new,foreign)}} finally {foreign.close()}
        val permit=gate.exclusive()
        try {
            source.delete(); val elsewhere=folders.newFile().apply {writeBytes(byteArrayOf(7))}
            java.nio.file.Files.createSymbolicLink(source.toPath(),elsewhere.toPath())
            assertThrows(IOException::class.java) {art.copyEntryConfirmed(old,new,permit)}
            java.nio.file.Files.delete(source.toPath())
            java.io.RandomAccessFile(source,"rw").use {it.setLength(4L*1024*1024+1)}
            assertThrows(IOException::class.java) {art.copyEntryConfirmed(old,new,permit)}
            assertFalse(entry(art,new).exists()); assertArrayEquals(byteArrayOf(7),elsewhere.readBytes())
        } finally {permit.close()}
    }
    @Test fun failedInputCloseRetainsExactReaderAndNeverPublishesOrRetries() {
        val dir=folders.newFolder(); val gate=SavedStorageBarrier(); var reader:FileInputStream?=null; var closes=0
        val art=DownloadArt(dir,barrier=gate,inputs={file ->object:FileInputStream(file) {
            init {reader=this}
            override fun close() {closes++; throw IOException("injected close")}
            fun fixtureClose()=super.close()
        }})
        val source=entry(art,old).apply {writeBytes(byteArrayOf(1,2))}; val permit=gate.exclusive()
        val error=assertThrows(SavedCoverCloseUncertain::class.java) {art.copyEntryConfirmed(old,new,permit)}
        assertSame(reader,error.owner.input); assertNotNull(error.owner.disposition); assertEquals(1,closes)
        assertTrue(source.exists()); assertFalse(entry(art,new).exists()); assertFalse(gate.allowsShared)
        assertThrows(IOException::class.java) {art.copyEntryConfirmed(old,new,permit)}
        assertEquals(1,closes); assertThrows(IOException::class.java) {permit.close()}
        // Fixture cleanup only; production must not retry a close whose result is unknown.
        reader!!.javaClass.getDeclaredMethod("fixtureClose").invoke(reader)
    }
    @Test fun failedOutputCloseKeepsExactTemporaryWriterAndOriginal() {
        val dir=folders.newFolder(); val gate=SavedStorageBarrier(); var writer:FileOutputStream?=null; var closes=0
        val art=DownloadArt(dir,barrier=gate,outputs={file ->object:FileOutputStream(file) {
            init {writer=this}
            override fun close() {closes++; throw IOException("injected output close")}
            fun fixtureClose()=super.close()
        }})
        val source=entry(art,old).apply {writeBytes(byteArrayOf(1,2))}; val permit=gate.exclusive()
        val error=assertThrows(SavedCoverCloseUncertain::class.java) {art.copyEntryConfirmed(old,new,permit)}
        assertSame(writer,error.owner.output); assertNotNull(error.owner.disposition); assertEquals(1,closes)
        assertTrue(source.exists()); assertFalse(entry(art,new).exists()); assertFalse(gate.allowsShared)
        assertTrue(dir.listFiles()!!.any {it.name.endsWith(".part")})
        assertThrows(IOException::class.java) {art.copyEntryConfirmed(old,new,permit)}; assertEquals(1,closes)
        writer!!.javaClass.getDeclaredMethod("fixtureClose").invoke(writer)
    }
    @Test fun knownWriteFailureClosesAndRemovesOnlyItsOwnTemporaryFile() {
        val dir=folders.newFolder(); val gate=SavedStorageBarrier(); var closed=0
        val art=DownloadArt(dir,barrier=gate,outputs={file ->object:FileOutputStream(file) {
            override fun write(bytes:ByteArray,offset:Int,length:Int) {throw IOException("injected write")}
            override fun close() {closed++; super.close()}
        }})
        val source=entry(art,old).apply {writeBytes(byteArrayOf(1,2))}
        val unrelated=File(dir,"other.part").apply {writeBytes(byteArrayOf(9))}; val permit=gate.exclusive()
        try {
            assertThrows(IOException::class.java) {art.copyEntryConfirmed(old,new,permit)}
            assertEquals(1,closed); assertFalse(entry(art,new).exists()); assertArrayEquals(byteArrayOf(1,2),source.readBytes())
            assertEquals(listOf("other.part"),dir.listFiles()!!.filter {it.name.endsWith(".part")}.map {it.name})
            assertTrue(unrelated.exists()); permit.check()
        } finally {permit.close()}
        assertTrue(gate.quiescent)
    }
}
