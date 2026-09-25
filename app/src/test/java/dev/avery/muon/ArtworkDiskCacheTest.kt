package dev.avery.muon

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArtworkDiskCacheTest {
    @get:Rule val folder = TemporaryFolder()

    private var clock = 1_000_000_000_000L
    private fun cache(dir: File = folder.root, maxBytes: Long = 1_000, maxAgeMs: Long = 10_000) =
        ArtworkDiskCache(dir, maxBytes, maxAgeMs) { clock }
    private fun bytes(n: Int, fill: Int = 1) = ByteArray(n) { fill.toByte() }
    private val a = "http://192.168.1.10:7814/api1/pic/small/1"
    private val b = "http://192.168.1.10:7814/api1/pic/small/2"

    @Test fun aStoredPictureIsReadBackUntilItExpires() {
        val cache = cache()
        cache.write(a, bytes(10, 7))
        assertArrayEquals(bytes(10, 7), cache.read(a))
        assertNull(cache.read(b))
        // Survives a new cache over the same directory, as after a restart.
        assertArrayEquals(bytes(10, 7), cache().read(a))
    }

    @Test fun twoServersNeverShareAPicture() {
        val cache = cache()
        cache.write(a, bytes(10, 1))
        val sameTrackOtherServer = "http://192.168.1.20:7814/api1/pic/small/1"
        assertNull(cache.read(sameTrackOtherServer))
        assertNotEquals(ArtworkDiskCache.key(a), ArtworkDiskCache.key(sameTrackOtherServer))
    }

    @Test fun anExpiredPictureIsAMissAndIsRemoved() {
        val cache = cache(maxAgeMs = 10_000)
        cache.write(a, bytes(10))
        File(folder.root, ArtworkDiskCache.key(a)).setLastModified(clock)
        clock += 10_001
        assertNull(cache.read(a))
        assertFalse(File(folder.root, ArtworkDiskCache.key(a)).exists())
    }

    @Test fun rewritingAPictureReplacesIt() {
        val cache = cache()
        cache.write(a, bytes(10, 1))
        cache.write(a, bytes(20, 2))
        assertArrayEquals(bytes(20, 2), cache.read(a))
    }

    @Test fun passingTheCapDropsTheOldestFirst() {
        val cache = cache(maxBytes = 250)
        val urls = (1..3).map { "http://192.168.1.10:7814/api1/pic/small/$it" }
        urls.forEachIndexed { i, url ->
            cache.write(url, bytes(100))
            File(folder.root, ArtworkDiskCache.key(url)).setLastModified(clock + i * 1_000L)
        }
        // A fourth write pushes the total over 250 bytes: the oldest go until it fits.
        val fourth = "http://192.168.1.10:7814/api1/pic/small/4"
        cache.write(fourth, bytes(100))
        File(folder.root, ArtworkDiskCache.key(fourth)).setLastModified(clock + 3_000L)
        val kept = folder.root.listFiles()!!.filter { !it.name.endsWith(".tmp") }
        assertTrue(kept.sumOf { it.length() } <= 250)
        assertNull(cache.read(urls[0]))
        assertNotNull(cache.read(fourth))
    }

    @Test fun clearingForgetsEverything() {
        val cache = cache()
        cache.write(a, bytes(10))
        cache.write(b, bytes(10))
        cache.clear()
        assertNull(cache.read(a))
        assertNull(cache.read(b))
        assertEquals(0, folder.root.listFiles()!!.size)
    }

    @Test fun anUnusableDirectoryIsOnlyAMiss() {
        val notADirectory = folder.newFile("occupied")
        val cache = cache(dir = notADirectory)
        cache.write(a, bytes(10))
        assertNull(cache.read(a))
    }

    @Test fun keysAreFixedLengthFileNames() {
        val key = ArtworkDiskCache.key(a)
        assertEquals(64, key.length)
        assertTrue(key.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
