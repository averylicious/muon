package dev.avery.muon

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PartitionSavedMoveJournalTest {
    @get:Rule val folders=TemporaryFolder()
    private fun <T> worker(work:()->T):T {
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {return try {executor.submit<T> {work()}.get(10,TimeUnit.SECONDS)}
            catch(error:java.util.concurrent.ExecutionException) {throw requireNotNull(error.cause)} }
        finally {executor.shutdownNow(); assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS))}
    }
    private fun binding():PartitionSavedMoveBinding {
        val source=PartitionMoveDestination("phone","source-root",PartitionSaveTicket(
            CachePartitionAllocation("saved/source","00000000-0000-0000-0000-000000000001"),
            "00000000-0000-0000-0000-000000000002"),17,"a".repeat(64),4,"b".repeat(64),"c".repeat(64))
        val target=source.copy(volume="card",rootIdentity="target-root",ticket=PartitionSaveTicket(
            CachePartitionAllocation("saved/target","00000000-0000-0000-0000-000000000003"),
            "00000000-0000-0000-0000-000000000004"),uid=18,completionIdentity="d".repeat(64))
        return PartitionSavedMoveBinding(source,target,SavedMoveCoverIdentity(0,null))
    }
    @Test fun bindingRejectsChangedAudioAliasedOwnerUnsupportedVersionAndTrailingPayload() {
        val expected=binding(); assertEquals(expected,PartitionSavedMoveBinding.decode(expected.encode()))
        assertThrows(IOException::class.java) {expected.copy(destination=expected.destination.copy(audioDigest="e".repeat(64))).encode()}
        assertThrows(IOException::class.java) {expected.copy(destination=expected.source).encode()}
        assertThrows(IOException::class.java) {PartitionSavedMoveBinding.decode(expected.encode()+byteArrayOf(1))}
        assertThrows(IOException::class.java) {PartitionSavedMoveBinding.decode(ByteArray(PARTITION_SAVED_MOVE_BYTES+1))}
        val unsupported=expected.encode().also {it[3]=3}
        assertThrows(IOException::class.java) {PartitionSavedMoveBinding.decode(unsupported)}
    }
    @Test fun boundedVersionTwoTreeSurvivesJournalRestartAndBindsTheActualSourceUid() {
        val allocation=folders.newFolder(); for(dir in listOf("bytes/0","index","metadata")) assertTrue(File(allocation,dir).mkdirs())
        File(allocation,"bytes/11.uid").writeText("")
        File(allocation,"bytes/0/0.0.1.v3.exo").writeBytes(byteArrayOf(1,2,3,4))
        File(allocation,"index/native-v1.db").writeText("index")
        File(allocation,"metadata/content-v1.db").writeText("metadata")
        val tree=PartitionMoveTree.capture(allocation,17,MigrationIoOwnership()) {}
        val expected=binding().copy(sourceTree=tree)
        assertEquals(expected,PartitionSavedMoveBinding.decode(expected.encode()))
        assertThrows(IOException::class.java) {expected.copy(source=expected.source.copy(uid=18)).encode()}
        val root=folders.newFolder(); var journal=PartitionSavedMoveJournal(root,true)
        val pending=worker {journal.open(); try {journal.begin(expected,true)} finally {journal.close()}}
        journal=PartitionSavedMoveJournal(root,false)
        worker {journal.open(); try {assertEquals(pending,journal.find(expected.key))} finally {journal.close()}}
    }
    @Test fun actualJournalRequiresConsentPreservesExactPendingAndCompleteTombstoneAcrossRestart() {
        val root=folders.newFolder(); val expected=binding(); var first=PartitionSavedMoveJournal(root,true)
        val pending=worker {
            first.open()
            assertThrows(IOException::class.java) {first.begin(expected,false)}
            assertTrue(first.page().isEmpty())
            first.begin(expected,true).also {first.close()}
        }
        first=PartitionSavedMoveJournal(root,false)
        worker {
            first.open()
            try {
                assertEquals(listOf(pending),first.page()); assertTrue(first.holdsName(expected.key))
                assertThrows(IOException::class.java) {first.begin(expected,true)}
                assertThrows(IOException::class.java) {first.complete(pending.copy(token="00000000-0000-0000-0000-000000000005"))}
                assertEquals(pending,first.find(expected.key))
                assertEquals(PartitionSavedMovePhase.Complete,first.complete(pending).phase)
            } finally {first.close()}
        }
        val last=PartitionSavedMoveJournal(root,false)
        worker {last.open(); try {
            assertTrue(last.holdsName(expected.key)); assertEquals(PartitionSavedMovePhase.Complete,last.find(expected.key)!!.phase)
            assertTrue(last.page(expected.key).isEmpty())
            assertThrows(IOException::class.java) {last.begin(expected,true)}
        } finally {last.close()}}
    }
    @Test fun unknownActualCursorCloseRetainsDatabaseAndRefusesRetryOrDatabaseClose() {
        var actual:SQLiteDatabase?=null; var child:java.io.Closeable?=null; var childAttempts=0; var dbAttempts=0
        val journal=PartitionSavedMoveJournal(folders.newFolder(),true,
            openDatabase={file,_ ->SQLiteDatabase.openOrCreateDatabase(file,null).also {actual=it}},
            closeDatabase={dbAttempts++; it.close()},
            closeIo={child=it; childAttempts++; throw IOException("Unknown actual cursor close")})
        worker {try {
            assertThrows(IOException::class.java) {journal.open()}
            assertThrows(IOException::class.java) {journal.close()}; assertThrows(IOException::class.java) {journal.open()}
            assertEquals(1,childAttempts); assertEquals(0,dbAttempts); assertTrue(actual!!.isOpen)
            assertTrue(journal.ownershipUncertain)
        } finally {child?.close(); actual?.endTransaction(); actual?.close()}}
    }
    @Test fun unknownActualDatabaseCloseIsAttemptedOnceAndRemainsOwned() {
        var actual:SQLiteDatabase?=null; var attempts=0
        val journal=PartitionSavedMoveJournal(folders.newFolder(),true,
            openDatabase={file,_ ->SQLiteDatabase.openOrCreateDatabase(file,null).also {actual=it}},
            closeDatabase={attempts++; throw IOException("Unknown actual database close")})
        worker {try {
            journal.open(); assertThrows(IOException::class.java) {journal.close()}
            assertThrows(IOException::class.java) {journal.close()}; assertThrows(IOException::class.java) {journal.page()}
            assertEquals(1,attempts); assertTrue(actual!!.isOpen)
        } finally {actual?.close()}}
    }
}
