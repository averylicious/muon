@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
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
    @Test fun validLargeOptionalMetadataPlaysNativelyAndInTransitionWithoutTrimmingMigration() {
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
        val state=transition.inspect("key/0")
        assertEquals(SavedCoverage.Full,state.coverage)
        assertEquals(4L,ContentMetadata.getContentLength(state.metadata))
        assertFalse(state.metadata.contains("optional-large-field"))
        assertFalse(state.displayMetadataLimited)
        val reader=transition.source.createDataSource()
        try {
            assertEquals(4L,reader.open(spec())); val bytes=ByteArray(4)
            assertEquals(4,reader.read(bytes,0,4)); assertArrayEquals(payload,bytes)
            assertEquals(-1,reader.read(bytes,0,4))
        } finally { reader.close() }
        assertThrows(IOException::class.java) { LegacyResourceProjection.read(f.root,f.uid,"key/0",f.read,{}) }
        database.readableDatabase.rawQuery("SELECT length(metadata) FROM ${f.table} WHERE key='key/0'",null).use {
            assertTrue(it.moveToFirst()); assertTrue(it.getLong(0)>LEGACY_PROJECTION_METADATA_BYTES)
        }
        assertTrue(f.barrier.quiescent); assertFalse(SimpleCache.isCacheFolderLocked(f.root))
        assertArrayEquals(payload,f.originals.single().readBytes())
        // Playback omits the optional field from its temporary view; exact migration still refuses.
        // Neither path rewrites the original serialized metadata or audio.
    }

    private fun metadata(vararg fields:Pair<String,ByteArray>)=ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { stream ->
            stream.writeInt(fields.size)
            for((name,value) in fields) { stream.writeUTF(name); stream.writeInt(value.size); stream.write(value) }
        }
    }.toByteArray()
    private fun Fixture.metadata(bytes:ByteArray) {
        database.writableDatabase.execSQL("UPDATE $table SET metadata=? WHERE key='key/0'",arrayOf(bytes))
    }
    private fun length(value:Long=4L)=ContentMetadata.KEY_CONTENT_LENGTH to ByteBuffer.allocate(8).putLong(value).array()

    @Test fun optionalValueBeforeLengthDoesNotHideLengthAndSmallDisplayTagsRemainAvailable() {
        val f=Fixture()
        val song=byteArrayOf(1,2,3); val origin="http://192.0.2.1:7814/api1/file/1".toByteArray()
        f.metadata(metadata("optional" to ByteArray(LEGACY_PROJECTION_METADATA_BYTES+1),
            SONG_METADATA to song,SAVED_FROM_METADATA to origin,length()))
        val state=f.audio().inspect("key/0")
        assertEquals(SavedCoverage.Full,state.coverage)
        assertArrayEquals(song,state.metadata.get(SONG_METADATA,null as ByteArray?))
        assertEquals(String(origin),state.metadata.get(SAVED_FROM_METADATA,null as String?))
        assertFalse(state.displayMetadataLimited); assertTrue(f.barrier.quiescent)
        assertArrayEquals(payload,f.originals.single().readBytes())
    }
    @Test fun oversizedDisplayTagsAreExplicitlyLimitedWithoutLosingSavedAudioOrInferringLength() {
        val f=Fixture()
        f.metadata(metadata(SONG_METADATA to ByteArray(TRACK_METADATA_MAX_BYTES+1),length()))
        val state=f.audio().inspect("key/0")
        assertEquals(SavedCoverage.Full,state.coverage); assertTrue(state.displayMetadataLimited)
        assertFalse(state.metadata.contains(SONG_METADATA))
        f.metadata(metadata("optional" to ByteArray(LEGACY_PROJECTION_METADATA_BYTES+1)))
        assertEquals(SavedCoverage.UnknownLength,f.audio().inspect("key/0").coverage)
        assertTrue(f.barrier.quiescent); assertArrayEquals(payload,f.originals.single().readBytes())
    }
    @Test fun malformedLengthDuplicateNamesAndTrailingBytesRefuseWithoutRepair() {
        val f=Fixture()
        val cases=listOf(metadata(ContentMetadata.KEY_CONTENT_LENGTH to byteArrayOf(1)),
            metadata(length(),length(8)),metadata("optional" to byteArrayOf(1),"optional" to byteArrayOf(2),length()),
            metadata(length())+byteArrayOf(1),metadata(length()).copyOf(8))
        for(bytes in cases) {
            f.metadata(bytes)
            assertThrows(IOException::class.java) { f.audio().inspect("key/0") }
            assertTrue(f.barrier.quiescent)
            database.readableDatabase.rawQuery("SELECT metadata FROM ${f.table} WHERE key='key/0'",null).use {
                assertTrue(it.moveToFirst()); assertArrayEquals(bytes,it.getBlob(0))
            }
        }
        assertArrayEquals(payload,f.originals.single().readBytes())
    }
    @Test fun nativeInputCeilingRefusesValidOverBudgetBlobAndLeavesItUntouched() {
        val f=Fixture(); val bytes=metadata("optional" to ByteArray(LEGACY_PLAYBACK_METADATA_BYTES),length())
        f.metadata(bytes)
        assertThrows(IOException::class.java) { f.audio().inspect("key/0") }
        val reader=f.audio().source.createDataSource()
        assertThrows(IOException::class.java) { reader.open(spec()) }; reader.close()
        assertTrue(f.barrier.quiescent)
        database.readableDatabase.rawQuery("SELECT length(metadata) FROM ${f.table} WHERE key='key/0'",null).use {
            assertTrue(it.moveToFirst()); assertEquals(bytes.size.toLong(),it.getLong(0))
        }
        assertArrayEquals(payload,f.originals.single().readBytes())
    }
    @Test fun actualDecoderRefusesReentryAndReleasesAdmissionAfterKnownCancellation() {
        val f=Fixture(); var rejected=false
        // Observe the real admission phase, without guessing how many schema checkpoints run.
        val decoder=LegacyResourceProjection::class.java.getDeclaredField("playbackDecoder")
            .apply { isAccessible=true }.get(null) as java.util.concurrent.atomic.AtomicBoolean
        LegacyResourceProjection.readAudio(f.root,f.uid,"key/0",f.read,{
            if(decoder.get() && !rejected) {
                val failure=assertThrows(IOException::class.java) {
                    LegacyResourceProjection.readAudio(f.root,f.uid,"key/0",f.read,{})
                }
                assertTrue(failure.message!!.contains("busy")); rejected=true
            }
        })
        assertTrue(rejected)
        assertThrows(IOException::class.java) {
            LegacyResourceProjection.readAudio(f.root,f.uid,"key/0",f.read,{
                if(decoder.get()) throw IOException("Controlled cancellation during actual decode")
            })
        }
        assertEquals(SavedCoverage.Full,f.audio().inspect("key/0").coverage)
        assertTrue(f.barrier.quiescent); assertArrayEquals(payload,f.originals.single().readBytes())
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
