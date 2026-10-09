@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.database.sqlite.SQLiteDatabase
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.database.VersionTable
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LegacyResourceProjectionTest {
    @get:Rule val folders=TemporaryFolder()
    private val database by lazy { StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()) }
    private val caches=mutableListOf<SimpleCache>()
    private val reads=mutableListOf<SQLiteDatabase>()
    private val payload=byteArrayOf(2,4,6,8)
    private fun cache(root:File=folders.newFolder())=SimpleCache(root,NoOpCacheEvictor(),database).also { caches+=it; it.checkInitialization() }
    private fun seed(cache:Cache,key:String,position:Long=0):File {
        val hole=requireNotNull(cache.startReadWrite(key,position,payload.size.toLong()))
        try { val file=cache.startFile(key,position,payload.size.toLong()); file.writeBytes(payload); cache.commitFile(file,payload.size.toLong()); return file }
        finally { cache.releaseHoleSpan(hole) }
    }
    private inner class Fixture(count:Int=1) {
        val root=folders.newFolder(); val source=cache(root); val uid=source.uid
        val table="ExoPlayerCacheIndex"+java.lang.Long.toHexString(uid)
        val original=seed(source,"selected")
        init {
            source.applyContentMetadataMutations("selected",ContentMetadataMutations().set("unknown_future",byteArrayOf(9,7)))
            for(i in 1 until count) seed(source,"other/$i")
            source.release()
        }
        fun db()=SQLiteDatabase.openDatabase(database.readableDatabase.path,null,SQLiteDatabase.OPEN_READONLY).also(reads::add)
        fun project(checkpoint:()->Unit={})=LegacyResourceProjection.read(root,uid,"selected",db(),checkpoint)
    }
    @After fun close() { try { reads.asReversed().forEach { it.close() }; caches.asReversed().forEach { it.release() } } finally { database.close() } }
    @Test fun nativeHundredsOfResourcesProjectOnlySelectedRecordWithoutReopeningSource() {
        val f=Fixture(200); val before=f.original.readBytes(); val selected=f.project()
        assertEquals(setOf("selected"),selected.keys); assertEquals(1,selected.getCachedSpans("selected").size)
        assertEquals(4L,selected.cacheSpace); assertFalse(SimpleCache.isCacheFolderLocked(f.root))
        assertArrayEquals(byteArrayOf(9,7),selected.getContentMetadata("selected").get("unknown_future",null as ByteArray?))
        assertThrows(IOException::class.java) { selected.getContentMetadata("other/1") }
        assertThrows(IOException::class.java) { selected.removeResource("selected") }
        assertThrows(IOException::class.java) { selected.release() }
        assertArrayEquals(before,f.original.readBytes())
    }
    @Test fun supportedMigrationCopiesPartialBytesBeyondDeclaredLengthAndUnknownMetadata() {
        val f=Fixture(); val root=folders.newFolder()
        val source=cache(root) // Independent fixture for two ranges; original source stays closed.
        seed(source,"partial",0); seed(source,"partial",16)
        source.applyContentMetadataMutations("partial",ContentMetadataMutations.setContentLength(
            ContentMetadataMutations().set("unknown_future",byteArrayOf(9,7)),2))
        val uid=source.uid; source.release()
        val read=SQLiteDatabase.openDatabase(database.readableDatabase.path,null,SQLiteDatabase.OPEN_READONLY).also(reads::add)
        val projected=LegacyResourceProjection.read(root,uid,"partial",read,{})
        val target=cache()
        val evidence=CacheMigrationPreparation.copy(projected,target,"partial",{})
        assertEquals(MigrationCopyEvidence(8,2),evidence)
        assertEquals(listOf(MigrationRange(0,4),MigrationRange(16,4)),CacheMigrationPreparation.ranges(projected,"partial"))
        assertEquals(4L,projected.getCachedLength("partial",0,100)); assertEquals(-12L,projected.getCachedLength("partial",4,100))
        assertEquals(2L,projected.getCachedBytes("partial",3,14))
        assertThrows(IOException::class.java) { projected.startReadWriteNonBlocking("partial",4,4) }
        assertArrayEquals(byteArrayOf(9,7),target.getContentMetadata("partial").get("unknown_future",null as ByteArray?))
        assertArrayEquals(payload,f.original.readBytes()); assertFalse(SimpleCache.isCacheFolderLocked(root))
    }
    @Test fun oversizedPersistedMetadataRefusesBeforeCursorWindowAndDoesNotRepairRow() {
        val f=Fixture()
        database.writableDatabase.execSQL("UPDATE ${f.table} SET metadata=zeroblob(8388608) WHERE key='selected'")
        assertThrows(IOException::class.java) { f.project() }
        database.readableDatabase.rawQuery("SELECT length(metadata) FROM ${f.table} WHERE key='selected'",null).use {
            assertTrue(it.moveToFirst()); assertEquals(8388608L,it.getLong(0))
        }
        assertArrayEquals(payload,f.original.readBytes())
    }
    @Test fun unknownVersionAndForeignFilesRefuseWithoutNativeInitializationOrDeletion() {
        val f=Fixture(); val hex=java.lang.Long.toHexString(f.uid)
        VersionTable.setVersion(database.writableDatabase,VersionTable.FEATURE_CACHE_CONTENT_METADATA,hex,2)
        assertThrows(IOException::class.java) { f.project() }
        assertEquals(2,VersionTable.getVersion(database.readableDatabase,VersionTable.FEATURE_CACHE_CONTENT_METADATA,hex))
        VersionTable.setVersion(database.writableDatabase,VersionTable.FEATURE_CACHE_CONTENT_METADATA,hex,1)
        val foreign=File(f.root,"retained-unknown").apply { writeBytes(byteArrayOf(5,6)) }
        assertThrows(IOException::class.java) { f.project() }
        assertArrayEquals(byteArrayOf(5,6),foreign.readBytes()); assertArrayEquals(payload,f.original.readBytes())
        assertFalse(SimpleCache.isCacheFolderLocked(f.root))
    }
    @Test fun malformedFieldLengthsAndDuplicateKeysRefuseWithoutAllocationOrRepair() {
        val f=Fixture()
        val bytes=ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { it.writeInt(1); it.writeUTF("bad"); it.writeInt(Int.MAX_VALUE) } }.toByteArray()
        database.writableDatabase.execSQL("UPDATE ${f.table} SET metadata=? WHERE key='selected'",arrayOf(bytes))
        assertThrows(IOException::class.java) { f.project() }
        database.writableDatabase.execSQL("UPDATE ${f.table} SET metadata=? WHERE key='selected'",arrayOf(ByteArray(4)))
        database.writableDatabase.execSQL("INSERT INTO ${f.table}(id,key,metadata) VALUES(2147483646,'selected',?)",arrayOf(ByteArray(4)))
        assertThrows(IOException::class.java) { f.project() }; assertArrayEquals(payload,f.original.readBytes())
    }
    @Test fun realFragmentationOverBudgetAndSymlinksRefusePreservingAllOriginalSpans() {
        val root=folders.newFolder(); val source=cache(root); val uid=source.uid
        val original=(0..MIGRATION_RANGES).map { seed(source,"fragmented",it*8L) }
        source.release()
        val read=SQLiteDatabase.openDatabase(database.readableDatabase.path,null,SQLiteDatabase.OPEN_READONLY).also(reads::add)
        assertThrows(IOException::class.java) { LegacyResourceProjection.read(root,uid,"fragmented",read,{}) }
        assertTrue(original.all { it.readBytes().contentEquals(payload) })
        val f=Fixture(); val slot=(0..9).first { !File(f.root,it.toString()).exists() }
        val link=File(f.root,slot.toString())
        Files.createSymbolicLink(link.toPath(),folders.newFolder().toPath())
        try { assertThrows(IOException::class.java) { f.project() } } finally { Files.delete(link.toPath()) }
        assertArrayEquals(payload,f.original.readBytes())
    }
    @Test fun activeNativeAndWritableDatabaseAreRefusedAndBorrowedCheckpointRemainsEffective() {
        val f=Fixture(); var available=true
        val projected=f.project { if(!available) throw IOException("Borrowed source barrier lost") }
        available=false
        assertThrows(IOException::class.java) { projected.getCachedLength("selected",0,100) }
        assertThrows(IOException::class.java) { LegacyResourceProjection.read(f.root,f.uid,"selected",database.writableDatabase,{}) }
        val reopened=cache(f.root)
        assertThrows(IOException::class.java) { f.project() }
        reopened.release(); assertArrayEquals(payload,f.original.readBytes())
    }
}
