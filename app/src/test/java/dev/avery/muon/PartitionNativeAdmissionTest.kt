@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.database.VersionTable
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
import java.io.File
import java.io.IOException
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PartitionNativeAdmissionTest {
    @get:Rule val folders=TemporaryFolder()
    private val database by lazy { StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()) }
    private val caches=ArrayList<SimpleCache>()
    private val key="saved/exact"
    private data class Fixture(val root:File,val uid:Long,val audio:Map<File,ByteArray>)
    @After fun close() { try { caches.asReversed().forEach { it.release() } } finally { database.close() } }
    private fun fixture(count:Int=1):Fixture {
        val root=folders.newFolder()
        val cache=SimpleCache(root,NoOpCacheEvictor(),database).also { caches+=it; it.checkInitialization() }
        repeat(count) { n ->
            val hole=requireNotNull(cache.startReadWriteNonBlocking(key,n*10L,3))
            try { val file=cache.startFile(key,n*10L,3); file.writeBytes(byteArrayOf(1,2,n.toByte())); cache.commitFile(file,3) }
            finally { cache.releaseHoleSpan(hole) }
        }
        val result=Fixture(root,cache.uid,cache.getCachedSpans(key).associate { requireNotNull(it.file) to requireNotNull(it.file).readBytes() })
        cache.release(); return result
    }
    private fun inspect(f:Fixture,limits:PartitionResourceLimits=PartitionResourceLimits())=
        PartitionNativeAdmission.inspect(f.root,f.uid,key,database.readableDatabase,limits)
    private fun kept(f:Fixture)=f.audio.forEach { (file,bytes) -> assertArrayEquals(bytes,file.readBytes()) }
    private fun table(f:Fixture,files:Boolean=false)=DatabaseProvider.TABLE_PREFIX+(if(files) "CacheFileMetadata" else "CacheIndex")+java.lang.Long.toHexString(f.uid)
    @Test fun closedActualNativeIndexAndEverySpanAreAdmittedWithoutOpeningOrChangingAnything() {
        val f=fixture(3); assertEquals(PartitionNativeEvidence(9,3),inspect(f)); kept(f)
        assertFalse(SimpleCache.isCacheFolderLocked(f.root)); assertEquals(PartitionNativeEvidence(9,3),inspect(f))
        val reopened=SimpleCache(f.root,NoOpCacheEvictor(),database).also { caches+=it; it.checkInitialization() }
        assertEquals(f.uid,reopened.uid); assertEquals(3,reopened.getCachedSpans(key).size); reopened.release(); kept(f)
    }
    @Test fun aMetadataOnlyNativeEmptyIndexAndFolderStayValidWithoutInventingASpan() {
        val f=fixture(0); assertEquals(PartitionNativeEvidence(0,0),inspect(f)); assertFalse(SimpleCache.isCacheFolderLocked(f.root))
    }
    @Test fun missingOrChangedUidRefusesBeforeNativeCanGenerateAnotherOneOrDeleteFiles() {
        val f=fixture(); val marker=File(f.root,java.lang.Long.toHexString(f.uid)+".uid"); assertTrue(marker.delete())
        assertThrows(IOException::class.java) { inspect(f) }; assertFalse(marker.exists()); kept(f)
        val wrong=File(f.root,"broken.uid").apply { writeBytes(byteArrayOf()) }
        assertThrows(IOException::class.java) { inspect(f) }; assertTrue(wrong.exists()); kept(f)
    }
    @Test fun missingOrIncompatibleContentIndexIsRefusedWithoutNativeSchemaReinitialization() {
        val f=fixture(); val db=database.writableDatabase
        VersionTable.setVersion(db,VersionTable.FEATURE_CACHE_CONTENT_METADATA,java.lang.Long.toHexString(f.uid),99)
        assertThrows(IOException::class.java) { inspect(f) }
        assertEquals(99,VersionTable.getVersion(db,VersionTable.FEATURE_CACHE_CONTENT_METADATA,java.lang.Long.toHexString(f.uid))); kept(f)
        VersionTable.setVersion(db,VersionTable.FEATURE_CACHE_CONTENT_METADATA,java.lang.Long.toHexString(f.uid),1)
        db.execSQL("DROP TABLE ${table(f)}")
        assertThrows(IOException::class.java) { inspect(f) }; kept(f)
        db.rawQuery("SELECT name FROM sqlite_master WHERE name=?",arrayOf(table(f))).use { assertFalse(it.moveToFirst()) }
    }
    @Test fun anotherKeyMultipleRowsOrNativeMetadataCannotEnterTheSingleResourcePartition() {
        for(change in listOf("UPDATE %s SET key='other'","INSERT INTO %s VALUES(1,'other',X'00000000')","UPDATE %s SET metadata=zeroblob(5000000)")) {
            val f=fixture(); database.writableDatabase.execSQL(change.format(table(f)))
            assertThrows(IOException::class.java) { inspect(f) }; kept(f)
        }
    }
    @Test fun embeddedNulCannotBypassKeyOrFilenameCursorWindowBudgets() {
        val oversized="\u0000"+"x".repeat(3*1024*1024)
        for(files in listOf(false,true)) {
            val f=fixture()
            database.writableDatabase.execSQL("UPDATE ${table(f,files)} SET ${if(files) "name" else "key"}=?",arrayOf(oversized))
            // A TEXT length guard would stop at NUL and load a >2MiB row instead of this refusal.
            assertThrows(IOException::class.java) { inspect(f) }; kept(f)
        }
    }
    @Test fun nativeSpanBudgetRefusesBeforeLoadingAnOverfullIndex() {
        val f=fixture(2); assertThrows(IOException::class.java) { inspect(f,PartitionResourceLimits(spans=1)) }; kept(f)
        assertEquals(PartitionNativeEvidence(6,2),inspect(f)); kept(f)
    }
    @Test fun staleFileLengthsOrMissingFileRowsRefuseInsteadOfLettingNativeCleanThemUp() {
        val f=fixture(); database.writableDatabase.execSQL("UPDATE ${table(f,true)} SET length=90")
        assertThrows(IOException::class.java) { inspect(f) }; kept(f)
        database.writableDatabase.execSQL("DELETE FROM ${table(f,true)}")
        assertThrows(IOException::class.java) { inspect(f) }; kept(f)
    }
    @Test fun foreignFilesSymlinksAndUnindexedSpansAreNotDeletedOrAdopted() {
        val f=fixture(); val foreign=File(f.root,"foreign").apply { writeBytes(byteArrayOf(8)) }
        assertThrows(IOException::class.java) { inspect(f) }; assertArrayEquals(byteArrayOf(8),foreign.readBytes()); kept(f)
        assertTrue(foreign.delete())
        val link=File(f.root,"0-link"); Files.createSymbolicLink(link.toPath(),f.audio.keys.single().toPath())
        assertThrows(IOException::class.java) { inspect(f) }; assertTrue(Files.isSymbolicLink(link.toPath())); kept(f)
        Files.delete(link.toPath())
        val unindexed=File(f.audio.keys.single().parentFile,"0.90.1.v3.exo").apply { writeBytes(byteArrayOf(9)) }
        assertThrows(IOException::class.java) { inspect(f) }; assertArrayEquals(byteArrayOf(9),unindexed.readBytes()); kept(f)
    }
    @Test fun unknownTriggersOrFileFormatsRefuseWithoutEditingNativeTablesOrFiles() {
        val f=fixture(); val db=database.writableDatabase
        db.execSQL("CREATE TRIGGER foreign_native_trigger AFTER INSERT ON ${table(f)} BEGIN SELECT 1; END")
        assertThrows(IOException::class.java) { inspect(f) }; kept(f)
        db.rawQuery("SELECT name FROM sqlite_master WHERE name='foreign_native_trigger'",null).use { assertTrue(it.moveToFirst()) }
        db.execSQL("DROP TRIGGER foreign_native_trigger")
        db.execSQL("UPDATE ${table(f,true)} SET name='0.0.0.v4.exo'")
        assertThrows(IOException::class.java) { inspect(f) }; kept(f)
    }
    @Test fun overlappingIndexedFilesRefuseRatherThanLettingNativeCollapseTheirIdentity() {
        val f=fixture(); val original=f.audio.keys.single(); val fields=original.name.split('.')
        val other=File(original.parentFile,"${fields[0]}.${fields[1]}.${fields[2].toLong()+1}.v3.exo")
        other.writeBytes(byteArrayOf(9,8,7))
        database.writableDatabase.execSQL("INSERT INTO ${table(f,true)} VALUES(?,?,?)",arrayOf(other.name,3,0))
        assertThrows(IOException::class.java) { inspect(f) }; kept(f); assertArrayEquals(byteArrayOf(9,8,7),other.readBytes())
    }
    @Test fun liveNativeInstanceAndCancelledPreflightNeverGrantAnotherOpen() {
        val f=fixture(); val live=SimpleCache(f.root,NoOpCacheEvictor(),database).also { caches+=it; it.checkInitialization() }
        assertThrows(IOException::class.java) { inspect(f) }; assertEquals(f.uid,live.uid); kept(f); live.release()
        var calls=0
        assertThrows(IOException::class.java) { PartitionNativeAdmission.inspect(f.root,f.uid,key,database.readableDatabase,checkpoint={
            if(++calls==4) throw IOException("Cancelled")
        }) }; assertFalse(SimpleCache.isCacheFolderLocked(f.root)); kept(f)
    }
}
