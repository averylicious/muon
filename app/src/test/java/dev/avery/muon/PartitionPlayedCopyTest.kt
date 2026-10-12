@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
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
class PartitionPlayedCopyTest {
    @get:Rule val folders=TemporaryFolder()
    private val key=playedKey("saved/fresh-fixture")
    private val origin="http://127.0.0.1:7814/7"
    private val payload=ByteArray(4096) { (it%127).toByte() }
    private val song=byteArrayOf(1,2,3)
    private fun spec()=DataSpec.Builder().setUri("http://127.0.0.1:7814/api1/fileopus/7").setKey(key).build()
    private fun upstream()=DataSource.Factory { ByteArrayDataSource(payload) }
    private fun <T> worker(action:()->T):T {
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            return try { executor.submit<T> { action() }.get(10,TimeUnit.SECONDS) }
            catch(failure:java.util.concurrent.ExecutionException) { throw requireNotNull(failure.cause) }
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS)) }
    }
    private inner class Fixture {
        val root=folders.newFolder(); val catalog=CachePartitionCatalog(root)
        val migrations=CacheMigrationJournal(root); val saves=PartitionSaveJournal(root,create=true)
        val budget=PartitionNativeBudget(1); val barrier=SavedStorageBarrier()
        val native=PartitionNativeOwner(catalog,migrations,"fixture",{"fixture"},budget=budget,saves=saves)
        val copy=PartitionPlayedCopy(catalog,saves,migrations,native,barrier,{true},{true})
        fun reader()=PartitionCompletedAudio(native,{ throw AssertionError("Played copy must not fabricate/query a manager completion") },capacity=1,allowPlayed=true)
        fun close() { assertEquals(0,budget.resident); saves.close(); migrations.close(); catalog.close() }
    }
    @Test fun completedPlayedCopyReadsWithoutManagerCompletionAndSurvivesFreshReaderOwner() {
        val f=Fixture(); var audio:PartitionCompletedAudio?=null
        try {
            val ticket=worker { f.copy.copy(spec(),song,origin,upstream()) { 8192L } }
            assertEquals(0,f.budget.resident); assertTrue(f.barrier.quiescent)
            assertEquals(PartitionSavePhase.Closed,f.saves.find(key)?.phase)
            val first=f.reader(); audio=first
            worker {
                val state=first.inspect(key); assertEquals(SavedCoverage.Full,state.coverage)
                assertEquals(payload.size.toLong(),state.bytes)
                assertArrayEquals(song,state.metadata.get(SONG_METADATA,null as ByteArray?))
                assertEquals(origin,state.metadata.get(SAVED_FROM_METADATA,null as String?))
                first.close()
            }
            assertEquals(0,f.budget.resident)
            val restartedNative=PartitionNativeOwner(f.catalog,f.migrations,"fixture",{"fixture"},budget=f.budget,saves=f.saves)
            val resumed=PartitionCompletedAudio(restartedNative,{ throw AssertionError("No manager record") },capacity=1,allowPlayed=true)
            audio=resumed
            worker {
                val source=resumed.source.createDataSource()
                try {
                    assertEquals(payload.size.toLong(),source.open(spec()))
                    val bytes=ByteArray(payload.size); var offset=0
                    while(offset<bytes.size) { val count=source.read(bytes,offset,bytes.size-offset); assertTrue(count>0); offset+=count }
                    assertArrayEquals(payload,bytes)
                } finally { source.close() }
            }
            assertEquals(ticket,f.saves.find(key)?.ticket); assertEquals(PartitionSavePhase.Closed,f.saves.find(key)?.phase)
            assertThrows(IOException::class.java) { worker { f.copy.copy(spec(),song,origin,upstream()) { 8192L } } }
        } finally { audio?.let { worker { it.close() } }; f.close() }
    }
    @Test fun oversizedMetadataRefusesBeforeAnyJournalReservationOrNativeOpen() {
        val f=Fixture()
        try {
            assertThrows(IOException::class.java) {
                worker { f.copy.copy(spec(),ByteArray(MIGRATION_METADATA_BYTES+1),origin,upstream()) { 8192L } }
            }
            assertNull(f.catalog.find(key)); assertNull(f.saves.find(key))
            assertFalse(File(f.root,"resources").exists()); assertTrue(f.barrier.quiescent)
        } finally { f.close() }
    }
    @Test fun tooLargeKnownClosedAttemptCannotPlayOrPoisonInspectionOfSavedInventory() {
        val f=Fixture(); var audio:PartitionCompletedAudio?=null
        try {
            assertThrows(PlayedCopyTooLarge::class.java) { worker { f.copy.copy(spec(),song,origin,upstream()) { 2L } } }
            assertEquals(0,f.budget.resident); assertTrue(f.barrier.quiescent)
            assertEquals(PartitionSavePhase.Closed,f.saves.find(key)?.phase)
            val read=f.reader(); audio=read
            worker {
                assertEquals(SavedCoverage.Missing,read.inspect(key).coverage)
                val source=read.source.createDataSource()
                try { assertThrows(IOException::class.java) { source.open(spec()) } }
                finally { source.close() }
                assertEquals(PartitionSavePhase.Closed,f.saves.find(key)?.phase)
            }
        } finally { audio?.let { worker { it.close() } }; f.close() }
    }
    @Test fun unknownActualSourceCloseRetainsNativeAndWriterWithoutCleanupOrSecondClose() {
        val f=Fixture(); val raw=ByteArrayDataSource(payload); var closes=0; var retained:Cache?=null
        val source=DataSource.Factory { object:DataSource by raw {
            override fun close():Unit { closes++; throw IOException("Unknown actual source close") }
        } }
        try {
            assertThrows(PartitionOwnershipUncertain::class.java) { worker { f.copy.copy(spec(),song,origin,source) { 8192L } } }
            assertEquals(1,closes); assertEquals(1,f.budget.resident); assertEquals(1,f.barrier.active)
            assertFalse(f.barrier.allowsShared); assertEquals(PartitionSavePhase.Open,f.saves.find(key)?.phase)
            val handles=PartitionPlayedCopy::class.java.getDeclaredField("retained").apply { isAccessible=true }.get(f.copy) as Array<*>
            retained=handles[0] as Cache
            assertTrue(handles[1] is PlayedCopyWriter)
            assertEquals(payload.size.toLong(),retained.getCachedBytes(key,0,Long.MAX_VALUE))
            assertEquals(payload.size.toLong(),ContentMetadata.getContentLength(retained.getContentMetadata(key)))
            assertThrows(IOException::class.java) { worker { f.copy.copy(spec(),song,origin,upstream()) { 8192L } } }
            assertEquals(1,closes); assertEquals(1,f.budget.resident)
        } finally {
            // Fixture-only proof: explicitly close the raw test source, then retire the captured
            // native owner. Production has no forced close, reset or gate-unquarantine API.
            raw.close(); retained?.let { worker { it.release() } }; f.close()
        }
    }
}
