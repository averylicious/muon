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

    @Test fun linkedCoverFilesAndDirectoriesAreNotReadFetchedOrRemoved() {
        for(kind in listOf("file", "directory", "parent", "empty-directory")) {
            val root=Files.createTempDirectory("cover-linked").toFile()
            try {
                val outside=java.io.File(root,"outside").apply {mkdirs()}
                val id="saved/disposable"
                val name="entry-"+java.security.MessageDigest.getInstance("SHA-256")
                    .digest("muon-saved-entry\u0000$id".toByteArray()).joinToString("") {"%02x".format(it)}
                val bytes=byteArrayOf(8,7,6)
                val original=java.io.File(outside,name)
                if(kind!="empty-directory") original.writeBytes(bytes)
                val link=java.io.File(root,"linked")
                val dir=when(kind) {
                    "file" -> java.io.File(root,"art").apply {
                        mkdirs();Files.createSymbolicLink(java.io.File(this,name).toPath(),original.toPath())
                    }
                    "parent" -> {
                        val nested=java.io.File(outside,"art").apply {mkdirs()}
                        original.renameTo(java.io.File(nested,name))
                        Files.createSymbolicLink(link.toPath(),outside.toPath())
                        java.io.File(link,"art")
                    }
                    else -> {Files.createSymbolicLink(link.toPath(),outside.toPath());link}
                }
                var requests=0
                val art=DownloadArt(dir,okhttp3.Call.Factory { requests++;throw java.io.IOException("No network") })
                assertFalse(kind,art.hasEntry(id))
                assertNull(kind,art.forEntry(id))
                art.fetchEntry(id,"http://127.0.0.1:1/fixture-no-network")
                assertEquals(kind,0,requests)
                assertThrows(java.io.IOException::class.java) {art.removeEntry(id)}
                if(kind=="parent") assertArrayEquals(bytes,java.io.File(outside,"art/$name").readBytes())
                else if(kind!="empty-directory") assertArrayEquals(bytes,original.readBytes())
                else assertTrue(outside.listFiles()!!.isEmpty())
                assertTrue(kind,Files.isSymbolicLink(if(kind=="file") java.io.File(dir,name).toPath() else link.toPath()))
            } finally {
                // The walk does not follow links, even in cleanup of these disposable fixtures.
                Files.walk(root.toPath()).use { paths -> paths.sorted(java.util.Comparator.reverseOrder()).forEach(Files::delete) }
            }
        }
    }

    @Test fun aSavedCoverAddressNamesOnlyItsEntry() {
        val entry = "saved/2f1c3a8e-0f4e-4b8e-9b0e-6c1d2e3f4a5b"
        assertEquals(entry, savedArtRequest(savedArtUrl(entry)))
        assertNull(savedArtRequest("http://192.168.1.10:7814/api1/pic/medium/42"))
        assertNull(savedArtRequest("$SAVED_ART_SCHEME:not base64!"))
        assertNull(savedArtRequest(savedArtUrl(entry) + "="))
    }
}
