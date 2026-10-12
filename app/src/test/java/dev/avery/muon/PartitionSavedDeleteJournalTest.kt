package dev.avery.muon

import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
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
class PartitionSavedDeleteJournalTest {
    @get:Rule val folders=TemporaryFolder()
    private fun <T> worker(work:()->T):T {
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {return try {executor.submit<T> {work()}.get(10,TimeUnit.SECONDS)}
            catch(error:java.util.concurrent.ExecutionException) {throw requireNotNull(error.cause)}}
        finally {executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS))}
    }
    private fun binding(key:String="saved/delete"):PartitionSavedDeleteBinding {
        val allocation=folders.newFolder()
        for(dir in listOf("bytes/0","index","metadata")) assertTrue(File(allocation,dir).mkdirs())
        File(allocation,"bytes/11.uid").writeText("")
        // The owned audio is only partial; Delete must not manufacture Move/full-copy authority.
        File(allocation,"bytes/0/0.2.1.v3.exo").writeBytes(byteArrayOf(3,4))
        File(allocation,"index/native-v1.db").writeText("native index")
        File(allocation,"metadata/content-v1.db").writeText("metadata")
        val tree=PartitionMoveTree.capture(allocation,17,MigrationIoOwnership()) {}
        return PartitionSavedDeleteBinding("phone","actual-source-root",PartitionSaveTicket(
            CachePartitionAllocation(key,"00000000-0000-0000-0000-000000000001"),
            "00000000-0000-0000-0000-000000000002"),17,"a".repeat(64),4,tree,SavedMoveCoverIdentity(0,null))
    }
    @Test fun bindingRoundTripsPartialOwnedAudioButRejectsUidIdentityVersionAndTrailingPayload() {
        val value=binding();assertEquals(value,PartitionSavedDeleteBinding.decode(value.encode()))
        assertThrows(IOException::class.java) {value.tree.requireAudioLength(4)}
        assertThrows(IOException::class.java) {value.copy(uid=18).encode()}
        assertThrows(IOException::class.java) {value.copy(completionIdentity="bad").encode()}
        assertThrows(IOException::class.java) {value.copy(ticket=value.ticket.copy(allocation=value.ticket.allocation.copy(key="old"))).encode()}
        assertThrows(IOException::class.java) {value.copy(volume="").encode()}
        assertThrows(IOException::class.java) {PartitionSavedDeleteBinding.decode(value.encode()+byteArrayOf(0))}
        assertThrows(IOException::class.java) {PartitionSavedDeleteBinding.decode(value.encode().also {it[3]=2})}
        assertThrows(IOException::class.java) {PartitionSavedDeleteBinding.decode(ByteArray(PARTITION_SAVED_DELETE_BYTES+1))}
    }
    @Test fun exactConsentIntentAndPermanentTombstoneSurviveActualJournalRestart() {
        val root=folders.newFolder();val value=binding();var journal=PartitionSavedDeleteJournal(root,true)
        assertFalse(File(root,"partition-deletes-v1.db").exists())
        val pending=worker {journal.open();try {
            assertThrows(IOException::class.java) {journal.begin(value,false)}
            assertTrue(journal.page().isEmpty());journal.begin(value,true)
        } finally {journal.close()}}
        journal=PartitionSavedDeleteJournal(root,false)
        worker {journal.open();try {
            assertEquals(pending,journal.find(value.key));assertTrue(journal.holdsName(value.key))
            assertThrows(IOException::class.java) {journal.begin(value,true)}
            assertThrows(IOException::class.java) {journal.complete(pending.copy(token="00000000-0000-0000-0000-000000000009"))}
            assertEquals(PartitionSavedDeletePhase.Complete,journal.complete(pending).phase)
        } finally {journal.close()}}
        journal=PartitionSavedDeleteJournal(root,false)
        worker {journal.open();try {
            assertEquals(PartitionSavedDeletePhase.Complete,journal.find(value.key)!!.phase)
            assertTrue(journal.holdsName(value.key));assertTrue(journal.pendingPage().isEmpty())
            assertThrows(IOException::class.java) {journal.begin(value,true)}
        } finally {journal.close()}}
    }
    @Test fun actualPendingPagesRemainTwoRowsAndSkipCompletedEntries() {
        val journal=PartitionSavedDeleteJournal(folders.newFolder(),true)
        worker {journal.open();try {
            for(key in listOf("saved/a","saved/b","saved/c","saved/d")) {
                val pending=journal.begin(binding(key),true)
                if(key=="saved/a") journal.complete(pending)
            }
            assertEquals(listOf("saved/b","saved/c"),journal.pendingPage().map {it.binding.key})
            assertEquals(listOf("saved/d"),journal.pendingPage("saved/c").map {it.binding.key})
            assertTrue(journal.pendingPage("saved/d").isEmpty())
        } finally {journal.close()}}
    }
    @Test fun oversizedActualSqlitePayloadIsProjectedAwayBeforeBindingHydration() {
        val root=folders.newFolder();val journal=PartitionSavedDeleteJournal(root,true)
        worker {journal.open();journal.close()}
        SQLiteDatabase.openDatabase(File(root,"partition-deletes-v1.db").path,null,SQLiteDatabase.OPEN_READWRITE).use {db ->
            db.execSQL("INSERT INTO saved_deletes VALUES(?,?,?,?)",arrayOf(savedCatalogSortKey("saved/large"),
                "00000000-0000-0000-0000-000000000003",0,ByteArray(PARTITION_SAVED_DELETE_BYTES+1)))
        }
        val resumed=PartitionSavedDeleteJournal(root,false)
        worker {resumed.open();try {
            assertThrows(IOException::class.java) {resumed.find("saved/large")}
            assertThrows(IOException::class.java) {resumed.pendingPage()}
            assertTrue(resumed.holdsName("saved/large"))
        } finally {resumed.close()}}
    }
    @Test fun unknownActualCursorClosePinsConnectionAndRefusesAnyRetryOrDatabaseClose() {
        var actual:SQLiteDatabase?=null;var child:Closeable?=null;var fail=false;var attempts=0;var databaseCloses=0
        val journal=PartitionSavedDeleteJournal(folders.newFolder(),true,
            openDatabase={file,_ ->SQLiteDatabase.openOrCreateDatabase(file,null).also {actual=it}},
            closeDatabase={databaseCloses++;it.close()},closeIo={if(fail) {child=it;attempts++;throw IOException("Unknown actual close")} else it.close()})
        worker {journal.open();fail=true
            assertThrows(IOException::class.java) {journal.pendingPage()}
            assertTrue(journal.ownershipUncertain);assertTrue(actual!!.isOpen);assertEquals(1,attempts)
            assertThrows(IOException::class.java) {journal.find("saved/one")}
            assertThrows(IOException::class.java) {journal.close()};assertEquals(0,databaseCloses)
            // Fixture-only cleanup: injected close did not touch the actual child. No production reset.
            child!!.close();actual!!.close()
        }
    }
    @Test fun resumeRefusesMissingAndForeignSchemaWithoutCreatingOrRepairingAnything() {
        val root=folders.newFolder();val missing=PartitionSavedDeleteJournal(root,false)
        worker {assertThrows(IOException::class.java) {missing.open()};missing.close()}
        val file=File(root,"partition-deletes-v1.db");assertFalse(file.exists())
        SQLiteDatabase.openOrCreateDatabase(file,null).use {it.execSQL("CREATE TABLE foreign_data(x TEXT)");it.version=1}
        val foreign=PartitionSavedDeleteJournal(root,false)
        worker {assertThrows(IOException::class.java) {foreign.open()};foreign.close()}
        SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY).use {db ->
            assertTrue(db.rawQuery("SELECT name FROM sqlite_master WHERE name='foreign_data'",null).use {it.moveToFirst()})
        }
    }
}
