@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.DefaultContentMetadata
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
    private fun localMetadata(length:Long=payload.size.toLong())=DefaultContentMetadata.EMPTY.copyWithMutationsApplied(
        ContentMetadataMutations.setContentLength(ContentMetadataMutations().set(SONG_METADATA,song)
            .set(SAVED_FROM_METADATA,origin).set("custom/private-field",byteArrayOf(6,9)),length))
    private fun localSpec(id:String="saved/local-destination",length:Long=payload.size.toLong())=
        DataSpec.Builder().setUri("muon-saved:copy").setKey(id).setLength(length).build()
    @Test fun localSavedDestinationKeepsFullMetadataAndBytesWithoutInventingCompletion() {
        val f=Fixture(); val spec=localSpec(); val metadata=localMetadata()
        try {
            val ticket=worker {f.copy.copyLocalSaved(spec,metadata,upstream())}
            assertEquals(PartitionSavePhase.Closed,f.saves.find(spec.key!!)?.phase)
            assertEquals(0,f.budget.resident); assertTrue(f.barrier.quiescent)
            worker {
                val cache=f.native.openClosedSaveRead(spec.key!!)
                try {
                    assertEquals(metadata,cache.getContentMetadata(spec.key!!))
                    assertEquals(payload.size.toLong(),cache.getCachedBytes(spec.key!!,0,Long.MAX_VALUE))
                } finally {cache.release()}
                val noRecord=PartitionCompletedAudio(f.native,{null},capacity=1)
                try {assertFalse(noRecord.contains(ticket.allocation.key))} finally {noRecord.close()}
            }
            assertThrows(IOException::class.java) {worker {f.copy.copyLocalSaved(spec,metadata,upstream())}}
        } finally {f.close()}
    }
    @Test fun localSavedRejectsNonfreshPartialOrOversizedMetadataBeforeReservation() {
        val f=Fixture()
        try {
            val wrong=listOf(localSpec("old"),localSpec(NEW_SAVE_PREFIX),localSpec(playedKey("saved/local")),
                localSpec().buildUpon().setPosition(1).build(),localSpec(length=3))
            for(spec in wrong) assertThrows(IOException::class.java) {worker {f.copy.copyLocalSaved(spec,localMetadata(),upstream())}}
            val huge=localMetadata().copyWithMutationsApplied(ContentMetadataMutations().set("too-large",ByteArray(MIGRATION_METADATA_BYTES)))
            assertThrows(IOException::class.java) {worker {f.copy.copyLocalSaved(localSpec(),huge,upstream())}}
            assertNull(f.catalog.find("saved/local-destination")); assertNull(f.saves.find("saved/local-destination"))
            assertFalse(File(f.root,"resources").exists()); assertTrue(f.barrier.quiescent)
        } finally {f.close()}
    }
    @Test fun localSavedShortSourceKeepsSourceAndRefusesIncompleteDestination() {
        val f=Fixture(); val spec=localSpec()
        try {
            assertThrows(IOException::class.java) {worker {f.copy.copyLocalSaved(spec,localMetadata(),
                DataSource.Factory {ByteArrayDataSource(payload.copyOf(12))})}}
            assertEquals(PartitionSavePhase.Closed,f.saves.find(spec.key!!)?.phase)
            assertEquals(0,f.budget.resident); assertTrue(f.barrier.quiescent)
            worker {
                val cache=f.native.openClosedSaveRead(spec.key!!)
                try {assertEquals(0L,cache.getCachedBytes(spec.key!!,0,Long.MAX_VALUE))} finally {cache.release()}
            }
        } finally {f.close()}
    }
    @Test fun localSavedUnknownSourceCloseRetainsExactNativeAndWriterWithoutRetirement() {
        val f=Fixture(); val spec=localSpec(); val raw=ByteArrayDataSource(payload); var closes=0; var held:Cache?=null
        val source=DataSource.Factory {object:DataSource by raw {
            override fun close():Unit {closes++; throw IOException("Unknown local source close")}
        }}
        try {
            assertThrows(PartitionOwnershipUncertain::class.java) {worker {f.copy.copyLocalSaved(spec,localMetadata(),source)}}
            assertEquals(1,closes); assertEquals(1,f.budget.resident); assertFalse(f.barrier.allowsShared)
            val handles=PartitionPlayedCopy::class.java.getDeclaredField("retained").apply {isAccessible=true}.get(f.copy) as Array<*>
            held=handles[0] as Cache; assertTrue(handles[1] is PlayedCopyWriter)
            assertEquals(payload.size.toLong(),held.getCachedBytes(spec.key!!,0,Long.MAX_VALUE))
            assertThrows(IOException::class.java) {worker {f.copy.copyLocalSaved(localSpec("saved/another"),localMetadata(),upstream())}}
            assertEquals(1,closes)
        } finally {raw.close(); held?.let {worker {it.release()}}; f.close()}
    }

    @Test @Config(sdk=[34,35]) fun verificationReadsFullAudioAndMetadataWithReadOnlySidecarWhereSupported() {
        val f=Fixture();val spec=localSpec();val id=requireNotNull(spec.key)
        val metadata=localMetadata().copyWithMutationsApplied(ContentMetadataMutations().set("future/chunked",ByteArray(3*64*1024+7) {it.toByte()}))
        fun sidecar(cache:Cache):android.database.sqlite.SQLiteDatabase {
            val h=cache.javaClass.getDeclaredField("h").apply {isAccessible=true}.get(cache)
            val store=h.javaClass.getDeclaredField("metadata").apply {isAccessible=true}.get(h)
            return PartitionContentMetadata::class.java.getDeclaredField("database").apply {isAccessible=true}.get(store) as android.database.sqlite.SQLiteDatabase
        }
        fun hashes(root:File)=root.walkTopDown().filter {it.isFile}.associate {it.relativeTo(root).path to
            java.security.MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()}
        try {
            val ticket=worker {f.copy.copyLocalSaved(spec,metadata,upstream())}
            val root=File(f.catalog.directory(ticket.allocation),"metadata");val before=hashes(root)
            val saved=f.saves.find(id)
            worker {
                val cache=f.native.openClosedSaveVerification(id)
                try {
                    assertEquals(android.os.Build.VERSION.SDK_INT>=35,sidecar(cache).isReadOnly)
                    val digest=SavedAudioDigest().readVerified(spec,LegacySavedAudio(cache).source,{}, {
                        assertEquals(metadata,cache.getContentMetadata(id))
                        assertEquals(payload.size.toLong(),cache.getCachedBytes(id,0,Long.MAX_VALUE))
                    })
                    val expected=java.security.MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") {"%02x".format(it)}
                    assertEquals(expected,digest)
                } finally {cache.release()}
            }
            assertEquals(0,f.budget.resident);assertEquals(saved,f.saves.find(id));assertEquals(before,hashes(root))
            worker {
                val ordinary=f.native.openClosedSaveRead(id)
                try {
                    assertFalse(sidecar(ordinary).isReadOnly)
                    ordinary.applyContentMetadataMutations(id,ContentMetadataMutations().set("writer-still-works",byteArrayOf(5,9)))
                    assertArrayEquals(byteArrayOf(5,9),ordinary.getContentMetadata(id).get("writer-still-works",null as ByteArray?))
                    assertArrayEquals(metadata.get("future/chunked",null as ByteArray?),ordinary.getContentMetadata(id).get("future/chunked",null as ByteArray?))
                } finally {ordinary.release()}
            }
        } finally {f.close()}
    }

}
