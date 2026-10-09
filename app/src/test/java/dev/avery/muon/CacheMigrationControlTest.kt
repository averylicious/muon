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
}
