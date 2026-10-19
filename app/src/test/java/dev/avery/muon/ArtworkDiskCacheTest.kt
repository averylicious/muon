package dev.avery.muon

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class ArtworkDiskCacheTest {
    @get:Rule val folder = TemporaryFolder()

    private var clock = 1_000_000_000_000L
    private fun cache(dir: File = folder.root, maxBytes: Long = 1_000, maxAgeMs: Long = 10_000, maxEntries: Int = ARTWORK_DISK_ENTRIES) =
        ArtworkDiskCache(dir, maxBytes, maxAgeMs, maxEntries) { clock }
    private fun bytes(n: Int, fill: Int = 1) = ByteArray(n) { fill.toByte() }
    private val a = "http://192.168.1.10:7814/api1/pic/small/1"
    private val b = "http://192.168.1.10:7814/api1/pic/small/2"
    private val song = "Title\u0000Artist\u0000Album"

    @Test fun aStoredPictureIsReadBackUntilItExpires() {
        val cache = cache()
        cache.write(a, song, bytes(10, 7))
        assertArrayEquals(bytes(10, 7), cache.read(a, song))
        assertNull(cache.read(b, song))
        // Survives a new cache over the same directory, as after a restart.
        assertArrayEquals(bytes(10, 7), cache().read(a, song))
    }

    @Test fun twoServersNeverShareAPicture() {
        val cache = cache()
        cache.write(a, song, bytes(10, 1))
        val sameTrackOtherServer = "http://192.168.1.20:7814/api1/pic/small/1"
        assertNull(cache.read(sameTrackOtherServer, song))
        assertNotEquals(ArtworkDiskCache.key(a, song), ArtworkDiskCache.key(sameTrackOtherServer, song))
    }

    @Test fun anExpiredPictureIsAMissAndIsRemoved() {
        val cache = cache(maxAgeMs = 10_000)
        cache.write(a, song, bytes(10))
        File(folder.root, ArtworkDiskCache.key(a, song)).setLastModified(clock)
        clock += 10_001
        assertNull(cache.read(a, song))
        assertFalse(File(folder.root, ArtworkDiskCache.key(a, song)).exists())
    }

    @Test fun rewritingAPictureReplacesIt() {
        val cache = cache()
        cache.write(a, song, bytes(10, 1))
        cache.write(a, song, bytes(20, 2))
        assertArrayEquals(bytes(20, 2), cache.read(a, song))
    }

    @Test fun passingTheCapDropsTheOldestFirst() {
        val cache = cache(maxBytes = 250)
        val urls = (1..3).map { "http://192.168.1.10:7814/api1/pic/small/$it" }
        urls.forEachIndexed { i, url ->
            cache.write(url, song, bytes(100))
            File(folder.root, ArtworkDiskCache.key(url, song)).setLastModified(clock + i * 1_000L)
        }
        // A fourth write pushes the total over 250 bytes: the oldest go until it fits.
        val fourth = "http://192.168.1.10:7814/api1/pic/small/4"
        cache.write(fourth, song, bytes(100))
        File(folder.root, ArtworkDiskCache.key(fourth, song)).setLastModified(clock + 3_000L)
        val kept = folder.root.listFiles()!!.filter { !it.name.endsWith(".tmp") }
        assertTrue(kept.sumOf { it.length() } <= 250)
        assertNull(cache.read(urls[0], song))
        assertNotNull(cache.read(fourth, song))
    }

    @Test fun clearingForgetsEverything() {
        val cache = cache()
        cache.write(a, song, bytes(10))
        cache.write(b, song, bytes(10))
        cache.clear()
        assertNull(cache.read(a, song))
        assertNull(cache.read(b, song))
        assertEquals(0, folder.root.listFiles()!!.size)
    }

    @Test fun anUnusableDirectoryIsOnlyAMiss() {
        val notADirectory = folder.newFile("occupied")
        val cache = cache(dir = notADirectory)
        cache.write(a, song, bytes(10))
        assertNull(cache.read(a, song))
    }

    @Test fun anOversizedDerivedEntryIsAMissWithoutLosingOtherCoversAndCanBeReplaced() {
        val cache = cache(maxBytes = ARTWORK_DISK_ENTRY_BYTES * 3)
        cache.write(b, song, bytes(10, 7))
        val boundary = bytes(ARTWORK_DISK_ENTRY_BYTES.toInt(), 2)
        cache.write(a, song, boundary)
        assertArrayEquals(boundary, cache.read(a, song))
        // Simulate an older/corrupted cache entry without constructing its oversized payload.
        val file = File(folder.root, ArtworkDiskCache.key(a, song))
        RandomAccessFile(file, "rw").use { it.setLength(ARTWORK_DISK_ENTRY_BYTES + 1) }
        assertNull(cache.read(a, song))
        assertArrayEquals(bytes(10, 7), cache.read(b, song))
        cache.write(a, song, bytes(20, 3))
        assertArrayEquals(bytes(20, 3), cache.read(a, song))
        // A rejected oversized write must not replace a good cache entry or leave a temp file.
        cache.write(a, song, bytes(ARTWORK_DISK_ENTRY_BYTES.toInt() + 1, 4))
        assertArrayEquals(bytes(20, 3), cache.read(a, song))
        assertEquals(2, folder.root.listFiles()!!.size)
    }

    @Test fun restartBoundsManyTinyEntriesAndKeepsNewestWithoutRemovingAnActiveTemporary() {
        val urls = (1..40).map { "http://example.test/art/$it" }
        urls.forEachIndexed { i, url ->
            File(folder.root, ArtworkDiskCache.key(url, song)).apply {
                writeBytes(bytes(2, i)); assertTrue(setLastModified(clock + i))
            }
        }
        val stale = File(folder.root, "abandoned.tmp").apply {
            writeBytes(bytes(1)); assertTrue(setLastModified(clock - 60_001))
        }
        val active = File(folder.root, "active.tmp").apply {
            writeBytes(bytes(1)); assertTrue(setLastModified(clock))
        }
        val cache = cache(maxEntries = 3)
        cache.write(a, song, bytes(3, 7))
        assertArrayEquals(bytes(3, 7), cache.read(a, song))
        assertNotNull(cache.read(urls[39], song)); assertNotNull(cache.read(urls[38], song))
        assertNull(cache.read(urls[37], song)); assertNull(cache.read(urls[0], song))
        assertEquals(3, folder.root.listFiles()!!.count { !it.name.endsWith(".tmp") })
        assertFalse(stale.exists()); assertArrayEquals(bytes(1), active.readBytes())
        // Once initialized, count admission must still run even though the byte cap is far away.
        cache.write(b, song, bytes(3, 8))
        assertArrayEquals(bytes(3, 8), cache.read(b, song))
        assertNull(cache.read(urls[38], song))
        assertEquals(3, folder.root.listFiles()!!.count { !it.name.endsWith(".tmp") })
    }

    @Test fun clearingStreamsEntriesWithoutTraversingALinkOrAnUnexpectedDirectory() {
        val dir = folder.newFolder("derived")
        val outside = folder.newFolder("outside")
        val original = File(outside, "original").apply { writeBytes(bytes(5, 9)) }
        java.nio.file.Files.createSymbolicLink(File(dir, "link").toPath(), outside.toPath())
        val nested = File(dir, "unexpected").apply { mkdir() }
        val nestedFile = File(nested, "keep").apply { writeBytes(bytes(4, 6)) }
        val cache = cache(dir = dir)
        cache.write(a, song, bytes(3))
        cache.clear()
        assertNull(cache.read(a, song))
        assertFalse(java.nio.file.Files.exists(File(dir, "link").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertArrayEquals(bytes(5, 9), original.readBytes())
        assertArrayEquals(bytes(4, 6), nestedFile.readBytes())
    }

    @Test fun keysAreFixedLengthFileNames() {
        val key = ArtworkDiskCache.key(a, song)
        assertEquals(64, key.length)
        assertTrue(key.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
