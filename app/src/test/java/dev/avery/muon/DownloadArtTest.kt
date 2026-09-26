package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DownloadArtTest {
    @Test fun anArtworkAddressNamesItsSongsDownload() {
        assertEquals("http://192.168.1.10:7814/42", downloadIdForArtwork("http://192.168.1.10:7814/api1/pic/medium/42"))
        assertEquals("http://192.168.1.10:7814/42", downloadIdForArtwork("http://192.168.1.10:7814/api1/pic/small/42"))
    }

    @Test fun otherAddressesNameNothing() {
        assertNull(downloadIdForArtwork("http://192.168.1.10:7814/api1/file/42"))
        assertNull(downloadIdForArtwork("http://8.8.8.8:7814/api1/pic/medium/42"))
        assertNull(downloadIdForArtwork("http://192.168.1.10:7814/api1/pic/large/42"))
    }

    @Test fun aKeptCoverIsFoundByEitherSizesAddressAndRemovedWithItsDownload() {
        val dir = Files.createTempDirectory("art").toFile()
        val art = DownloadArt(dir)
        val id = "http://192.168.1.10:7814/42"
        assertNull(art.forArtwork("http://192.168.1.10:7814/api1/pic/small/42"))
        // Stored as fetch() would store it, without the network.
        val name = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        java.io.File(dir, name).writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(art.has(id))
        assertArrayEquals(byteArrayOf(1, 2, 3), art.forArtwork("http://192.168.1.10:7814/api1/pic/medium/42"))
        art.remove(id)
        assertFalse(art.has(id))
        dir.deleteRecursively()
    }
}
