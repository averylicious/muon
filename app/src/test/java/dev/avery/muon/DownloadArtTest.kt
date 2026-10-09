package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/** Covers a new save owns (#213): named by its own request ID, never by a track number. */
class DownloadArtTest {
    @Test fun anEntrysCoverIsFoundOnlyByItsOwnIdAndRemovedWithIt() {
        val dir = Files.createTempDirectory("art").toFile()
        try {
            val art = DownloadArt(dir)
            val entry = "saved/2f1c3a8e-0f4e-4b8e-9b0e-6c1d2e3f4a5b"
            val other = "saved/7d9e0a1b-2c3d-4e5f-8a6b-7c8d9e0f1a2b"
            assertFalse(art.hasEntry(entry))
            // Stored as fetchEntry would store it, without the network.
            val name = "entry-" + java.security.MessageDigest.getInstance("SHA-256")
                .digest("muon-saved-entry\u0000$entry".toByteArray()).joinToString("") { "%02x".format(it) }
            java.io.File(dir, name).writeBytes(byteArrayOf(1, 2, 3))
            assertTrue(art.hasEntry(entry))
            assertArrayEquals(byteArrayOf(1, 2, 3), art.forEntry(entry))
            assertNull("Another entry does not adopt it", art.forEntry(other))
            art.removeEntry(other)
            assertTrue("Removing another entry leaves it", art.hasEntry(entry))
            art.removeEntry(entry)
            assertFalse(art.hasEntry(entry))
        } finally { dir.deleteRecursively() }
    }

    @Test fun anOlderPerTrackCoverIsNeitherShownNorDeleted() {
        val dir = Files.createTempDirectory("art").toFile()
        try {
            val art = DownloadArt(dir)
            val live = "http://192.168.1.10:7814/42"
            // How the previous app kept a cover: by the track number's ID alone, shared by every copy of it.
            val legacy = java.io.File(dir, java.security.MessageDigest.getInstance("SHA-256").digest(live.toByteArray())
                .joinToString("") { "%02x".format(it) }).apply { writeBytes(byteArrayOf(7, 7)) }
            assertFalse(art.hasEntry(live))
            assertNull(art.forEntry(live))
            art.removeEntry(live)
            assertArrayEquals(byteArrayOf(7, 7), legacy.readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test fun aSavedCoverAddressNamesOnlyItsEntry() {
        val entry = "saved/2f1c3a8e-0f4e-4b8e-9b0e-6c1d2e3f4a5b"
        assertEquals(entry, savedArtRequest(savedArtUrl(entry)))
        assertNull(savedArtRequest("http://192.168.1.10:7814/api1/pic/medium/42"))
        assertNull(savedArtRequest("$SAVED_ART_SCHEME:not base64!"))
        assertNull(savedArtRequest(savedArtUrl(entry) + "="))
    }
}
