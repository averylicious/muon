@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.database.sqlite.SQLiteDatabase
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.io.IOException
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
class LegacyTransitionOwnerTest {
    @get:Rule val folders=TemporaryFolder()
    private val payload=byteArrayOf(2,4,6,8)
    private val spec get()=DataSpec.Builder().setUri("muon-saved:owner-test").setKey("saved/0").build()
    private inner class Fixture(count:Int=1) {
        val root=folders.newFolder()
        val provider=StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        val databaseFile:File
        val uid:Long
        val originals=mutableListOf<File>()
        val barrier=SavedStorageBarrier()
        var present=true
        init {
            val native=SimpleCache(root,NoOpCacheEvictor(),provider)
            try {
                native.checkInitialization(); uid=native.uid
                for(i in 0 until count) {
                    val key="saved/$i"; val hole=requireNotNull(native.startReadWrite(key,0,4))
                    try { val file=native.startFile(key,0,4); file.writeBytes(payload); native.commitFile(file,4); originals+=file }
                    finally { native.releaseHoleSpan(hole) }
                    native.applyContentMetadataMutations(key,ContentMetadataMutations.setContentLength(ContentMetadataMutations(),4))
                }
                databaseFile=File(provider.readableDatabase.path)
            } finally { native.release(); provider.close() }
        }
        fun owner(close:(SQLiteDatabase)->Unit={ it.close() })=LegacyTransitionOwner(root,databaseFile,barrier,{present},closeDatabase=close)
        fun unchanged() { assertFalse(SimpleCache.isCacheFolderLocked(root)); originals.forEach { assertArrayEquals(payload,it.readBytes()) } }
    }
    @Test fun startupNeverOpensLegacyNativeCacheAndCloseRefusesAnActualReaderEvenAtEof() {
        val f=Fixture(70); val owner=f.owner(); val audio=owner.open()
        try {
            assertSame(audio,owner.open()); assertTrue(audio.contains("saved/0"))
            var keys=0; audio.forEachKey { keys++; true }; assertEquals(70,keys)
            val reader=audio.source.createDataSource()
            try {
                assertEquals(4L,reader.open(spec)); val bytes=ByteArray(4)
                assertEquals(4,reader.read(bytes,0,4)); assertArrayEquals(payload,bytes)
                assertEquals(-1,reader.read(ByteArray(1),0,1))
                assertThrows(IOException::class.java) { owner.close() }
                assertEquals(1,f.barrier.active)
            } finally { reader.close() }
            owner.close(); assertThrows(IOException::class.java) { audio.contains("saved/0") }
            assertThrows(IOException::class.java) { owner.open() }
            assertTrue(f.barrier.quiescent); f.unchanged()
        } finally { owner.close() }
    }
    @Test fun foreignNativeOwnerOrMalformedUidRefusesBeforeOpeningTheDatabase() {
        val f=Fixture(); var connections=0
        val provider=StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        val native=SimpleCache(f.root,NoOpCacheEvictor(),provider)
        try {
            native.checkInitialization()
            val owner=LegacyTransitionOwner(f.root,f.databaseFile,f.barrier,{true},{ connections++; error("must not open") })
            assertThrows(IOException::class.java) { owner.open() }; assertEquals(0,connections)
        } finally { native.release(); provider.close() }
        val marker=File(f.root,java.lang.Long.toHexString(f.uid)+".uid")
        val wrong=File(f.root,"00.uid"); assertTrue(marker.renameTo(wrong))
        try {
            val owner=LegacyTransitionOwner(f.root,f.databaseFile,f.barrier,{true},{ connections++; error("must not open") })
            assertThrows(IOException::class.java) { owner.open() }; assertEquals(0,connections)
            assertTrue(wrong.exists()); assertTrue(f.barrier.quiescent)
        } finally { assertTrue(wrong.renameTo(marker)) }
        f.unchanged()
    }
    @Test fun unknownSchemaRefusesWithoutRepairAndOwnerDoesNotRetry() {
        val f=Fixture(); val table="ExoPlayerCacheIndex"+java.lang.Long.toHexString(f.uid)
        SQLiteDatabase.openDatabase(f.databaseFile.path,null,SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("ALTER TABLE $table ADD COLUMN alien TEXT") }
        val originalDb=f.databaseFile.readBytes(); var opens=0
        val owner=LegacyTransitionOwner(f.root,f.databaseFile,f.barrier,{true},{ file ->
            opens++; SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY)
        })
        assertThrows(IOException::class.java) { owner.open() }
        assertThrows(IOException::class.java) { owner.open() }; assertEquals(1,opens)
        assertTrue(f.barrier.quiescent); assertArrayEquals(originalDb,f.databaseFile.readBytes()); f.unchanged()
    }
    @Test fun lostAvailabilityFailsPlaybackButKnownCloseKeepsOriginalAndAllowsFreshReader() {
        val f=Fixture(); val owner=f.owner(); val audio=owner.open(); val reader=audio.source.createDataSource()
        try {
            reader.open(spec); f.present=false
            assertThrows(IOException::class.java) { reader.read(ByteArray(1),0,1) }
            f.present=true; assertThrows(IOException::class.java) { reader.read(ByteArray(1),0,1) }
            reader.close(); reader.open(spec); reader.close(); owner.close(); f.unchanged()
        } finally { reader.close(); owner.close() }
    }
    @Test fun unknownActualDatabaseCloseQuarantinesExactConnectionWithoutRetry() {
        val f=Fixture(); var retained:SQLiteDatabase?=null; var closes=0
        val owner=f.owner { database -> retained=database; closes++; throw IOException("close left connection open") }
        owner.open()
        try {
            assertThrows(IOException::class.java) { owner.close() }; assertTrue(requireNotNull(retained).isOpen)
            assertThrows(IOException::class.java) { owner.close() }; assertEquals(1,closes)
            assertThrows(IOException::class.java) { f.barrier.shared() }
            assertThrows(IOException::class.java) { f.barrier.exclusive() }
            assertEquals(1,f.barrier.active); f.unchanged()
        } finally { retained?.close() } // Fixture-only actual teardown, no production reset.
    }
    @Test fun migrationUsesTheOwnedReadOnlyConnectionAndRequiresTheSameSessionGate() {
        val f=Fixture(); val owner=f.owner(); val legacy=owner.open()
        val provider=StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        val root=folders.newFolder(); val catalog=CachePartitionCatalog(root)
        val journal=CacheMigrationJournal(root); val saves=PartitionSaveJournal(root,create=true)
        val budget=PartitionNativeBudget(1)
        val native=PartitionNativeOwner(catalog,journal,"fixture",{"fixture"},budget=budget,saves=saves)
        fun session(gate:SavedStorageBarrier)=PartitionStorageSession(catalog,saves,journal,native,provider,"transition",
            legacy,DataSource.Factory { ByteArrayDataSource(payload) },{false},{true},gate)
        val wrong=session(SavedStorageBarrier()); val correct=session(f.barrier)
        try {
            assertThrows(IOException::class.java) { owner.migrate(wrong,CacheMigrationControl(1000,nanoTime={0L}),"saved/0",{Long.MAX_VALUE},{}) }
            assertNull(journal.find("saved/0")); assertEquals(0,budget.resident)
            val ready=owner.migrate(correct,CacheMigrationControl(1000,nanoTime={0L}),"saved/0",{Long.MAX_VALUE},{
                assertFalse(SimpleCache.isCacheFolderLocked(f.root))
                assertThrows(IOException::class.java) { f.barrier.shared() }
            })
            assertEquals(MigrationPhase.Ready,ready.phase)
            val reader=correct.audio.source.createDataSource()
            try {
                assertEquals(4L,reader.open(spec)); val bytes=ByteArray(4)
                assertEquals(4,reader.read(bytes,0,4)); assertArrayEquals(payload,bytes)
                assertThrows(IOException::class.java) { owner.close() }
            } finally { reader.close() }
            correct.close(); owner.close(); f.unchanged(); assertEquals(0,budget.resident)
        } finally {
            correct.close(); wrong.close(); owner.close(); saves.close(); journal.close(); catalog.close(); provider.close()
        }
    }
    @Test fun replacedUidMarkerFailsAnAlreadyOpenReaderRatherThanAdoptingAnotherOwner() {
        val f=Fixture(); val owner=f.owner(); val audio=owner.open(); val reader=audio.source.createDataSource()
        val marker=File(f.root,java.lang.Long.toHexString(f.uid)+".uid"); val parked=File(f.root,"parked-marker")
        try {
            reader.open(spec); assertTrue(marker.renameTo(parked)); assertTrue(marker.createNewFile())
            assertThrows(IOException::class.java) { reader.read(ByteArray(1),0,1) }
            reader.close()
        } finally { marker.delete(); assertTrue(parked.renameTo(marker)); owner.close() }
        f.unchanged()
    }
}
