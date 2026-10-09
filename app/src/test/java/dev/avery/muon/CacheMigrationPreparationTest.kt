@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadataMutations
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
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34],manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CacheMigrationPreparationTest {
    @get:Rule val folders = TemporaryFolder()
    private val database by lazy { StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()) }
    private val caches = ArrayList<SimpleCache>()
    @After fun close() { try { caches.asReversed().forEach { it.release() } } finally { database.close() } }

    @Test fun supportedScalarRangesCopyEveryPartialByteAndUnknownMetadataWithoutRemovingSource() {
        val source = cache(); val target = cache(); val key = "saved/legacy"
        seed(source,key,0,byteArrayOf(1,2,3)); seed(source,key,3,byteArrayOf(4,5))
        seed(source,key,10,byteArrayOf(6,7)); seed(source,key,100,byteArrayOf(8))
        source.applyContentMetadataMutations(key,ContentMetadataMutations().set("unknown-field",byteArrayOf(0,1,2)).set("exo_len",4L))
        val original = source.getCachedSpans(key).associate { requireNotNull(it.file).absolutePath to requireNotNull(it.file).readBytes() }
        val noSets = object : Cache by source {
            override fun getCachedSpans(key: String) = throw AssertionError("No native span snapshot")
            override fun getKeys() = throw AssertionError("No native key snapshot")
        }
        val evidence = CacheMigrationPreparation.copy(noSets,target,key,{})
        assertEquals(MigrationCopyEvidence(8,3),evidence)
        assertEquals(source.getContentMetadata(key),target.getContentMetadata(key))
        assertEquals(listOf(MigrationRange(0,5),MigrationRange(10,2),MigrationRange(100,1)),CacheMigrationPreparation.ranges(noSets,key))
        assertFalse(target.isCached(key,5,1)); assertFalse(target.isCached(key,12,1))
        original.forEach { (path,bytes) -> assertArrayEquals(bytes,java.io.File(path).readBytes()) }
        assertTrue(source.isCached(key,100,1)) // Even beyond its declared length, retained verbatim.
    }
    @Test fun metadataOnlyUnknownResourcesArePreservedAndAnExistingTargetIsNeverReplaced() {
        val source=cache(); val target=cache(); val key="unknown"
        source.applyContentMetadataMutations(key,ContentMetadataMutations().set("future-format",byteArrayOf(7,8)))
        assertEquals(MigrationCopyEvidence(0,0),CacheMigrationPreparation.copy(source,target,key,{}))
        assertEquals(source.getContentMetadata(key),target.getContentMetadata(key))
        val existing=cache(); seed(existing,key,0,byteArrayOf(99))
        assertThrows(IOException::class.java) { CacheMigrationPreparation.copy(source,existing,key,{}) }
        assertTrue(existing.isCached(key,0,1)); assertEquals(99,requireNotNull(existing.getCachedSpans(key).first().file).readBytes()[0].toInt())
    }
    @Test fun fragmentationMetadataAndBrokenProbesRefuseBeforeTheFirstDestinationWrite() {
        val source=cache(); val target=cache(); val key="fragmented"
        repeat(MIGRATION_RANGES+1) { seed(source,key,it*3L,byteArrayOf(7)) }
        assertThrows(IOException::class.java) { CacheMigrationPreparation.copy(source,target,key,{}) }
        assertEquals(0L,target.cacheSpace); assertEquals((MIGRATION_RANGES+1).toLong(),source.cacheSpace)
        val metadata=cache()
        metadata.applyContentMetadataMutations(key,ContentMetadataMutations().set("large",ByteArray(MIGRATION_METADATA_BYTES+1)))
        assertThrows(IOException::class.java) { CacheMigrationPreparation.copy(metadata,target,key,{}) }
        assertEquals(0L,target.cacheSpace)
        val broken=object : Cache by source { override fun getCachedLength(key:String,position:Long,length:Long)=0L }
        assertThrows(IOException::class.java) { CacheMigrationPreparation.ranges(broken,key) }
        val overflow=object : Cache by source { override fun getCachedLength(key:String,position:Long,length:Long)=Long.MIN_VALUE }
        assertThrows(IOException::class.java) { CacheMigrationPreparation.ranges(overflow,key) }
    }
    @Test fun cancellationWriteFailureAndLateMutationNeverYieldVerificationOrRemoveOriginals() {
        val source=cache(); val key="saved/kept"; seed(source,key,0,ByteArray(100_000) { 42 })
        val target=cache()
        assertThrows(IOException::class.java) {
            CacheMigrationPreparation.copy(source,target,key,{ if (target.cacheSpace>0) throw IOException("Cancelled") })
        }
        assertTrue(source.isCached(key,0,100_000))
        val failed=cache()
        val outputs=MoveFileOutputs { throw IOException("Full destination") }
        assertThrows(IOException::class.java) { CacheMigrationPreparation.copy(source,failed,key,{},outputs) }
        assertTrue(source.isCached(key,0,100_000))
        val changed=cache(); var once=false
        assertThrows(IOException::class.java) {
            CacheMigrationPreparation.copy(source,changed,key,{
                if (!once && changed.cacheSpace>0) { once=true; source.applyContentMetadataMutations(key,ContentMetadataMutations().set("changed",1L)) }
            })
        }
        assertTrue(source.isCached(key,0,100_000)); assertTrue(source.getContentMetadata(key).contains("changed"))
    }
    private fun cache()=SimpleCache(folders.newFolder(),NoOpCacheEvictor(),database).also { caches+=it; it.checkInitialization() }
    private fun seed(cache:Cache,key:String,position:Long,bytes:ByteArray) {
        val hole=requireNotNull(cache.startReadWriteNonBlocking(key,position,bytes.size.toLong()))
        try { val file=cache.startFile(key,position,bytes.size.toLong()); file.writeBytes(bytes); cache.commitFile(file,bytes.size.toLong()) }
        finally { cache.releaseHoleSpan(hole) }
    }
}
