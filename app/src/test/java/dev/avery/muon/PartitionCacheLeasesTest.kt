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
import java.io.IOException
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34],manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PartitionCacheLeasesTest {
    @get:Rule val folders=TemporaryFolder()
    private val database by lazy { StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()) }
    private val native=ArrayList<SimpleCache>()
    private val dirs=HashMap<String,File>() // Test fixture only, not production routing.
    private var live=0; private var maximum=0
    @After fun close() { try { native.asReversed().forEach { it.release() } } finally { database.close() } }
    private fun open(key:String):Cache {
        val actual=SimpleCache(dirs.getOrPut(key) { folders.newFolder() },NoOpCacheEvictor(),database).also {
            native+=it; it.checkInitialization()
        }
        if (!actual.isCached(key,0,1)) {
            val hole=requireNotNull(actual.startReadWriteNonBlocking(key,0,1))
            try { val f=actual.startFile(key,0,1); f.writeBytes(byteArrayOf(7)); actual.commitFile(f,1) }
            finally { actual.releaseHoleSpan(hole) }
        }
        live++; maximum=maxOf(maximum,live)
        return object:Cache by actual {
            override fun release() { actual.release(); live-- }
        }
    }
    @Test fun pinnedInstancesNeverEvictAndEveryOpenIncludingReuseStaysWithinCapacity() {
        val pool=PartitionCacheLeases(::open,capacity=2)
        val a=pool.acquire("a"); val duplicate=pool.acquire("a"); val b=pool.acquire("b")
        assertSame(a.cache,duplicate.cache); assertEquals(2,pool.resident); assertEquals(3,pool.active)
        assertThrows(PartitionCacheBusy::class.java) { pool.acquire("c") }
        a.close(); a.close(); assertEquals(2,pool.active)
        assertThrows(PartitionCacheBusy::class.java) { pool.acquire("c") }
        duplicate.close()
        pool.acquire("c").use { assertTrue(it.cache.isCached("c",0,1)) }
        assertTrue(b.cache.isCached("b",0,1)); b.close()
        repeat(20) { n -> pool.acquire("key$n").use { assertTrue(it.cache.isCached("key$n",0,1)) } }
        assertEquals(2,maximum); pool.close(); assertEquals(0,live)
        // Closing/reopening native caches retains every original span file.
        pool.close(); assertEquals(0,pool.resident)
        for ((key,dir) in dirs) {
            val reopened=SimpleCache(dir,NoOpCacheEvictor(),database)
            try { reopened.checkInitialization(); assertTrue(key,reopened.isCached(key,0,1)) }
            finally { reopened.release() }
        }
    }
    @Test fun shutdownNeverClosesActiveReadsAndOpeningCallbacksCannotOversubscribe() {
        lateinit var pool:PartitionCacheLeases
        pool=PartitionCacheLeases({ key ->
            assertEquals(1,pool.resident); assertThrows(PartitionCacheBusy::class.java) { pool.acquire(key) }
            open(key)
        },capacity=1)
        val pinned=pool.acquire("kept")
        pool.close(); assertEquals(1,live); assertTrue(pinned.cache.isCached("kept",0,1))
        assertThrows(IOException::class.java) { pool.acquire("new") }
        pinned.close(); assertEquals(0,live)
        val closing=PartitionCacheLeases({ key -> open(key) },capacity=1)
        closing.close(); assertThrows(IOException::class.java) { closing.acquire("never") }
    }
    @Test fun leaseBudgetAndFactoryFailureHaveNoUnboundedWaitOrLostSlots() {
        var fail=true
        val pool=PartitionCacheLeases({ key -> if (fail) { fail=false; throw IOException("open failed") } else open(key) },capacity=1,leaseLimit=2)
        assertThrows(IOException::class.java) { pool.acquire("kept") }; assertEquals(0,pool.resident); assertEquals(0,pool.active)
        val a=pool.acquire("kept"); val b=pool.acquire("kept")
        assertThrows(PartitionCacheBusy::class.java) { pool.acquire("kept") }
        assertThrows(IOException::class.java) { pool.acquire("x".repeat(MIGRATION_KEY_BYTES)) }
        b.close(); a.close(); pool.close(); assertEquals(0,live)
    }
    @Test fun openingReservationCountsWhileFactoryRunsAndShutdownRetiresItsReturnedInstance() {
        val entered=java.util.concurrent.CountDownLatch(1)
        val resume=java.util.concurrent.CountDownLatch(1)
        val result=java.util.concurrent.atomic.AtomicReference<Throwable>()
        val pool=PartitionCacheLeases({ key ->
            entered.countDown()
            check(resume.await(5,java.util.concurrent.TimeUnit.SECONDS))
            open(key)
        },capacity=1)
        val worker=Thread { try { pool.acquire("opening").close() } catch (failure:Throwable) { result.set(failure) } }
        worker.start()
        try {
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1,pool.resident); assertEquals(1,pool.active)
            assertThrows(PartitionCacheBusy::class.java) { pool.acquire("other") }
            pool.close(); assertEquals(1,pool.resident)
        } finally { resume.countDown(); worker.join(5000) }
        assertFalse(worker.isAlive); assertTrue(result.get() is IOException)
        assertEquals(0,pool.resident); assertEquals(0,pool.active); assertEquals(0,live)
    }
    @Test fun failedNativeReleaseCountsTheUnknownInstanceAndRevokesAllNewAdmission() {
        val pool=PartitionCacheLeases({ key ->
            val actual=open(key)
            object:Cache by actual { override fun release() { throw IOException("unknown close") } }
        },capacity=1)
        pool.acquire("preserved").close()
        assertThrows(IOException::class.java) { pool.acquire("new") }
        assertEquals(1,pool.resident); assertEquals(0,pool.active); assertEquals(1,live)
        assertThrows(IOException::class.java) { pool.acquire("preserved") }
        assertTrue(dirs.getValue("preserved").walkTopDown().any { it.name.endsWith(".v3.exo") })
        pool.close() // Never deletes files to recover the uncertain capacity.
    }
    @Test fun writerPinAllowsReadersButExcludesOtherWritersAndRemovalExcludesEveryReader() {
        val pool=PartitionCacheLeases(::open,capacity=1)
        val writer=pool.acquireWriter("a"); val reader=pool.acquire("a")
        assertSame(writer.cache,reader.cache)
        assertThrows(PartitionCacheBusy::class.java) { pool.acquireWriter("a") }
        assertThrows(PartitionCacheBusy::class.java) { pool.acquireWriter("a",exclusive=true) }
        writer.close()
        assertThrows(PartitionCacheBusy::class.java) { pool.acquireWriter("a",exclusive=true) }
        reader.close()
        val removal=pool.acquireWriter("a",exclusive=true)
        assertThrows(PartitionCacheBusy::class.java) { pool.acquire("a") }
        assertThrows(PartitionCacheBusy::class.java) { pool.acquireWriter("a") }
        removal.close(); pool.acquire("a").close(); pool.close(); assertEquals(0,pool.resident)
    }
    @Test fun quarantinedTaskRetainsItsPinAndHandlesAndStopsAdmissionWithoutForcedClosure() {
        val pool=PartitionCacheLeases(::open,capacity=1); val writer=pool.acquireWriter("a")
        writer.quarantine(Any()); writer.quarantine(Any())
        assertEquals(1,pool.active); assertEquals(1,pool.resident)
        assertThrows(IOException::class.java) { pool.acquire("b") }
        pool.close(); assertEquals(1,pool.resident); assertTrue(writer.cache.isCached("a",0,1))
        assertThrows(IOException::class.java) { writer.close() }; assertEquals(1,pool.active)
    }

    @Test fun cleanWriterRetirementIsAtomicAndCannotTakeAnotherReadersPin() {
        val pool=PartitionCacheLeases(::open,capacity=1)
        val writer=pool.acquireWriter("kept"); val reader=pool.acquire("kept")
        assertFalse(writer.tryRetireWriter()); assertEquals(2,pool.active); assertEquals(1,live)
        assertThrows(IOException::class.java) { reader.tryRetireWriter() }
        reader.close(); assertTrue(writer.tryRetireWriter())
        assertEquals(0,pool.active); assertEquals(0,pool.resident); assertEquals(0,live)
        assertThrows(IOException::class.java) { writer.tryRetireWriter() }
        pool.acquire("kept").use { assertTrue(it.cache.isCached("kept",0,1)) }
        pool.close(); assertEquals(0,live)
    }
    @Test fun closingWriterKeepsItsSlotAndBlocksReopenUntilActualNativeCloseReturns() {
        val entered=java.util.concurrent.CountDownLatch(1); val resume=java.util.concurrent.CountDownLatch(1)
        val pool=PartitionCacheLeases({ key ->
            val cache=open(key)
            object:Cache by cache {
                override fun release() { entered.countDown(); check(resume.await(5,java.util.concurrent.TimeUnit.SECONDS)); cache.release() }
            }
        },capacity=1)
        val writer=pool.acquireWriter("kept")
        val worker=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val result=worker.submit<Boolean> { writer.tryRetireWriter() }
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0,pool.active); assertEquals(1,pool.resident); assertEquals(1,live)
            assertThrows(PartitionCacheBusy::class.java) { pool.acquire("kept") }
            assertThrows(PartitionCacheBusy::class.java) { pool.acquire("other") }
            resume.countDown(); assertTrue(result.get(5,java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(0,pool.resident); assertEquals(0,live)
        } finally { resume.countDown(); worker.shutdownNow(); assertTrue(worker.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS)); pool.close() }
    }
    @Test fun uncertainWriterRetirementKeepsTheEntryAndNeverGrantsCompletionOrReadmission() {
        val pool=PartitionCacheLeases({ key ->
            val cache=open(key)
            object:Cache by cache { override fun release() { throw IOException("Unknown retirement") } }
        },capacity=1)
        val writer=pool.acquireWriter("kept")
        assertThrows(IOException::class.java) { writer.tryRetireWriter() }
        assertEquals(0,pool.active); assertEquals(1,pool.resident); assertEquals(1,live)
        assertThrows(IOException::class.java) { pool.acquire("kept") }
        assertThrows(IOException::class.java) { writer.tryRetireWriter() }
        pool.close(); assertTrue(dirs.getValue("kept").walkTopDown().any { it.name.endsWith(".exo") })
    }

}
