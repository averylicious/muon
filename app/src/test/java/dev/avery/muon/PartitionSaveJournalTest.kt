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
class PartitionSaveJournalTest {
    @get:Rule val folders=TemporaryFolder()
    private fun allocation(key:String)=CachePartitionAllocation(key,UUID.randomUUID().toString())
    private fun sql(root:File,block:(SQLiteDatabase)->Unit)=SQLiteDatabase.openDatabase(File(root,"new-save-journal-v1.db").path,null,SQLiteDatabase.OPEN_READWRITE).use(block)
    @Test fun exactPagedRecordsSurviveRestartWithoutPromotingOpeningOrOpen() {
        val root=folders.newFolder(); val names=(0 until 80).map { "saved/$it" }+listOf("","a\u0000b","\uD800","\uD801")
        lateinit var opening:PartitionSaveTicket; lateinit var open:PartitionSaveTicket; lateinit var closed:PartitionSaveRecord
        PartitionSaveJournal(root,create=true).use { journal ->
            names.reversed().forEach { journal.begin(allocation(it)) }
            opening=requireNotNull(journal.find(names[0])).ticket; journal.opening(opening)
            open=requireNotNull(journal.find(names[1])).ticket; journal.opening(open); journal.opened(open,9)
            val ticket=requireNotNull(journal.find(names[2])).ticket; journal.opening(ticket); journal.opened(ticket,10); journal.closed(ticket,10)
            closed=requireNotNull(journal.find(names[2]))
        }
        PartitionSaveJournal(root).use { journal ->
            assertEquals(PartitionSavePhase.Opening,journal.find(opening.allocation.key)?.phase)
            assertEquals(PartitionSavePhase.Open,journal.find(open.allocation.key)?.phase)
            assertThrows(IOException::class.java) { journal.opening(opening) }; assertThrows(IOException::class.java) { journal.opening(open) }
            assertEquals(closed,journal.find(closed.ticket.allocation.key)); assertEquals(closed,journal.opening(closed.ticket))
            assertThrows(IOException::class.java) { journal.opened(closed.ticket,11) }
            journal.opened(closed.ticket,10); journal.closed(closed.ticket,10)
            val actual=ArrayList<String>(); var after:String?=null
            while(true) { val page=journal.page(after); assertTrue(page.size<=16); if(page.isEmpty()) break
                actual+=page.map { it.ticket.allocation.key }; after=page.last().ticket.allocation.key }
            assertEquals(names.sorted(),actual)
        }
    }
    @Test fun staleReplacementAndCrossResourceCollisionCannotEraseOwnedRows() {
        val root=folders.newFolder()
        PartitionSaveJournal(root,create=true).use { journal ->
            val first=journal.begin(allocation("kept"))
            assertThrows(IOException::class.java) { journal.begin(allocation("kept"),first.token) }
            journal.uncertain(first); val next=journal.begin(allocation("kept"),first.token)
            assertThrows(IOException::class.java) { journal.opening(first) }
            assertThrows(IOException::class.java) { journal.uncertain(first) }
            assertThrows(Exception::class.java) { journal.begin(CachePartitionAllocation("other",next.allocation.directory)) }
            assertNull(journal.find("other")); assertEquals(next,journal.find("kept")?.ticket)
            journal.opening(next); journal.opened(next,7); journal.closed(next,7)
            assertThrows(IOException::class.java) { journal.begin(allocation("kept"),next.token) }
            assertEquals(PartitionSavePhase.Closed,journal.find("kept")?.phase)
        }
    }
    @Test fun missingJournalIsNotRecreatedAndFreshCreationNeverOverwritesExistingStore() {
        val root=folders.newFolder(); val kept=File(root,"original").apply { writeBytes(byteArrayOf(3,1,4)) }
        assertThrows(IOException::class.java) { PartitionSaveJournal(root).close() }; assertFalse(File(root,"new-save-journal-v1.db").exists())
        PartitionSaveJournal(root,create=true).use { it.begin(allocation("kept")) }
        assertThrows(IOException::class.java) { PartitionSaveJournal(root,create=true).close() }
        PartitionSaveJournal(root).use { assertNotNull(it.find("kept")) }
        assertArrayEquals(byteArrayOf(3,1,4),kept.readBytes())
    }
    @Test fun oversizedWrongTypedAndNulSuffixedPayloadsRefuseBeforeCursorProjection() {
        for(column in listOf("key","directory","token","uid")) {
            val root=folders.newFolder(); PartitionSaveJournal(root,create=true).use { it.begin(allocation("kept")) }
            sql(root) { db ->
                if(column=="key") db.execSQL("UPDATE saves SET key=zeroblob(3145728)")
                else db.execSQL("UPDATE saves SET $column=?",arrayOf("x\u0000"+"z".repeat(3*1024*1024)))
            }
            PartitionSaveJournal(root).use { journal ->
                assertThrows(IOException::class.java) { journal.page() }
                if(column!="key") assertThrows(IOException::class.java) { journal.find("kept") }
            }
            sql(root) { db -> db.rawQuery("SELECT COUNT(*) FROM saves",null).use { assertTrue(it.moveToFirst()); assertEquals(1,it.getInt(0)) } }
        }
    }
    @Test fun unknownSchemaAndInvalidStatesAreNotRepairedOrTruncated() {
        val root=folders.newFolder(); PartitionSaveJournal(root,create=true).use { it.begin(allocation("kept")) }
        sql(root) { it.execSQL("UPDATE saves SET phase=4294967296") }
        PartitionSaveJournal(root).use { assertThrows(IOException::class.java) { it.find("kept") } }
        sql(root) { it.execSQL("UPDATE saves SET phase=3,uid=NULL") }
        PartitionSaveJournal(root).use { assertThrows(IOException::class.java) { it.page() } }
        sql(root) { it.execSQL("CREATE TRIGGER foreign_write AFTER INSERT ON saves BEGIN DELETE FROM saves; END") }
        assertThrows(IOException::class.java) { PartitionSaveJournal(root).close() }
        sql(root) { db -> db.rawQuery("SELECT COUNT(*) FROM saves",null).use { assertTrue(it.moveToFirst()); assertEquals(1,it.getInt(0)) } }
    }
    @Test fun invalidIdentityAndUidTransitionsKeepThePreviousReceipt() {
        val root=folders.newFolder()
        PartitionSaveJournal(root,create=true).use { journal ->
            assertThrows(IOException::class.java) { journal.begin(allocation("z".repeat(MIGRATION_KEY_BYTES))) }
            assertThrows(IOException::class.java) { journal.begin(CachePartitionAllocation("kept","bad")) }
            val ticket=journal.begin(allocation("kept")); journal.opening(ticket)
            assertThrows(IOException::class.java) { journal.opened(ticket,-1) }
            assertEquals(PartitionSavePhase.Opening,journal.find("kept")?.phase)
            journal.opened(ticket,17)
            assertThrows(IOException::class.java) { journal.closed(ticket,18) }; assertEquals(PartitionSavePhase.Open,journal.find("kept")?.phase)
            journal.closed(ticket,17); assertEquals(PartitionSavePhase.Closed,journal.find("kept")?.phase)
        }
    }
}
