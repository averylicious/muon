package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test

class OfflineDownloadsTest {
    @Test fun aDownloadIsKeyedAsThePlayerKeysTheSong() {
        val endpoint = ServerEndpoint.parse("192.168.1.10")
        // TauonTrack.mediaItem sets its media ID to the origin, a slash and the track ID.
        assertEquals("http://192.168.1.10:7814/42", downloadId(endpoint.origin, 42))
    }

    @Test fun onlyAnOriginalFileStreamIsRedirectedToItsDownload() {
        assertEquals("http://192.168.1.10:7814/42" to "http://192.168.1.10:7814/api1/fileopus/42",
            downloadForStream("http", "192.168.1.10:7814", "/api1/file/42"))
        assertNull(downloadForStream("http", "192.168.1.10:7814", "/api1/pic/medium/42"))
        assertNull(downloadForStream("http", "192.168.1.10:7814", "/api1/file/42/extra"))
        assertNull(downloadForStream("http", "8.8.8.8:7814", "/api1/file/42"))
    }

    @Test fun theEstimateIsEightyFourKilobitsPerSecond() {
        val fourMinutes = TauonTrack(1, "", "", "", 240_000, true, false)
        val unknown = TauonTrack(2, "", "", "", 0, true, false)
        assertEquals(2_520_000L, downloadEstimate(listOf(fourMinutes, unknown)))
    }

    @Test fun sizesReadAtAGlance() {
        assertEquals("900 kB", formatBytes(900_000))
        assertEquals("340 MB", formatBytes(340_200_000))
        assertEquals("1.1 GB", formatBytes(1_100_000_000))
    }

    @Test fun aPagesProgressCountsWhatIsDoneAndWhatIsComing() {
        val marks = mapOf("a" to DownloadMark.Done, "b" to DownloadMark.Downloading, "c" to DownloadMark.Queued)
        assertEquals(DownloadProgress(4, 1, 2), downloadProgress(listOf("a", "b", "c", "d"), marks))
        assertTrue(downloadProgress(listOf("a"), marks).all)
        assertFalse(downloadProgress(emptyList(), marks).all)
    }

    @Test fun aDownloadRemembersItsSongForTheOfflineLibrary() {
        val song = TauonTrack(7, "Hold On (with Georgia Ku)", "ILLENIUM; Georgia Ku", "ASCEND", 235_000, true, false,
            albumArtist = "ILLENIUM", trackNumber = "2/15")
        assertEquals(song, decodeSong(encodeSong(song)))
    }

    @Test fun aRecordThisVersionCannotReadIsLeftOut() {
        assertNull(decodeSong("Hold On".toByteArray()))
        assertNull(decodeSong(ByteArray(0)))
    }
}
