package dev.avery.muon

import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PartitionRemovalCoverTest {
    @get:Rule val folders=TemporaryFolder()
    private fun entry(art:DownloadArt,id:String):File=DownloadArt::class.java
        .getDeclaredMethod("entryFile",String::class.java).apply {isAccessible=true}.invoke(art,id) as File
    @Test fun confirmedRemovalCancelsQueuedFetchAndDeletesOnlyTheEntryOwnedCover() {
        val gate=SavedStorageBarrier(); val dir=folders.newFolder(); val art=DownloadArt(dir,barrier=gate)
        val id="saved/fixture"; val cover=entry(art,id).apply {writeBytes(byteArrayOf(1,2))}
        val older=File(dir,"42").apply {writeBytes(byteArrayOf(7))}
        var queued:Runnable?=null; var fetched=0
        val work=DownloadArtworkWork(Executor {queued=it},{_,_ ->fetched++},art::removeEntry,barrier=gate)
        assertTrue(work.fetch(id,"http://127.0.0.1:7814/api1/albumart/42"))
        val permit=gate.exclusive()
        try {
            work.cancelForConfirmedRemoval(id,permit); art.removeEntryConfirmed(id,permit)
            assertFalse(cover.exists()); art.removeEntryConfirmed(id,permit)
        } finally {permit.close()}
        requireNotNull(queued).run()
        assertEquals(0,fetched); assertArrayEquals(byteArrayOf(7),older.readBytes()); assertTrue(gate.quiescent)
    }
    @Test fun absentOptionalCoverDirectoryCanFinishDeleteWithoutCreatingItOrTouchingOtherFiles() {
        val parent=folders.newFolder();val dir=File(parent,"covers")
        val unrelated=File(parent,"unrelated").apply {writeBytes(byteArrayOf(6,7))}
        val gate=SavedStorageBarrier();val art=DownloadArt(dir,barrier=gate)
        val permit=gate.exclusive()
        try {
            val expected=art.savedDeleteCover("saved/without-cover",permit)
            assertEquals(SavedMoveCoverIdentity(0,null),expected)
            art.removeSavedDeleteCoverConfirmed("saved/without-cover",expected,permit)
            assertFalse(dir.exists());assertArrayEquals(byteArrayOf(6,7),unrelated.readBytes());permit.check()
        } finally {permit.close()}
        assertTrue(gate.quiescent)
    }
    @Test fun exactDeleteRecoveryAcceptsAlreadyAbsentCoverAndDirectoryWithoutInventingACopy() {
        val parent=folders.newFolder();val dir=File(parent,"covers").apply {check(mkdir())}
        val gate=SavedStorageBarrier();val art=DownloadArt(dir,barrier=gate);val id="saved/interrupted-cover"
        val file=entry(art,id).apply {writeBytes(byteArrayOf(1,3,5))};val permit=gate.exclusive()
        try {
            val expected=art.savedDeleteCover(id,permit);assertEquals(3L,expected.length)
            // Fixture models completed cover unlink before a prior process interruption.
            java.nio.file.Files.delete(file.toPath());java.nio.file.Files.delete(dir.toPath())
            art.removeSavedDeleteCoverConfirmed(id,expected,permit)
            assertFalse(dir.exists());assertFalse(file.exists());permit.check()
        } finally {permit.close()}
        assertTrue(gate.quiescent)
    }

    @Test fun confirmedCoverAndQueueRemovalRefuseForeignGateBeforeMutation() {
        val gate=SavedStorageBarrier(); val art=DownloadArt(folders.newFolder(),barrier=gate)
        val id="saved/fixture"; val cover=entry(art,id).apply {writeBytes(byteArrayOf(1,2))}
        var queued:Runnable?=null; var fetched=0
        val work=DownloadArtworkWork(Executor {queued=it},{_,_ ->fetched++},art::removeEntry,barrier=gate)
        assertTrue(work.fetch(id,"http://127.0.0.1:7814/api1/albumart/42"))
        val foreign=SavedStorageBarrier().exclusive()
        try {
            assertThrows(IOException::class.java) {work.cancelForConfirmedRemoval(id,foreign)}
            assertThrows(IOException::class.java) {art.removeEntryConfirmed(id,foreign)}
            assertArrayEquals(byteArrayOf(1,2),cover.readBytes())
        } finally {foreign.close()}
        requireNotNull(queued).run(); assertEquals(1,fetched)
        assertArrayEquals(byteArrayOf(1,2),cover.readBytes())
    }
}
