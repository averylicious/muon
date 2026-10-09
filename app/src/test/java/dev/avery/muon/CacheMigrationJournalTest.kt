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
}
