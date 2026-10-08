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
class CachePartitionCatalogTest {
    @get:Rule val folders=TemporaryFolder()
    @Test fun exactDiskReservationsSurviveRestartWithBoundedOrderedPagesAndNoFullMap() {
        val root=folders.newFolder()
        val names=(0 until 1000).map { "saved/$it" }+listOf("", "a", "a\u0000b", "\uD800", "\uD801", "x".repeat(2048))
        val expected=names.sorted()
        var first:CachePartitionAllocation?=null
        CachePartitionCatalog(root).use { catalog ->
            names.reversed().forEach { catalog.reserve(it) }
            assertEquals(names.size.toLong(),catalog.count())
            first=catalog.find(names.first()); assertEquals(first,catalog.reserve(names.first()))
        }
        CachePartitionCatalog(root).use { catalog ->
            assertEquals(first,catalog.find(names.first()))
            assertNull(catalog.find("absent")); assertEquals(names.size.toLong(),catalog.count())
            val actual=ArrayList<String>(); var after:String?=null
            while (true) {
                val page=catalog.page(after); assertTrue(page.size<=PARTITION_LOCATOR_WINDOW)
                if (page.isEmpty()) break
                actual+=page.map { it.key }; after=page.last().key
            }
            assertEquals(expected,actual)
        }
    }
    @Test fun unknownExistingDirectoriesAndCrossKeyCollisionsAreNeverAdoptedOrOverwritten() {
        val root=folders.newFolder(); val uuid=UUID.randomUUID().toString()
        val originals=File(root,"resources/$uuid/original").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(7,8)) }
        CachePartitionCatalog(root) { uuid }.use { catalog ->
            assertThrows(IOException::class.java) { catalog.reserve("unknown") }; assertEquals(0L,catalog.count())
            assertArrayEquals(byteArrayOf(7,8),originals.readBytes())
        }
        val other=folders.newFolder(); val another=UUID.randomUUID().toString()
        CachePartitionCatalog(other) { another }.use { catalog ->
            val original=catalog.reserve("first")
            assertThrows(Exception::class.java) { catalog.reserve("second") }
            assertEquals(original,catalog.find("first")); assertNull(catalog.find("second")); assertEquals(1L,catalog.count())
        }
    }
    @Test fun oversizedKeysMalformedPathsAndUnknownSchemaRefuseWithoutDeletingData() {
        val root=folders.newFolder()
        CachePartitionCatalog(root) { "../not-a-partition" }.use { catalog ->
            assertThrows(IOException::class.java) { catalog.reserve("kept") }
            assertThrows(IOException::class.java) { catalog.reserve("x".repeat(MIGRATION_KEY_BYTES)) }
            assertEquals(0L,catalog.count())
        }
        val original=File(root,"original").apply { writeBytes(byteArrayOf(1,2,3)) }
        SQLiteDatabase.openOrCreateDatabase(File(root,"partition-locators-v1.db"),null).use { it.version=42 }
        assertThrows(IOException::class.java) { CachePartitionCatalog(root).close() }
        assertArrayEquals(byteArrayOf(1,2,3),original.readBytes())
        SQLiteDatabase.openOrCreateDatabase(File(root,"partition-locators-v1.db"),null).use { assertEquals(42,it.version) }
        val foreign=folders.newFolder()
        SQLiteDatabase.openOrCreateDatabase(File(foreign,"partition-locators-v1.db"),null).use {
            it.execSQL("CREATE TABLE sqliteXunrelated(value INTEGER)"); it.execSQL("INSERT INTO sqliteXunrelated VALUES(7)")
        }
        assertThrows(IOException::class.java) { CachePartitionCatalog(foreign).close() }
        val view=folders.newFolder()
        SQLiteDatabase.openOrCreateDatabase(File(view,"partition-locators-v1.db"),null).use {
            it.execSQL("CREATE VIEW foreign_view AS SELECT 7 AS value")
        }
        assertThrows(IOException::class.java) { CachePartitionCatalog(view).close() }
    }
    @Test fun matchingVersionCannotAdoptAnUnknownTableOrTriggerSchema() {
        val foreign=folders.newFolder()
        SQLiteDatabase.openOrCreateDatabase(File(foreign,"partition-locators-v1.db"),null).use {
            it.execSQL("CREATE TABLE partitions(key BLOB, directory TEXT)"); it.version=1
        }
        assertThrows(IOException::class.java) { CachePartitionCatalog(foreign).close() }
        val root=folders.newFolder()
        CachePartitionCatalog(root).use { it.reserve("kept") }
        SQLiteDatabase.openOrCreateDatabase(File(root,"partition-locators-v1.db"),null).use {
            it.execSQL("CREATE TRIGGER foreign_write AFTER INSERT ON partitions BEGIN DELETE FROM partitions; END")
        }
        assertThrows(IOException::class.java) { CachePartitionCatalog(root).close() }
        // Refusal never executes the unknown trigger or erases the retained routing row.
        SQLiteDatabase.openOrCreateDatabase(File(root,"partition-locators-v1.db"),null).use {
            it.rawQuery("SELECT COUNT(*) FROM partitions",null).use { rows -> assertTrue(rows.moveToFirst()); assertEquals(1L,rows.getLong(0)) }
        }
    }
    @Test fun symbolicLinksCannotChangeThePrivateRootOrAnExactReservedResourceIdentity() {
        val outside=folders.newFolder()
        val original=File(outside,"original").apply { writeBytes(byteArrayOf(3,4)) }
        val root=folders.newFolder()
        java.nio.file.Files.createSymbolicLink(File(root,"resources").toPath(),outside.toPath())
        CachePartitionCatalog(root).use { catalog ->
            assertThrows(IOException::class.java) { catalog.reserve("unknown") }; assertEquals(0L,catalog.count())
        }
        val next=folders.newFolder()
        CachePartitionCatalog(next).use { catalog ->
            val candidate=catalog.reserve("kept")
            val resources=File(next,"resources").apply { mkdirs() }
            val other=File(resources,UUID.randomUUID().toString()).apply { mkdirs() }
            java.nio.file.Files.createSymbolicLink(File(resources,candidate.directory).toPath(),other.toPath())
            assertThrows(IOException::class.java) { catalog.directory(candidate) }
        }
        assertArrayEquals(byteArrayOf(3,4),original.readBytes())
    }
    @Test fun corruptedDirectoryAndClosedLedgerCannotReturnAnInventedEmptyCatalog() {
        val root=folders.newFolder(); val catalog=CachePartitionCatalog(root)
        catalog.reserve("kept"); catalog.close()
        assertThrows(Exception::class.java) { catalog.find("kept") }
        SQLiteDatabase.openOrCreateDatabase(File(root,"partition-locators-v1.db"),null).use {
            it.execSQL("UPDATE partitions SET directory='../unknown'")
        }
        CachePartitionCatalog(root).use { reopened ->
            assertThrows(IOException::class.java) { reopened.find("kept") }
            assertThrows(IOException::class.java) { reopened.page() }
            assertEquals(1L,reopened.count())
        }
    }
}
