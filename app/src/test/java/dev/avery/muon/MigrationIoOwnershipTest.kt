@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
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
class MigrationIoOwnershipTest {
    @get:Rule val folders=TemporaryFolder()
    private val database by lazy { StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()) }
    private val natives=mutableListOf<SimpleCache>()
    private val controls=mutableListOf<Fixture>()
    private val rawOutputs=mutableListOf<MoveFileOutput>()
    private val uncommittedOutputFiles=mutableListOf<File>()
    private val owned=mutableListOf<Cache>()
    private inner class Fixture {
        val root=folders.newFolder()
        val catalog=CachePartitionCatalog(root)
        val journal=CacheMigrationJournal(root)
        val budget=PartitionNativeBudget(1)
        val owner=PartitionNativeOwner(catalog,journal,"card",{"card"},budget=budget)
        val source=SimpleCache(folders.newFolder(),NoOpCacheEvictor(),database).also { natives+=it; it.checkInitialization() }
        val key="saved/resource"
        val payload=ByteArray(150_000) { (it%127).toByte() }
        val original:File
        init {
            val hole=requireNotNull(source.startReadWrite(key,0,payload.size.toLong()))
            try { original=source.startFile(key,0,payload.size.toLong()); original.writeBytes(payload); source.commitFile(original,payload.size.toLong()) }
            finally { source.releaseHoleSpan(hole) }
        }
        fun opener()=owner.migrationTarget(key).let { factory -> { file:File -> factory(file).also(owned::add) } }
    }
    private fun fixture()=Fixture().also(controls::add)
    @After fun close() {
        // Test-only recovery of deliberately retained disposable raw handles before native cleanup.
        // Production has no such retry/reset authority and retains the slot until explicit recovery.
        try {
            rawOutputs.forEach { try { it.close() } catch(_:IOException) {} }
            uncommittedOutputFiles.forEach { it.delete() } // Disposable injected staging, after real close above.
            owned.asReversed().forEach { it.release() }
            controls.asReversed().forEach { it.journal.close(); it.catalog.close() }
        } finally { try { natives.asReversed().forEach { it.release() } } finally { database.close() } }
    }
    @Test fun unknownOutputCloseRetainsRawHandleAndNativeSlotAndStopsFurtherAdmission() {
        val f=fixture(); var closeCalls=0; var opens=0
        val outputs=MoveFileOutputs { file ->
            uncommittedOutputFiles+=file
            val raw=MoveFileOutputs.Real.open(file).also(rawOutputs::add)
            object:MoveFileOutput by raw { override fun close() { closeCalls++; throw IOException("Injected unknown output close") } }
        }
        val factory=f.opener()
        val runner=CacheMigrationPublication(f.catalog,f.journal,{ opens++; factory(it) },outputs=outputs)
        val failure=assertThrows(MigrationIoUncertain::class.java) { runner.migrate(f.source,f.key,{}) }
        assertFalse(failure.ownership.quiescent); assertEquals(1,closeCalls); assertEquals(1,f.budget.resident)
        assertTrue(uncommittedOutputFiles.single().isFile) // Do not unlink an output with unknown closure.
        assertEquals(MigrationPhase.Uncertain,f.journal.find(f.key)?.phase); assertNull(f.journal.ready(f.key))
        assertThrows(IOException::class.java) { runner.migrate(f.source,f.key,{}) }; assertEquals(1,opens)
        assertArrayEquals(f.payload,f.original.readBytes())
    }
    @Test fun migrationAdapterRetainsBorrowedSourceExclusionOnUnknownFileClose() {
        val f=fixture(); val barrier=SavedStorageBarrier()
        val outputs=MoveFileOutputs { file ->
            uncommittedOutputFiles+=file
            val raw=MoveFileOutputs.Real.open(file).also(rawOutputs::add)
            object:MoveFileOutput by raw { override fun close():Unit=throw IOException("Injected unknown output") }
        }
        val publisher=CacheMigrationPublication(f.catalog,f.journal,f.opener(),outputs=outputs)
        assertThrows(MigrationIoUncertain::class.java) {
            BarrierCacheMigration(barrier).run(CacheMigrationControl(1000,nanoTime={0L}),f.source,f.key,publisher,{Long.MAX_VALUE},{})
        }
        assertEquals(1,barrier.active); assertEquals(1,f.budget.resident)
        assertThrows(IOException::class.java) { barrier.shared() }
        assertArrayEquals(f.payload,f.original.readBytes())
    }
    @Test fun unknownInputCloseDuringCopyCannotReleaseNativeTargetOrRetryTheRawClose() {
        val f=fixture(); var closeCalls=0; var opens=0
        val inputs:(File)->RandomAccessFile={ file ->
            // Even a close that throws after the platform close cannot be assumed successful.
            object:RandomAccessFile(file,"r") {
                override fun close() { closeCalls++; super.close(); throw IOException("Injected uncertain reader close") }
            }
        }
        val factory=f.opener()
        val runner=CacheMigrationPublication(f.catalog,f.journal,{opens++; factory(it)},inputs=inputs)
        assertThrows(MigrationIoUncertain::class.java) { runner.migrate(f.source,f.key,{}) }
        assertEquals(1,closeCalls); assertEquals(1,opens); assertEquals(1,f.budget.resident)
        assertThrows(IOException::class.java) { runner.migrate(f.source,f.key,{}) }; assertEquals(1,closeCalls)
        assertNull(f.journal.ready(f.key)); assertArrayEquals(f.payload,f.original.readBytes())
    }
    @Test fun unknownReaderCloseDuringReopenedVerificationKeepsTheReopenedNativeSlot() {
        val f=fixture(); var inputCount=0; var opens=0; var closeFailures=0
        val inputs:(File)->RandomAccessFile={ file ->
            inputCount++
            if(inputCount==5) object:RandomAccessFile(file,"r") {
                override fun close() { closeFailures++; super.close(); throw IOException("Injected verification close") }
            } else RandomAccessFile(file,"r")
        }
        val factory=f.opener()
        val runner=CacheMigrationPublication(f.catalog,f.journal,{ opens++; factory(it) },inputs=inputs)
        assertThrows(MigrationIoUncertain::class.java) { runner.migrate(f.source,f.key,{}) }
        assertEquals(2,opens); assertEquals(1,closeFailures); assertEquals(1,f.budget.resident)
        assertEquals(MigrationPhase.Uncertain,f.journal.find(f.key)?.phase)
        assertThrows(IOException::class.java) { runner.migrate(f.source,f.key,{}) }; assertEquals(2,opens)
        assertArrayEquals(f.payload,f.original.readBytes())
    }
    @Test fun suppressedReentrantCloseCannotEraseUnknownHandleOrRetireNativeSlot() {
        val f=fixture(); var closeCalls=0
        lateinit var runner:CacheMigrationPublication
        val outputs=MoveFileOutputs { file ->
            uncommittedOutputFiles+=file
            val raw=MoveFileOutputs.Real.open(file).also(rawOutputs::add)
            object:MoveFileOutput by raw {
                override fun close() {
                    closeCalls++
                    // Reenter publication while closing; it must not start a second native attempt.
                    assertThrows(IOException::class.java) { runner.migrate(f.source,f.key,{}) }
                    throw IOException("Injected callback close remains unknown")
                }
            }
        }
        runner=CacheMigrationPublication(f.catalog,f.journal,f.opener(),outputs=outputs)
        assertThrows(MigrationIoUncertain::class.java) { runner.migrate(f.source,f.key,{}) }
        assertEquals(1,closeCalls); assertEquals(1,f.budget.resident)
        assertEquals(MigrationPhase.Uncertain,f.journal.find(f.key)?.phase)
        assertArrayEquals(f.payload,f.original.readBytes())
    }
    @Test fun sameHandleReentrantCloseSuppressedByCallbackStaysUncertain() {
        lateinit var tracker:MigrationIoOwnership
        lateinit var raw:MoveFileOutput
        var calls=0
        val path=folders.newFile()
        val output=MoveFileOutputs.Real.open(path).also(rawOutputs::add)
        raw=object:MoveFileOutput by output {
            override fun close() {
                calls++
                assertThrows(MigrationIoUncertain::class.java) { tracker.close(raw) }
                assertFalse(tracker.quiescent)
                assertThrows(IOException::class.java) { tracker.output(folders.newFile()) }
                // Intentionally suppress the callback refusal and return normally.
            }
        }
        tracker=MigrationIoOwnership(MoveFileOutputs { raw })
        val tracked=tracker.output(path)
        assertThrows(MigrationIoUncertain::class.java) { tracked.close() }
        assertFalse(tracker.quiescent); assertEquals(1,calls)
        assertThrows(MigrationIoUncertain::class.java) { tracked.close() }; assertEquals(1,calls)
    }
    @Test fun knownClosedWriteFailureRetiresNativeTargetAndAllowsFreshAttempt() {
        val f=fixture(); var fail=true
        val outputs=MoveFileOutputs { file ->
            val raw=MoveFileOutputs.Real.open(file)
            object:MoveFileOutput by raw {
                override fun write(buffer:ByteArray,offset:Int,length:Int) {
                    if(fail) { fail=false; throw IOException("Injected write failure with known cleanup") }
                    raw.write(buffer,offset,length)
                }
            }
        }
        var factory=f.opener()
        val runner=CacheMigrationPublication(f.catalog,f.journal,{factory(it)},outputs=outputs)
        assertThrows(IOException::class.java) { runner.migrate(f.source,f.key,{}) }
        assertEquals(0,f.budget.resident); val old=requireNotNull(f.journal.find(f.key)).ticket.allocation
        // Distinct factory per retry is required by the existing native owner's attempt contract.
        factory=f.opener()
        val ready=runner.migrate(f.source,f.key,{})
        assertEquals(MigrationPhase.Ready,ready.phase); assertNotEquals(old,ready.ticket.allocation)
        assertEquals(0,f.budget.resident); assertArrayEquals(f.payload,f.original.readBytes())
    }
}
