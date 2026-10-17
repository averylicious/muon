package dev.avery.muon

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CacheMigrationJournalTest {
    @get:Rule val folders=TemporaryFolder()
    private fun allocation(key:String)=CachePartitionAllocation(key,UUID.randomUUID().toString())

    @Test fun exactBoundedPagesRestartWithoutPromotingPendingOrPersistedVerifiedReceipts() {
        val root=folders.newFolder()
        val names=(0 until 300).map { "saved/$it" }+listOf("", "a\u0000b", "\uD800", "\uD801")
        lateinit var pending:MigrationTicket
        lateinit var verified:MigrationTicket
        lateinit var ready:MigrationRecord
        CacheMigrationJournal(root).use { journal ->
            names.reversed().forEach { journal.begin(allocation(it),7,null) }
            pending=requireNotNull(journal.find(names.first())).ticket
            verified=requireNotNull(journal.find(names[1])).ticket
            journal.verified(verified,8,MigrationCopyEvidence(17,1))
            val ticket=requireNotNull(journal.find(names[2])).ticket
            journal.verified(ticket,9,MigrationCopyEvidence(0,0)); ready=journal.publish(ticket)
            assertEquals(MigrationPhase.Ready,ready.phase)
        }
        CacheMigrationJournal(root).use { journal ->
            assertNull(journal.ready(pending.allocation.key)); assertNull(journal.ready(verified.allocation.key))
            assertEquals(ready,journal.ready(ready.ticket.allocation.key))
            assertThrows(IOException::class.java) { journal.publish(verified) }
            assertThrows(IOException::class.java) { journal.verified(verified,8,MigrationCopyEvidence(17,1)) }
            val actual=ArrayList<String>(); var after:String?=null
            while(true) {
                val page=journal.page(after); assertTrue(page.size<=16)
                if(page.isEmpty()) break
                actual+=page.map { it.ticket.allocation.key }; after=page.last().ticket.allocation.key
            }
            assertEquals(names.sorted(),actual)
            val fresh=journal.begin(allocation(verified.allocation.key),7,verified.token)
            assertNotEquals(verified.token,fresh.token)
            assertThrows(IOException::class.java) { journal.publish(verified) }
        }
    }
    @Test fun staleClaimsCrossResourceCollisionsAndReadyReplacementRefuseWithoutErasingRows() {
        val root=folders.newFolder()
        CacheMigrationJournal(root).use { journal ->
            val old=journal.begin(allocation("kept"),5,null)
            assertThrows(IOException::class.java) { journal.begin(allocation("kept"),5,null) }
            val next=journal.begin(allocation("kept"),5,old.token)
            assertThrows(IOException::class.java) { journal.verified(old,6,MigrationCopyEvidence(4,1)) }
            journal.uncertain(old); assertEquals(next,journal.find("kept")?.ticket)
            assertThrows(Exception::class.java) { journal.begin(CachePartitionAllocation("other",next.allocation.directory),5,null) }
            assertNull(journal.find("other")); assertEquals(next,journal.find("kept")?.ticket)
            journal.verified(next,6,MigrationCopyEvidence(4,1)); val ready=journal.publish(next)
            assertThrows(IOException::class.java) { journal.begin(allocation("kept"),5,next.token) }
            journal.uncertain(next); assertEquals(ready,journal.ready("kept"))
        }
    }
    @Test fun unknownSchemaAndCorruptRowsRefuseWithoutInventingAbsenceOrDeletingOriginals() {
        val root=folders.newFolder(); val original=File(root,"original").apply { writeBytes(byteArrayOf(1,4,9)) }
        CacheMigrationJournal(root).use { it.begin(allocation("kept"),4,null) }
        val file=File(root,"migration-journal-v1.db")
        SQLiteDatabase.openOrCreateDatabase(file,null).use { it.execSQL("UPDATE migrations SET phase=4294967296") }
        CacheMigrationJournal(root).use { journal ->
            assertThrows(IOException::class.java) { journal.find("kept") }
            assertThrows(IOException::class.java) { journal.page() }
        }
        SQLiteDatabase.openOrCreateDatabase(file,null).use { it.execSQL("UPDATE migrations SET phase=0,source_uid='unknown'") }
        CacheMigrationJournal(root).use { journal -> assertThrows(IOException::class.java) { journal.find("kept") } }
        SQLiteDatabase.openOrCreateDatabase(file,null).use {
            it.execSQL("CREATE TRIGGER unknown_write AFTER INSERT ON migrations BEGIN DELETE FROM migrations; END")
        }
        assertThrows(IOException::class.java) { CacheMigrationJournal(root).close() }
        assertArrayEquals(byteArrayOf(1,4,9),original.readBytes())
        val unknown=folders.newFolder()
        SQLiteDatabase.openOrCreateDatabase(File(unknown,"migration-journal-v1.db"),null).use { it.execSQL("CREATE VIEW foreign_view AS SELECT 7 AS value") }
        assertThrows(IOException::class.java) { CacheMigrationJournal(unknown).close() }
        val closed=CacheMigrationJournal(folders.newFolder()); closed.close()
        assertThrows(Exception::class.java) { closed.find("kept") }
    }
    @Test fun freshReservationRetainsPreviousUncertainDirectoryAndRejectsCollision() {
        val root=folders.newFolder(); val identities=listOf(UUID.randomUUID().toString(),UUID.randomUUID().toString())
        var index=0
        CachePartitionCatalog(root) { identities[minOf(index++,1)] }.use { catalog ->
            val old=catalog.reserve("kept")
            val original=File(catalog.directory(old),"uncertain").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(7)) }
            val fresh=catalog.reserveFresh("kept")
            assertNotEquals(old.directory,fresh.directory); assertEquals(fresh,catalog.find("kept"))
            assertArrayEquals(byteArrayOf(7),original.readBytes()); assertFalse(catalog.directory(fresh).exists())
            assertThrows(IOException::class.java) { catalog.reserveFresh("kept") }
            assertEquals(fresh,catalog.find("kept")); assertArrayEquals(byteArrayOf(7),original.readBytes())
        }
    }
    @Test fun oversizedUuidOrPageKeyRefusesBeforeCursorLoadingWithoutErasingItsTicket() {
        for(field in listOf("directory","token","key")) {
            val root=folders.newFolder(); CacheMigrationJournal(root).use { it.begin(allocation("kept"),1,null) }
            val file=File(root,"migration-journal-v1.db")
            SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
                if(field=="key") db.execSQL("UPDATE migrations SET key=zeroblob(3145728)")
                else db.execSQL("UPDATE migrations SET $field=?",arrayOf("\u0000"+"x".repeat(3145728)))
            }
            CacheMigrationJournal(root).use { journal ->
                if(field!="key") assertThrows(IOException::class.java) { journal.find("kept") }
                assertThrows(IOException::class.java) { journal.page() }
            }
            SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
                db.rawQuery("SELECT length(CAST($field AS BLOB)) FROM migrations",null).use {
                    assertTrue(it.moveToFirst()); assertEquals(if(field=="key") 3145728 else 3145729,it.getInt(0))
                }
            }
        }
    }
    @Test fun malformedNumericReceiptFieldsCannotBeProjectedAsLegitimateMissingValues() {
        for(field in listOf("source_uid","phase","target_uid","bytes","ranges")) {
            val root=folders.newFolder()
            CacheMigrationJournal(root).use { journal ->
                val ticket=journal.begin(allocation("kept"),1,null)
                journal.verified(ticket,2,MigrationCopyEvidence(1,1)); journal.publish(ticket)
            }
            val file=File(root,"migration-journal-v1.db")
            SQLiteDatabase.openOrCreateDatabase(file,null).use { it.execSQL("UPDATE migrations SET $field=?",arrayOf("\u0000"+"x".repeat(3145728))) }
            CacheMigrationJournal(root).use { journal ->
                assertThrows(IOException::class.java) { journal.find("kept") }
                assertThrows(IOException::class.java) { journal.ready("kept") }
                assertThrows(IOException::class.java) { journal.page() }
            }
            SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
                db.rawQuery("SELECT typeof($field),length(CAST($field AS BLOB)) FROM migrations",null).use {
                    assertTrue(it.moveToFirst()); assertEquals("text",it.getString(0)); assertEquals(3145729,it.getInt(1))
                }
            }
        }
    }

    @Test fun pendingRecoveryFiltersReadyBeforeHydrationAndPagesOnlyTwoExactRecords() {
        val root=folders.newFolder()
        CacheMigrationJournal(root).use {journal ->
            for(key in listOf("a-ready","b-pending","c-ready","d-verified","e-uncertain")) {
                val ticket=journal.begin(allocation(key),7,null)
                when {
                    key.endsWith("ready") -> {journal.verified(ticket,8,MigrationCopyEvidence(4,1));journal.publish(ticket)}
                    key.endsWith("verified") -> journal.verified(ticket,8,MigrationCopyEvidence(4,1))
                    key.endsWith("uncertain") -> journal.uncertain(ticket)
                }
            }
            val first=journal.pendingPage();assertEquals(listOf("b-pending","d-verified"),first.entries.map {it.ticket.allocation.key})
            assertTrue(first.hasMore);assertEquals("d-verified",first.after)
            val last=journal.pendingPage(first.after);assertEquals(listOf("e-uncertain"),last.entries.map {it.ticket.allocation.key})
            assertFalse(last.hasMore);assertTrue(journal.pendingPage(last.after).entries.isEmpty())
            assertNotNull(journal.ready("a-ready"));assertNotNull(journal.ready("c-ready"))
        }
    }
    @Test fun pendingRecoveryDoesNotHideMalformedPhaseOrHydrateItsOversizedPayload() {
        val root=folders.newFolder();CacheMigrationJournal(root).use {it.begin(allocation("kept"),7,null)}
        SQLiteDatabase.openOrCreateDatabase(File(root,"migration-journal-v1.db"),null).use {
            it.execSQL("UPDATE migrations SET phase=zeroblob(8388608)")
        }
        CacheMigrationJournal(root).use {journal ->
            assertThrows(IOException::class.java) {journal.pendingPage()}
        }
        SQLiteDatabase.openOrCreateDatabase(File(root,"migration-journal-v1.db"),null).use {db ->
            db.rawQuery("SELECT typeof(phase),length(phase) FROM migrations",null).use {
                assertTrue(it.moveToFirst());assertEquals("blob",it.getString(0));assertEquals(8388608,it.getInt(1))
            }
        }
    }
    @Test fun pendingRecoveryUnknownActualCursorCloseRetainsConnectionAndRefusesEveryFurtherOperation() {
        val root=folders.newFolder();var fail=false;var child:java.io.Closeable?=null;var attempts=0
        val io=MigrationIoOwnership(closeHandle={if(fail) {child=it;attempts++;throw IOException("Unknown actual cursor close")} else it.close()})
        val journal=CacheMigrationJournal(root,recoveryIo=io)
        val ticket=journal.begin(allocation("kept"),7,null)
        val db=CacheMigrationJournal::class.java.getDeclaredField("database").apply {isAccessible=true}.get(journal) as SQLiteDatabase
        fail=true
        try {
            val failure=assertThrows(PartitionOwnershipUncertain::class.java) {journal.pendingPage()}
            assertSame(journal,failure.owner)
            assertFalse(io.quiescent);assertTrue(db.isOpen);assertEquals(1,attempts)
            assertThrows(IOException::class.java) {journal.find("kept")}
            assertThrows(IOException::class.java) {journal.page()}
            assertThrows(IOException::class.java) {journal.pendingPage()}
            assertThrows(IOException::class.java) {journal.begin(allocation("other"),7,null)}
            assertThrows(IOException::class.java) {journal.verified(ticket,8,MigrationCopyEvidence(4,1))}
            assertThrows(IOException::class.java) {journal.publish(ticket)}
            assertThrows(IOException::class.java) {journal.uncertain(ticket)}
            assertThrows(IOException::class.java) {journal.close()};assertEquals(1,attempts)
            db.rawQuery("SELECT COUNT(*) FROM migrations",null).use {assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0))}
        } finally {
            // Test callback never attempted raw close. Close only these known fixture handles;
            // production has no reset or retry-close path over uncertain ownership.
            child?.close();db.close()
        }
    }

    private fun actualDatabase(journal:CacheMigrationJournal)=CacheMigrationJournal::class.java
        .getDeclaredField("database").apply {isAccessible=true}.get(journal) as SQLiteDatabase
    @Test fun productionShapedStartupRetainsJournalBeforeSchemaCursorOpeningAndNeverRetriesFailure() {
        val root=folders.newFolder();var child:java.io.Closeable?=null;var closes=0
        val journal=CacheMigrationJournal(root,autoOpen=false,recoveryIo=MigrationIoOwnership(closeHandle={
            child=it;closes++;throw IOException("Unknown schema cursor close")
        }))
        assertFalse(File(root,"migration-journal-v1.db").exists())
        val startup=StorageStartup<CacheMigrationJournal>()
        try {
            val failure=assertThrows(PartitionOwnershipUncertain::class.java) {startup.open {opening ->
                opening.own {journal}.also {it.open()}
            }}
            assertSame(journal,failure.owner);assertEquals(1,startup.retained);assertTrue(journal.ownershipUncertain)
            val handles=StorageStartup::class.java.getDeclaredField("handles").apply {isAccessible=true}.get(startup) as Array<*>
            assertSame(journal,handles[0]);assertTrue(actualDatabase(journal).isOpen);assertTrue(actualDatabase(journal).inTransaction())
            assertThrows(IOException::class.java) {startup.open {throw AssertionError("Failed startup retried")}}
            assertThrows(IOException::class.java) {journal.open()};assertThrows(IOException::class.java) {journal.close()};assertEquals(1,closes)
        } finally {child?.close();actualDatabase(journal).endTransaction();actualDatabase(journal).close()}
    }
    @Test fun actualStatementCloseFailureKeepsTransactionAndConnectionWithoutCommitRollbackOrRetry() {
        val root=folders.newFolder();var fail=false;var child:java.io.Closeable?=null;var closes=0
        val journal=CacheMigrationJournal(root,recoveryIo=MigrationIoOwnership(closeHandle={
            if(fail && it is android.database.sqlite.SQLiteStatement) {child=it;closes++;throw IOException("Unknown insert statement close")}
            it.close()
        }))
        val db=actualDatabase(journal);fail=true
        try {
            val failure=assertThrows(PartitionOwnershipUncertain::class.java) {journal.begin(allocation("kept"),7,null)}
            assertSame(journal,failure.owner);assertTrue(db.isOpen);assertTrue(db.inTransaction());assertTrue(journal.ownershipUncertain)
            db.rawQuery("SELECT COUNT(*) FROM migrations",null).use {assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0))}
            assertThrows(IOException::class.java) {journal.find("kept")};assertThrows(IOException::class.java) {journal.close()};assertEquals(1,closes)
        } finally {child?.close();db.endTransaction();db.close()}
        SQLiteDatabase.openOrCreateDatabase(File(root,"migration-journal-v1.db"),null).use {dbAfter ->
            dbAfter.rawQuery("SELECT COUNT(*) FROM migrations",null).use {assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
        }
    }
    @Test fun transactionEndThrowAfterActualCommitIsUncertainAndDoesNotReturnASuccessReceipt() {
        val root=folders.newFolder();var fail=false;var ends=0
        val journal=CacheMigrationJournal(root,finishTransaction={db ->db.endTransaction();ends++
            if(fail) throw IOException("Actual commit returned no trustworthy acknowledgment")})
        val db=actualDatabase(journal);fail=true
        try {
            val failure=assertThrows(PartitionOwnershipUncertain::class.java) {journal.begin(allocation("kept"),7,null)}
            assertSame(journal,failure.owner);assertFalse(db.inTransaction());assertTrue(db.isOpen);assertTrue(journal.ownershipUncertain)
            db.rawQuery("SELECT phase FROM migrations",null).use {assertTrue(it.moveToFirst());assertEquals(MigrationPhase.Copying.ordinal,it.getInt(0))}
            val count=ends;assertThrows(IOException::class.java) {journal.begin(allocation("other"),7,null)}
            assertThrows(IOException::class.java) {journal.close()};assertEquals(count,ends)
        } finally {db.close()}
    }
    @Test fun actualDatabaseCloseFailureRetainsExactOwnerAndNeverRetriesClose() {
        val root=folders.newFolder();var closes=0
        val journal=CacheMigrationJournal(root,closeDatabase={closes++;throw IOException("Unknown actual database close")})
        val db=actualDatabase(journal);journal.begin(allocation("kept"),7,null)
        try {
            val failure=assertThrows(PartitionOwnershipUncertain::class.java) {journal.close()}
            assertSame(journal,failure.owner);assertTrue(db.isOpen);assertTrue(journal.ownershipUncertain)
            assertThrows(IOException::class.java) {journal.close()};assertThrows(IOException::class.java) {journal.page()};assertEquals(1,closes)
        } finally {db.close()}
    }

}
