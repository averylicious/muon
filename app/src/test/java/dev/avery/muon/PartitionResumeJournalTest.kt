package dev.avery.muon

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.IOException
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
class PartitionResumeJournalTest {
    @get:Rule val folders=TemporaryFolder()
    private val names=listOf("partition-locators-v1.db","migration-journal-v1.db")
    private fun open(root:File,name:String):java.io.Closeable =
        if(name==names[0]) CachePartitionCatalog(root,create=false) else CacheMigrationJournal(root,create=false)
    @Test fun missingResumeJournalIsNeverRecreatedAndOriginalFilesStayIntact() {
        for(name in names) {
            val root=folders.newFolder(); val original=File(root,"original-audio").apply { writeBytes(byteArrayOf(9,2,8)) }
            assertThrows(IOException::class.java) { open(root,name).close() }
            assertFalse(File(root,name).exists()); assertArrayEquals(byteArrayOf(9,2,8),original.readBytes())
        }
    }
    @Test fun emptyExistingResumeJournalRefusesWithoutInitializingTablesOrVersion() {
        for(name in names) {
            val root=folders.newFolder(); val file=File(root,name)
            SQLiteDatabase.openOrCreateDatabase(file,null).use { assertEquals(0,it.version) }
            assertThrows(IOException::class.java) { open(root,name).close() }
            SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
                assertEquals(0,db.version)
                db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name IN ('partitions','migrations')",null).use {
                    assertTrue(it.moveToFirst()); assertEquals(0,it.getInt(0))
                }
            }
        }
    }
    @Test fun lostVersionCannotSilentlyRepairPreviouslyInitializedReservations() {
        for(name in names) {
            val root=folders.newFolder()
            val allocation=CachePartitionCatalog(root).use { it.reserve("original/key") }
            CacheMigrationJournal(root).use { it.begin(allocation,123,null) }
            SQLiteDatabase.openDatabase(File(root,name).path,null,SQLiteDatabase.OPEN_READWRITE).use { it.version=0 }
            assertThrows(IOException::class.java) { open(root,name).close() }
            SQLiteDatabase.openDatabase(File(root,name).path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
                assertEquals(0,db.version)
                db.rawQuery("SELECT COUNT(*) FROM ${if(name==names[0]) "partitions" else "migrations"}",null).use {
                    assertTrue(it.moveToFirst()); assertEquals(1,it.getInt(0))
                }
            }
        }
    }
    @Test fun validStrictResumePreservesExactReservationAndPendingPhaseWithoutPublication() {
        val root=folders.newFolder()
        val allocation=CachePartitionCatalog(root).use { it.reserve("original/key") }
        val ticket=CacheMigrationJournal(root).use { it.begin(allocation,123,null) }
        CachePartitionCatalog(root,create=false).use { assertEquals(allocation,it.find("original/key")) }
        CacheMigrationJournal(root,create=false).use {
            assertEquals(ticket,it.find("original/key")?.ticket); assertEquals(MigrationPhase.Copying,it.find("original/key")?.phase)
            assertNull(it.ready("original/key")); assertThrows(IOException::class.java) { it.publish(ticket) }
        }
    }
    @Test fun foreignLargeSchemaAndSymlinkResumeNeverBecomeOwnedJournals() {
        for(name in names) {
            val root=folders.newFolder(); val file=File(root,name)
            SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
                db.execSQL("CREATE VIEW foreign_view AS SELECT '${"z".repeat(4096)}' AS value"); db.version=1
            }
            assertThrows(IOException::class.java) { open(root,name).close() }
            val moved=File(root,"preserved.db"); assertTrue(file.renameTo(moved))
            java.nio.file.Files.createSymbolicLink(file.toPath(),moved.toPath())
            assertThrows(IOException::class.java) { open(root,name).close() }; assertTrue(java.nio.file.Files.isSymbolicLink(file.toPath()))
            SQLiteDatabase.openDatabase(moved.path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT length(value) FROM foreign_view",null).use { assertTrue(it.moveToFirst()); assertEquals(4096,it.getInt(0)) }
            }
        }
    }
}
