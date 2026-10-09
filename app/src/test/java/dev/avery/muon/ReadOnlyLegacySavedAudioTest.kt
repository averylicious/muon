@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.io.IOException
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
class ReadOnlyLegacySavedAudioTest {
    @get:Rule val folders=TemporaryFolder()
    private val database by lazy { StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()) }
    private val reads=mutableListOf<SQLiteDatabase>()
    private val payload=byteArrayOf(2,4,6,8)
    private inner class Fixture(count:Int=1,partial:Boolean=false) {
        val root=folders.newFolder(); val barrier=SavedStorageBarrier(); var available=true
        val cache=SimpleCache(root,NoOpCacheEvictor(),database).also { it.checkInitialization() }
        val uid=cache.uid; val table="ExoPlayerCacheIndex"+java.lang.Long.toHexString(uid)
        val originals=mutableListOf<File>()
        val read:SQLiteDatabase
        init {
            try {
                for(i in 0 until count) {
                    val name="key/$i"; val hole=requireNotNull(cache.startReadWrite(name,0,4))
                    try { val file=cache.startFile(name,0,4); file.writeBytes(payload); cache.commitFile(file,4); originals+=file }
                    finally { cache.releaseHoleSpan(hole) }
                    cache.applyContentMetadataMutations(name,ContentMetadataMutations.setContentLength(ContentMetadataMutations(),if(partial) 8 else 4))
                }
            } finally { cache.release() }
            read=SQLiteDatabase.openDatabase(database.readableDatabase.path,null,SQLiteDatabase.OPEN_READONLY).also(reads::add)
        }
        fun audio(files:DataSource.Factory?=null)=ReadOnlyLegacySavedAudio(root,uid,read,barrier,{
            if(!available) throw IOException("Fixture storage unavailable")
        },files)
    }
    @After fun close() { try { reads.asReversed().forEach { it.close() } } finally { database.close() } }
    private fun spec(key:String="key/0",position:Long=0)=DataSpec.Builder().setUri("muon-saved:fixture").setKey(key).setPosition(position).build()
    @Test fun closedLegacyPlaybackReadsExactSavedBytesAndEofKeepsExclusionWithoutNativeIndex() {
        val f=Fixture(100); val audio=f.audio()
        assertEquals(SavedCoverage.Full,audio.inspect("key/0").coverage)
        assertTrue(audio.contains("key/0")); assertFalse(audio.contains("absent"))
        val reader=audio.source.createDataSource()
        assertEquals(4L,reader.open(spec())); val bytes=ByteArray(4)
        assertEquals(4,reader.read(bytes,0,4)); assertArrayEquals(payload,bytes)
        assertEquals(-1,reader.read(ByteArray(1),0,1)); assertEquals(1,f.barrier.active)
        assertThrows(IOException::class.java) { f.barrier.exclusive() }
        reader.close(); assertTrue(f.barrier.quiescent)
        assertFalse(SimpleCache.isCacheFolderLocked(f.root)); f.originals.forEach { assertArrayEquals(payload,it.readBytes()) }
    }
    @Test fun inventoryPagesScalarKeysWithoutMaterializingMetadataAndCallbackKeepsSharedPermit() {
        val f=Fixture(70); database.writableDatabase.execSQL("UPDATE ${f.table} SET metadata=zeroblob(8388608) WHERE key='key/0'")
        var count=0; f.audio().forEachKey { count++; assertThrows(IOException::class.java) { f.barrier.exclusive() }; true }
        assertEquals(70,count); assertTrue(f.barrier.quiescent)
        count=0; f.audio().forEachKey { count++; false }; assertEquals(1,count)
        assertThrows(IOException::class.java) { f.audio().inspect("key/0") }
        assertTrue(f.barrier.quiescent); assertArrayEquals(payload,f.originals.first().readBytes())
    }
    @Test fun validLargeOptionalMetadataPlaysNativelyButTransitionReaderCurrentlyRefusesAudio() {
        val f=Fixture()
        val native=SimpleCache(f.root,NoOpCacheEvictor(),database)
        try {
            native.checkInitialization()
            native.applyContentMetadataMutations("key/0",ContentMetadataMutations().set("optional-large-field",
                ByteArray(LEGACY_PROJECTION_METADATA_BYTES+1) { 7 }))
            val reader=LegacySavedAudio(native).source.createDataSource()
            try {
                assertEquals(4L,reader.open(spec()))
                val bytes=ByteArray(4); assertEquals(4,reader.read(bytes,0,4)); assertArrayEquals(payload,bytes)
            } finally { reader.close() }
        } finally { native.release() }
        val transition=f.audio(); assertTrue(transition.contains("key/0"))
        assertThrows(IOException::class.java) { transition.inspect("key/0") }
        val reader=transition.source.createDataSource()
        assertThrows(IOException::class.java) { reader.open(spec()) }; reader.close()
        assertTrue(f.barrier.quiescent); assertFalse(SimpleCache.isCacheFolderLocked(f.root))
        assertArrayEquals(payload,f.originals.single().readBytes())
        // Characterizes an unresolved compatibility gap, NOT desired future behavior. Metadata
        // budget refusal is appropriate for exact migration; playback needs a bounded scalar path.
    }

    @Test fun unsupportedInventoryIdentityRefusesWithoutSilentlySkippingOrTruncatingIt() {
        val f=Fixture(); database.writableDatabase.execSQL("UPDATE ${f.table} SET id=-1 WHERE key='key/0'")
        var calls=0; assertThrows(IOException::class.java) { f.audio().forEachKey { calls++; true } }
        assertEquals(0,calls); assertTrue(f.barrier.quiescent); assertArrayEquals(payload,f.originals.first().readBytes())
    }
    @Test fun missingSavedByteFailsWithoutUpstreamOrSinkAndKnownCleanupReleasesPermit() {
        val f=Fixture(partial=true); val reader=f.audio().source.createDataSource()
        assertEquals(SavedCoverage.Partial,f.audio().inspect("key/0").coverage)
        assertThrows(IOException::class.java) { reader.open(spec(position=4)) }
        reader.close(); assertTrue(f.barrier.quiescent); assertArrayEquals(payload,f.originals.first().readBytes())
    }
    @Test fun lostAvailabilityStaysFailedUntilActualCloseThenFreshReadCanStart() {
        val f=Fixture(); val reader=f.audio().source.createDataSource(); reader.open(spec())
        f.available=false
        assertThrows(IOException::class.java) { reader.read(ByteArray(1),0,1) }
        f.available=true
        assertThrows(IOException::class.java) { reader.read(ByteArray(1),0,1) }
        reader.close(); reader.open(spec()); reader.close(); assertTrue(f.barrier.quiescent)
    }
    @Test fun unknownFileCloseRetainsExactChildAndBlocksMigrationWithoutRetryingClose() {
        val f=Fixture(); val actual=mutableListOf<DataSource>(); var closes=0
        val files=DataSource.Factory {
            val child=FileDataSource.Factory().createDataSource().also(actual::add)
            object:DataSource {
                override fun addTransferListener(listener:TransferListener)=child.addTransferListener(listener)
                override fun open(spec:DataSpec)=child.open(spec)
                override fun read(buffer:ByteArray,offset:Int,length:Int)=child.read(buffer,offset,length)
                override fun getUri():Uri?=child.uri
                override fun close() { closes++; throw IOException("Unknown real file close") }
            }
        }
        try {
            val reader=f.audio(files).source.createDataSource(); reader.open(spec())
            assertThrows(IOException::class.java) { reader.close() }
            assertThrows(IOException::class.java) { reader.close() }
            assertEquals(1,closes); assertEquals(1,f.barrier.active)
            assertThrows(IOException::class.java) { f.barrier.exclusive() }
            assertArrayEquals(payload,f.originals.first().readBytes())
        } finally { actual.forEach { it.close() } } // Test-only cleanup of disposable actual handle.
    }
}
