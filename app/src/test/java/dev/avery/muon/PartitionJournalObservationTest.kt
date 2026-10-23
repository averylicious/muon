package dev.avery.muon

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PartitionJournalObservationTest {
    @get:Rule val folders = TemporaryFolder()
    private fun attributes(file: File) = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    private fun root() = folders.newFolder().also { dir ->
        PARTITION_BOOTSTRAP_JOURNALS.forEachIndexed { i, name -> File(dir, name).writeBytes(byteArrayOf(i.toByte())) }
    }
    private fun identities(root: File): Array<Any?> = PARTITION_BOOTSTRAP_JOURNALS.map { attributes(File(root, it)).fileKey() }.toTypedArray()
    private fun ordinary(raw: DirectoryStream<Path>) = object : DirectoryStream<Path> {
        override fun iterator() = raw.iterator()
        override fun close() = raw.close()
    }

    @Test fun freshSecureAndFallbackChildrenRejectEveryReplacementAndSameInodeLink() {
        for (fallback in listOf(false, true)) {
            val root = root(); val expected = identities(root); val key = requireNotNull(attributes(root).fileKey())
            val observer = PartitionJournalObservation(root, directories = { path ->
                val raw = Files.newDirectoryStream(path)
                if (fallback) ordinary(raw) else {
                    if (raw !is SecureDirectoryStream<*>) { raw.close(); fail("Host must exercise actual secure views") }
                    raw
                }
            }, onMode = { throw IllegalStateException("diagnostic sink failure") })
            observer.check(key, expected)
            for ((i, name) in PARTITION_BOOTSTRAP_JOURNALS.withIndex()) {
                val file = File(root, name); val retained = File(folders.newFolder(), "retained")
                assertTrue(file.renameTo(retained))
                try {
                    retained.copyTo(file)
                    assertThrows(IOException::class.java) { observer.check(key, expected) }
                    assertTrue(file.delete())
                    Files.createSymbolicLink(file.toPath(), retained.toPath())
                    // A link to the exact old inode is still forbidden, not merely a changed key.
                    assertThrows(IOException::class.java) { observer.check(key, expected) }
                    assertArrayEquals(byteArrayOf(i.toByte()), retained.readBytes())
                } finally { Files.deleteIfExists(file.toPath()); assertTrue(retained.renameTo(file)) }
                observer.check(key, expected)
            }
        }
    }

    @Test fun secureHandleMustMatchTheExpectedRootEvenWhenOtherChildrenAreValid() {
        val root = root(); val other = root(); val key = requireNotNull(attributes(root).fileKey())
        var closes = 0
        val observer = PartitionJournalObservation(root, directories = {
            Files.newDirectoryStream(other.toPath()).also {
                if (it !is SecureDirectoryStream<*>) { it.close(); fail("Host must exercise actual secure root view") }
            }
        }, closeHandle = { closes++; it.close() })
        assertThrows(IOException::class.java) { observer.check(key, identities(other)) }
        assertEquals(1, closes)
        PARTITION_BOOTSTRAP_JOURNALS.forEachIndexed { i, name ->
            assertArrayEquals(byteArrayOf(i.toByte()), File(root, name).readBytes())
            assertArrayEquals(byteArrayOf(i.toByte()), File(other, name).readBytes())
        }
    }

    @Test fun unknownCloseRetainsOneActualHandleAndNeverReopensOrRetriesIt() {
        val root = root(); val expected = identities(root); val key = requireNotNull(attributes(root).fileKey())
        var opens = 0; var closes = 0; var retained: Closeable? = null
        val observer = PartitionJournalObservation(root, directories = {
            opens++; Files.newDirectoryStream(it).also { retained = it }
        }, closeHandle = { closes++; throw IOException("injected unknown close") })
        try {
            assertThrows(MigrationIoUncertain::class.java) { observer.check(key, expected) }
            repeat(20) { assertThrows(IOException::class.java) { observer.check(key, expected) } }
            assertEquals(1, opens); assertEquals(1, closes)
            PARTITION_BOOTSTRAP_JOURNALS.forEachIndexed { i, name ->
                assertArrayEquals(byteArrayOf(i.toByte()), File(root, name).readBytes())
            }
        } finally { retained?.close() } // Isolated injected host fixture only, no production retry.
    }

    @Test fun concurrentChecksNeverOverlapOwnedDirectoryHandles() {
        val root = root(); val expected = identities(root); val key = requireNotNull(attributes(root).fileKey())
        val live = AtomicInteger(); val opens = AtomicInteger(); val closes = AtomicInteger()
        val observer = PartitionJournalObservation(root, directories = {
            assertEquals(1, live.incrementAndGet()); opens.incrementAndGet(); Files.newDirectoryStream(it)
        }, closeHandle = { it.close(); closes.incrementAndGet(); assertEquals(0, live.decrementAndGet()) })
        val pool = Executors.newFixedThreadPool(4); val start = CountDownLatch(1)
        try {
            val futures = (1..4).map { pool.submit(Callable {
                assertTrue(start.await(5, TimeUnit.SECONDS)); repeat(8) { observer.check(key, expected) }; true
            }) }
            start.countDown(); futures.forEach { assertTrue(it.get(15, TimeUnit.SECONDS)) }
            assertEquals(32, opens.get()); assertEquals(32, closes.get()); assertEquals(0, live.get())
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }
}
