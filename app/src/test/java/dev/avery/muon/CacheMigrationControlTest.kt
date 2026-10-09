@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
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
import java.io.InterruptedIOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CacheMigrationControlTest {
    @get:Rule val folders=TemporaryFolder()
    private val database by lazy { StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()) }
    private val caches=ArrayList<SimpleCache>()
    @After fun close() { try { caches.asReversed().forEach { it.release() } } finally { database.close() } }
    private fun cache(file:File=folders.newFolder())=SimpleCache(file,NoOpCacheEvictor(),database).also { caches+=it; it.checkInitialization() }
    private fun seed(source:Cache,key:String):File {
        val bytes=ByteArray(150_000) { (it%127).toByte() }
        val hole=requireNotNull(source.startReadWriteNonBlocking(key,0,bytes.size.toLong()))
        try { val file=source.startFile(key,0,bytes.size.toLong()); file.writeBytes(bytes); source.commitFile(file,bytes.size.toLong()); return file }
        finally { source.releaseHoleSpan(hole) }
    }
    private fun fixture(block:(Cache,File,CachePartitionCatalog,CacheMigrationJournal,CacheMigrationPublication)->Unit) {
        val source=cache(); val original=seed(source,"saved"); val root=folders.newFolder()
        CachePartitionCatalog(root).use { catalog -> CacheMigrationJournal(root).use { journal ->
            block(source,original,catalog,journal,CacheMigrationPublication(catalog,journal,::cache))
        } }
    }
    @Test fun barrierAdapterExcludesActiveReaderBeforeReservationAndPublishesAfterDrain()=fixture { source,original,catalog,journal,publisher ->
        val barrier=SavedStorageBarrier(); val adapter=BarrierCacheMigration(barrier); val held=barrier.shared()
        assertThrows(IOException::class.java) {
            adapter.run(CacheMigrationControl(1000,nanoTime={0L}),source,"saved",publisher,{Long.MAX_VALUE},{})
        }
        assertEquals(0L,catalog.count()); held.close()
        val before=original.readBytes()
        val ready=adapter.run(CacheMigrationControl(1000,nanoTime={0L}),source,"saved",publisher,{Long.MAX_VALUE},{
            assertEquals(1,barrier.active)
            assertThrows(IOException::class.java) { barrier.shared() }
        })
        assertEquals(MigrationPhase.Ready,ready.phase); assertEquals(ready,journal.ready("saved"))
        assertTrue(barrier.quiescent); assertArrayEquals(before,original.readBytes())
    }
    @Test fun admitsExactSpacePublishesNativeReplacementAndKeepsOneScalarReceipt()=fixture { source,original,_,journal,publisher ->
        val before=original.readBytes(); val control=CacheMigrationControl(1000,headroomBytes=1024,nanoTime={0L})
        val ready=control.run(source,"saved",publisher,{151_024L},{})
        assertEquals(MigrationPhase.Ready,ready.phase); assertEquals(ready,journal.ready("saved"))
        assertEquals(MigrationWorkProgress(MigrationWorkPhase.Ready,150_000),control.progress)
        control.cancel(); assertEquals(MigrationWorkPhase.Ready,control.progress.phase)
        assertThrows(IOException::class.java) { control.run(source,"saved",publisher,{Long.MAX_VALUE},{}) }
        assertArrayEquals(before,original.readBytes())
    }
    @Test fun cancelledBeforeExecutionNeverReservesOrOpensTarget()=fixture { source,original,catalog,journal,publisher ->
        val before=original.readBytes(); val control=CacheMigrationControl(1000,nanoTime={0L}); control.cancel()
        assertThrows(InterruptedIOException::class.java) { control.run(source,"saved",publisher,{Long.MAX_VALUE},{}) }
        assertEquals(MigrationWorkPhase.Cancelled,control.progress.phase); assertEquals(0L,catalog.count()); assertNull(journal.find("saved"))
        assertArrayEquals(before,original.readBytes())
    }
    @Test fun unavailableOrInsufficientSpaceIsRefusedBeforeReservation()=fixture { source,original,catalog,journal,publisher ->
        val before=original.readBytes()
        for(free in listOf(-1L,0L,151_023L)) {
            val control=CacheMigrationControl(1000,headroomBytes=1024,nanoTime={0L})
            assertThrows(IOException::class.java) { control.run(source,"saved",publisher,{free},{}) }
            assertEquals(MigrationWorkPhase.Failed,control.progress.phase); assertEquals(0L,catalog.count()); assertNull(journal.find("saved"))
        }
        assertArrayEquals(before,original.readBytes())
    }
    @Test fun cancellationAfterCopyingKeepsUncertainTargetAndFreshRetryPublishesSeparately()=fixture { source,original,catalog,journal,publisher ->
        val before=original.readBytes(); val control=CacheMigrationControl(1000,headroomBytes=0,nanoTime={0L})
        assertThrows(InterruptedIOException::class.java) {
            control.run(source,"saved",publisher,{Long.MAX_VALUE},{
                if(journal.find("saved")?.phase==MigrationPhase.Verified) control.cancel()
            })
        }
        val stopped=requireNotNull(journal.find("saved")); assertEquals(MigrationPhase.Uncertain,stopped.phase)
        assertEquals(MigrationWorkPhase.Cancelled,control.progress.phase); assertNull(journal.ready("saved"))
        val retained=catalog.directory(stopped.ticket.allocation); assertTrue(retained.isDirectory)
        val ready=CacheMigrationControl(1000,headroomBytes=0,nanoTime={0L}).run(source,"saved",publisher,{Long.MAX_VALUE},{})
        assertNotEquals(stopped.ticket.allocation,ready.ticket.allocation); assertTrue(retained.isDirectory)
        assertArrayEquals(before,original.readBytes())
    }
    @Test fun deadlineOrLostBarrierStopsBeforePublicationWithoutDeletingOriginal()=fixture { source,original,_,journal,publisher ->
        val before=original.readBytes(); var clock=0L
        val timed=CacheMigrationControl(1,headroomBytes=0,nanoTime={clock})
        assertThrows(InterruptedIOException::class.java) { timed.run(source,"saved",publisher,{Long.MAX_VALUE},{
            if(journal.find("saved")?.phase==MigrationPhase.Copying) clock=1_000_000
        }) }
        assertEquals(MigrationWorkPhase.Expired,timed.progress.phase); assertNull(journal.ready("saved"))
        val barrier=CacheMigrationControl(1000,headroomBytes=0,nanoTime={0L})
        assertThrows(IOException::class.java) { barrier.run(source,"saved",publisher,{Long.MAX_VALUE},{
            if(journal.find("saved")?.phase==MigrationPhase.Copying) throw IOException("Lost source exclusion")
        }) }
        assertEquals(MigrationWorkPhase.Failed,barrier.progress.phase); assertNull(journal.ready("saved"))
        assertArrayEquals(before,original.readBytes())
    }
    @Test fun overflowAndUnsupportedBudgetsDoNotCreateTarget()=fixture { source,_,catalog,journal,publisher ->
        assertThrows(IllegalArgumentException::class.java) { CacheMigrationControl(0) }
        assertThrows(IllegalArgumentException::class.java) { CacheMigrationControl(Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { CacheMigrationControl(1,headroomBytes=-1) }
        val control=CacheMigrationControl(1000,headroomBytes=Long.MAX_VALUE,nanoTime={0L})
        assertThrows(IOException::class.java) { control.run(source,"saved",publisher,{Long.MAX_VALUE},{}) }
        assertEquals(0L,catalog.count()); assertNull(journal.find("saved"))
    }
    @Test fun cancelAndDeadlineCoverRealClosedLegacyProjectionBeforeTargetReservation() {
        for(cancel in listOf(true,false)) {
            val sourceRoot=folders.newFolder(); val source=cache(sourceRoot); val original=seed(source,"saved")
            val before=original.readBytes(); val uid=source.uid; source.release()
            val root=folders.newFolder(); var clock=0L; var checked=0; var opened=0
            val control=CacheMigrationControl(1000,nanoTime={clock})
            val barrier=SavedStorageBarrier()
            android.database.sqlite.SQLiteDatabase.openDatabase(database.readableDatabase.path,null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { read ->
                CachePartitionCatalog(root).use { catalog -> CacheMigrationJournal(root).use { journal ->
                    val publication=CacheMigrationPublication(catalog,journal,{ opened++; cache(it) })
                    assertThrows(InterruptedIOException::class.java) {
                        BarrierCacheMigration(barrier).runProjected(control,"saved",publication,{Long.MAX_VALUE},{}) { checkpoint ->
                            LegacyResourceProjection.read(sourceRoot,uid,"saved",read,{
                                checked++
                                if(checked==3) { if(cancel) control.cancel() else clock=1_000_000_000 }
                                checkpoint()
                            })
                        }
                    }
                    assertEquals(if(cancel) MigrationWorkPhase.Cancelled else MigrationWorkPhase.Expired,control.progress.phase)
                    assertEquals(3,checked); assertEquals(0,opened); assertEquals(0L,catalog.count())
                    assertNull(journal.find("saved")); assertTrue(barrier.quiescent)
                    assertArrayEquals(before,original.readBytes()); assertFalse(SimpleCache.isCacheFolderLocked(sourceRoot))
                } }
            }
        }
    }
    @Test fun priorCancellationSkipsPreparationAndProjectionWithoutQuarantiningKnownGate()=fixture { _,original,catalog,journal,publisher ->
        val control=CacheMigrationControl(1000,nanoTime={0L}); control.cancel()
        val barrier=SavedStorageBarrier(); var prepares=0; var projections=0
        assertThrows(InterruptedIOException::class.java) {
            BarrierCacheMigration(barrier,{prepares++}).runProjected(control,"saved",publisher,{Long.MAX_VALUE},{}) {
                projections++; throw AssertionError("Cancelled projection executed")
            }
        }
        assertEquals(0,prepares); assertEquals(0,projections); assertTrue(barrier.quiescent)
        assertEquals(MigrationWorkPhase.Cancelled,control.progress.phase); assertEquals(0L,catalog.count())
        assertNull(journal.find("saved")); assertTrue(original.isFile)
    }

    @Test fun unknownNativeCloseIsDistinctFromCancellationAndRetainsExclusionUntilProcessTeardown() {
        val source=cache(); val original=seed(source,"saved"); val before=original.readBytes()
        val root=folders.newFolder(); val actual=ArrayList<Cache>(); var closes=0; var opened=0
        CachePartitionCatalog(root).use { catalog -> CacheMigrationJournal(root).use { journal ->
            val control=CacheMigrationControl(1000,nanoTime={0L})
            val publication=CacheMigrationPublication(catalog,journal,{ directory ->
                val child=cache(directory).also(actual::add); opened++
                object:Cache by child {
                    override fun release() {
                        closes++; control.cancel()
                        throw IOException("Injected native close before release")
                    }
                }
            })
            val barrier=SavedStorageBarrier()
            assertThrows(IOException::class.java) {
                BarrierCacheMigration(barrier).run(control,source,"saved",publication,{Long.MAX_VALUE},{})
            }
            assertEquals(MigrationWorkPhase.Uncertain,control.progress.phase)
            assertTrue(publication.ownershipUncertain); assertEquals(1,closes); assertEquals(1,opened)
            assertEquals(1,barrier.active); assertThrows(IOException::class.java) { barrier.shared() }
            assertNull(journal.ready("saved")); assertEquals(MigrationPhase.Uncertain,journal.find("saved")?.phase)
            assertThrows(IOException::class.java) { publication.migrate(source,"saved",{}) }
            assertEquals(1,closes); assertEquals(1,opened); assertArrayEquals(before,original.readBytes())
            // Only disposable fixture teardown closes actual; production must not retry uncertain close.
            actual.forEach { it.release() }
        } }
    }
    @Test fun unknownSourceDirectoryCloseReportsUncertainBeforeAnyReservation()=fixture { _,original,catalog,journal,publisher ->
        val directory=folders.newFolder().toPath(); val actual=java.nio.file.Files.newDirectoryStream(directory)
        val ownership=MigrationIoOwnership(directories={
            object:java.nio.file.DirectoryStream<java.nio.file.Path> {
                override fun iterator()=actual.iterator()
                override fun close():Unit=throw IOException("Injected unknown directory close")
            }
        })
        try {
            val control=CacheMigrationControl(1000,nanoTime={0L}); val barrier=SavedStorageBarrier()
            assertThrows(MigrationIoUncertain::class.java) {
                BarrierCacheMigration(barrier).runProjected(control,"saved",publisher,{Long.MAX_VALUE},{}) {
                    ownership.directory(directory).close(); throw AssertionError("Unknown close swallowed")
                }
            }
            assertEquals(MigrationWorkPhase.Uncertain,control.progress.phase)
            assertFalse(ownership.quiescent); assertEquals(1,barrier.active)
            assertEquals(0L,catalog.count()); assertNull(journal.find("saved")); assertTrue(original.isFile)
        } finally { actual.close() }
    }

}
