@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.DefaultContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
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
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PartitionContentMetadataTest {
    @get:Rule val folders=TemporaryFolder()
    private val database by lazy { StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()) }
    private val caches=ArrayList<SimpleCache>()
    @After fun close() { try { caches.asReversed().forEach { it.release() } } finally { database.close() } }
    private fun native(directory:File)=SimpleCache(directory,NoOpCacheEvictor(),database).also { caches+=it; it.checkInitialization() }
    private fun directory()=File(folders.newFolder(),"metadata")
    private fun sql(root:File,block:(SQLiteDatabase)->Unit)=SQLiteDatabase.openDatabase(File(root,"content-v1.db").path,null,SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun metadata(vararg fields:Pair<String,ByteArray>)=DefaultContentMetadata(linkedMapOf(*fields))
    private fun open(root:File,key:String):PartitionResourceCache {
        val fresh=!root.exists()
        val raw=native(File(root,"bytes"))
        val store=PartitionContentMetadata(File(root,"metadata"),raw.uid,key,create=fresh)
        return try { PartitionResourceCache.open(raw,key,persistent=store) }
            catch(failure:Throwable) { try { store.close() } finally { raw.release() }; throw failure }
    }
    @Test fun largeUnknownFieldsAndExactUtf16NamesSurviveChunkedSQLiteReopen() {
        val root=directory(); val key="saved/\u0000\ud800"; val name="future/\u0000\udfff"
        val bytes=ByteArray(3*1024*1024+7) { (it%251).toByte() }
        PartitionContentMetadata(root,7,key,create=true).use { store ->
            assertEquals(DefaultContentMetadata.EMPTY,store.read())
            store.write(metadata(name to bytes,"" to byteArrayOf(0,4,9)))
            val projection=store.read(); assertArrayEquals(bytes,projection.get(name,null as ByteArray?))
            // A returned map's mutable bytes do not change persistent data.
            projection.entrySet().first { it.key==name }.value[0]=99
            assertArrayEquals(bytes,store.read().get(name,null as ByteArray?))
        }
        PartitionContentMetadata(root,7,key).use { store ->
            assertArrayEquals(bytes,store.read().get(name,null as ByteArray?)); assertEquals(2,store.read().entrySet().size)
        }
        sql(root) { db -> db.rawQuery("SELECT COUNT(*),MAX(length(value)) FROM chunks",null).use {
            assertTrue(it.moveToFirst()); assertTrue(it.getInt(0)>32); assertEquals(64*1024,it.getInt(1))
        } }
    }
    @Test fun ownerMismatchAndFreshCreationRefuseWithoutReplacingExistingMetadata() {
        val root=directory(); val key="exact"
        PartitionContentMetadata(root,9,key,create=true).use { it.write(metadata("kept" to byteArrayOf(1,2))) }
        assertThrows(IOException::class.java) { PartitionContentMetadata(root,10,key) }
        assertThrows(IOException::class.java) { PartitionContentMetadata(root,9,"other") }
        assertThrows(IOException::class.java) { PartitionContentMetadata(root,9,key,create=true) }
        PartitionContentMetadata(root,9,key).use { assertArrayEquals(byteArrayOf(1,2),it.read().get("kept",null as ByteArray?)) }
    }
    @Test fun interruptedChunkWriteRollsBackToThePreviousCompleteProjection() {
        val root=directory()
        PartitionContentMetadata(root,8,"key",create=true).use { it.write(metadata("old" to byteArrayOf(7))) }
        PartitionContentMetadata(root,8,"key",afterChunk={ throw IOException("write interrupted") }).use { store ->
            assertThrows(IOException::class.java) { store.write(metadata("new" to ByteArray(200_000) { 9 })) }
            assertArrayEquals(byteArrayOf(7),store.read().get("old",null as ByteArray?)); assertEquals(1,store.read().entrySet().size)
        }
        PartitionContentMetadata(root,8,"key").use { assertArrayEquals(byteArrayOf(7),it.read().get("old",null as ByteArray?)) }
    }
    @Test fun boundsRefuseBeforeAnyPreviouslySavedFieldChanges() {
        val root=directory()
        PartitionContentMetadata(root,4,"key",create=true).use { store ->
            store.write(metadata("kept" to byteArrayOf(3)))
            assertThrows(IOException::class.java) { store.write(metadata("x" to ByteArray(MIGRATION_METADATA_BYTES))) }
            val tooMany=(0..MIGRATION_METADATA_FIELDS).associate { "$it" to byteArrayOf(1) }
            assertThrows(IOException::class.java) { store.write(DefaultContentMetadata(tooMany)) }
            assertArrayEquals(byteArrayOf(3),store.read().get("kept",null as ByteArray?))
        }
        assertThrows(IOException::class.java) { PartitionContentMetadata(directory(),1,"x".repeat(MIGRATION_KEY_BYTES),create=true) }
    }
    @Test fun missingDatabaseOrPayloadIsRefusedInsteadOfInventingEmptyMetadata() {
        val root=directory()
        assertThrows(IOException::class.java) { PartitionContentMetadata(root,5,"key") }; assertFalse(root.exists())
        PartitionContentMetadata(root,5,"key",create=true).use { it.write(metadata("kept" to byteArrayOf(5))) }
        sql(root) { it.execSQL("DELETE FROM chunks") }
        PartitionContentMetadata(root,5,"key").use { assertThrows(IOException::class.java) { it.read() } }
        assertTrue(File(root,"content-v1.db").delete())
        assertThrows(IOException::class.java) { PartitionContentMetadata(root,5,"key") }
        assertFalse(File(root,"content-v1.db").exists())
    }
    @Test fun unknownSchemaAndChangedOwnerAreRefusedAndKeptOnDisk() {
        val root=directory()
        PartitionContentMetadata(root,6,"key",create=true).close()
        sql(root) { it.execSQL("CREATE TABLE foreign_data(value BLOB)") }
        assertThrows(IOException::class.java) { PartitionContentMetadata(root,6,"key") }
        sql(root) { db -> db.rawQuery("SELECT name FROM sqlite_master WHERE name='foreign_data'",null).use { assertTrue(it.moveToFirst()) }
            db.execSQL("DROP TABLE foreign_data"); db.execSQL("UPDATE owner SET key=?",arrayOf(savedCatalogSortKey("other"))) }
        assertThrows(IOException::class.java) { PartitionContentMetadata(root,6,"key") }
    }
    @Test fun noncontiguousAndOversizedChunksAreRefusedWithoutReset() {
        for(statement in listOf("UPDATE chunks SET ordinal=2","UPDATE chunks SET value=zeroblob(65537)")) {
            val root=directory(); PartitionContentMetadata(root,1,"key",create=true).close(); sql(root) { it.execSQL(statement) }
            PartitionContentMetadata(root,1,"key").use { assertThrows(IOException::class.java) { it.read() } }
            sql(root) { db -> db.rawQuery("SELECT COUNT(*) FROM chunks",null).use { assertTrue(it.moveToFirst()); assertEquals(1,it.getInt(0)) } }
        }
    }
    @Test fun duplicateFieldsTrailingBytesAndInvalidNameEncodingAreRefused() {
        val payloads=ArrayList<ByteArray>()
        fun encoded(block:(DataOutputStream)->Unit)=ByteArrayOutputStream().also { out -> DataOutputStream(out).use(block) }.toByteArray()
        payloads+=encoded { it.writeInt(2); repeat(2) { _ -> it.writeInt(0); it.writeInt(1); it.writeByte(9) } }
        payloads+=encoded { it.writeInt(0); it.writeByte(1) }
        payloads+=encoded { it.writeInt(1); it.writeInt(1); it.writeByte(0); it.writeInt(0) }
        for(payload in payloads) {
            val root=directory(); PartitionContentMetadata(root,1,"key",create=true).close()
            sql(root) { it.execSQL("UPDATE chunks SET value=?",arrayOf(payload)) }
            PartitionContentMetadata(root,1,"key").use { assertThrows(IOException::class.java) { it.read() } }
        }
    }
    @Test fun metadataOnlyMigrationPublishesOnlyAfterSeparateStoreSurvivesNativeReopen() {
        val source=native(folders.newFolder()); val key="metadata-only"; val kept=byteArrayOf(4,0,9)
        source.applyContentMetadataMutations(key,ContentMetadataMutations().set("future",kept))
        val root=folders.newFolder(); lateinit var ready:MigrationRecord
        CachePartitionCatalog(root).use { catalog -> CacheMigrationJournal(root).use { journal ->
            ready=CacheMigrationPublication(catalog,journal,{ open(it,key) }).migrate(source,key,{})
            assertEquals(MigrationPhase.Ready,ready.phase); assertEquals(MigrationCopyEvidence(0,0),ready.evidence)
            open(catalog.directory(ready.ticket.allocation),key).let { target ->
                assertEquals(ready.targetUid,target.uid); assertEquals(setOf(key),target.keys)
                assertArrayEquals(kept,target.getContentMetadata(key).get("future",null as ByteArray?)); assertTrue(target.getCachedSpans(key).isEmpty())
                target.release()
            }
            assertTrue(File(catalog.directory(ready.ticket.allocation),"metadata/content-v1.db").isFile)
        } }
        CacheMigrationJournal(root).use { assertEquals(ready,it.ready(key)) }
        assertArrayEquals(kept,source.getContentMetadata(key).get("future",null as ByteArray?))
    }
    @Test fun actualCacheWriterUsesLogicalLengthAndReopensBytesWithoutNativeMetadata() {
        val root=File(folders.newFolder(),"partition"); val key="audio"; val payload=ByteArray(4096) { (it%251).toByte() }
        val partition=open(root,key)
        partition.applyContentMetadataMutations(key,ContentMetadataMutations().set("unknown",byteArrayOf(8,9)))
        val input=CacheDataSource.Factory().setCache(partition).setUpstreamDataSourceFactory { ByteArrayDataSource(payload) }.createDataSourceForDownloading()
        CacheWriter(input,DataSpec.Builder().setUri(Uri.parse("fixture://audio")).setKey(key).build(),null,null).cache()
        assertTrue(partition.isCached(key,0,payload.size.toLong()))
        assertEquals(payload.size.toLong(),ContentMetadata.getContentLength(partition.getContentMetadata(key)))
        val span=partition.getCachedSpans(key).single(); assertArrayEquals(payload,requireNotNull(span.file).readBytes()); partition.release()
        val reopened=open(root,key); assertArrayEquals(byteArrayOf(8,9),reopened.getContentMetadata(key).get("unknown",null as ByteArray?))
        assertEquals(payload.size.toLong(),ContentMetadata.getContentLength(reopened.getContentMetadata(key)))
        assertArrayEquals(payload,requireNotNull(reopened.getCachedSpans(key).single().file).readBytes()); reopened.release()
        val raw=native(File(root,"bytes")); assertEquals(DefaultContentMetadata.EMPTY,raw.getContentMetadata(key)); raw.release()
    }
    @Test fun removingLastSpanKeepsMetadataAndExplicitResourceRemovalClearsIt() {
        val root=File(folders.newFolder(),"partition"); val key="audio"; val partition=open(root,key)
        val hole=requireNotNull(partition.startReadWriteNonBlocking(key,0,2))
        val file=partition.startFile(key,0,2).apply { writeBytes(byteArrayOf(1,2)) }; partition.commitFile(file,2); partition.releaseHoleSpan(hole)
        partition.applyContentMetadataMutations(key,ContentMetadataMutations().set("kept",byteArrayOf(7)))
        partition.removeSpan(partition.getCachedSpans(key).single()); assertEquals(setOf(key),partition.keys)
        assertArrayEquals(byteArrayOf(7),partition.getContentMetadata(key).get("kept",null as ByteArray?)); partition.release()
        val reopened=open(root,key); assertArrayEquals(byteArrayOf(7),reopened.getContentMetadata(key).get("kept",null as ByteArray?))
        reopened.removeResource(key); assertTrue(reopened.keys.isEmpty()); reopened.release()
        open(root,key).let { assertEquals(DefaultContentMetadata.EMPTY,it.getContentMetadata(key)); it.release() }
    }
    @Test fun logicalLengthRefusesOversizedCommitBeforeNativeCacheCanPublishIt() {
        val root=File(folders.newFolder(),"partition"); val key="audio"; val partition=open(root,key)
        partition.applyContentMetadataMutations(key,ContentMetadataMutations().set("exo_len",2L))
        val hole=requireNotNull(partition.startReadWriteNonBlocking(key,0,4))
        val staged=partition.startFile(key,0,4).apply { writeBytes(byteArrayOf(1,2,3,4)) }
        assertThrows(Cache.CacheException::class.java) { partition.commitFile(staged,4) }
        assertTrue(partition.getCachedSpans(key).isEmpty()); assertArrayEquals(byteArrayOf(1,2,3,4),staged.readBytes())
        assertEquals(2L,ContentMetadata.getContentLength(partition.getContentMetadata(key)))
        partition.releaseHoleSpan(hole); partition.release()
    }
    @Test fun unreadableMetadataStopsNativeWriteAdmissionAndPreservesExistingBytes() {
        val root=File(folders.newFolder(),"partition"); val key="audio"; val partition=open(root,key)
        val hole=requireNotNull(partition.startReadWriteNonBlocking(key,0,2))
        val file=partition.startFile(key,0,2).apply { writeBytes(byteArrayOf(6,7)) }; partition.commitFile(file,2); partition.releaseHoleSpan(hole)
        sql(File(root,"metadata")) { it.execSQL("DELETE FROM chunks") }
        assertThrows(Cache.CacheException::class.java) { partition.startReadWriteNonBlocking(key,2,2) }
        assertTrue(partition.uncertain); assertArrayEquals(byteArrayOf(6,7),file.readBytes()); partition.release()
    }
    @Test fun inconsistentLegacyLengthCannotPublishAReplacementThatHidesRetainedBytes() {
        val source=native(folders.newFolder()); val key="audio"
        val hole=requireNotNull(source.startReadWriteNonBlocking(key,90,2))
        val file=source.startFile(key,90,2).apply { writeBytes(byteArrayOf(8,9)) }; source.commitFile(file,2); source.releaseHoleSpan(hole)
        source.applyContentMetadataMutations(key,ContentMetadataMutations().set("exo_len",1L))
        val root=folders.newFolder()
        CachePartitionCatalog(root).use { catalog -> CacheMigrationJournal(root).use { journal ->
            assertThrows(Cache.CacheException::class.java) { CacheMigrationPublication(catalog,journal,{ open(it,key) }).migrate(source,key,{}) }
            assertNull(journal.ready(key)); assertEquals(MigrationPhase.Uncertain,journal.find(key)?.phase)
            assertArrayEquals(byteArrayOf(8,9),file.readBytes()); assertEquals(1L,ContentMetadata.getContentLength(source.getContentMetadata(key)))
        } }
    }
    @Test fun sidecarIdentityAndLegacyNativeMetadataCannotBeSilentlyAdopted() {
        val key="audio"; val raw=native(folders.newFolder()); raw.applyContentMetadataMutations(key,ContentMetadataMutations().set("kept",byteArrayOf(1)))
        val store=PartitionContentMetadata(directory(),raw.uid,key,create=true)
        assertThrows(IOException::class.java) { PartitionResourceCache.open(raw,key,persistent=store) }
        assertArrayEquals(byteArrayOf(1),raw.getContentMetadata(key).get("kept",null as ByteArray?)); store.close()
        val fresh=native(folders.newFolder()); val other=PartitionContentMetadata(directory(),fresh.uid,"other",create=true)
        assertThrows(IOException::class.java) { PartitionResourceCache.open(fresh,key,persistent=other) }; assertTrue(fresh.keys.isEmpty()); other.close()
    }
    @Test fun oversizedOwnerKeyRefusesBeforeLoadingItIntoTheCursorWindowAndKeepsTheRecord() {
        val root=directory(); PartitionContentMetadata(root,23,"audio",create=true).close()
        sql(root) { it.execSQL("UPDATE owner SET key=zeroblob(3145728)") }
        assertThrows(IOException::class.java) { PartitionContentMetadata(root,23,"audio") }
        sql(root) { db -> db.rawQuery("SELECT length(key) FROM owner",null).use { assertTrue(it.moveToFirst()); assertEquals(3145728,it.getInt(0)) } }
    }
    @Test fun oversizedOrWrongTypedChunksRefuseBeforeCursorProjectionWithoutReplacingTheirPayload() {
        for(blob in listOf(true,false)) {
            val root=directory(); PartitionContentMetadata(root,23,"audio",create=true).close()
            sql(root) { db ->
                if(blob) db.execSQL("UPDATE chunks SET value=zeroblob(3145728)")
                else db.execSQL("UPDATE chunks SET value=?",arrayOf("\u0000"+"x".repeat(3145728)))
            }
            PartitionContentMetadata(root,23,"audio").use { store -> assertThrows(IOException::class.java) { store.read() } }
            sql(root) { db -> db.rawQuery("SELECT length(CAST(value AS BLOB)) FROM chunks",null).use {
                assertTrue(it.moveToFirst()); assertEquals(if(blob) 3145728 else 3145729,it.getInt(0))
            } }
        }
    }

}
