package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

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

    @Test fun theEstimatePreservesFractionalMillisecondsAcrossSongs() {
        assertEstimate(0, 0, -1, Long.MIN_VALUE)
        assertEstimate(1)
        assertEstimate(1, 1)
        assertEstimate(999, 1)
        assertEstimate(240_000, -1)
    }

    @Test fun largeEstimatesDoNotOverflowTheIntermediateProduct() {
        assertEstimate(Long.MAX_VALUE / OPUS_BYTES_PER_SECOND + 1)
        val lastMillisWhoseEstimateFits = Long.MAX_VALUE / 21 * 2
        assertEstimate(lastMillisWhoseEstimateFits)
        assertEstimate(lastMillisWhoseEstimateFits + 1)
        assertEstimate(lastMillisWhoseEstimateFits + 2)
    }

    @Test fun unrepresentableEstimatesAndDurationTotalsSaturate() {
        assertEstimate(Long.MAX_VALUE)
        assertEstimate(Long.MAX_VALUE, Long.MAX_VALUE)
        assertEstimate(Long.MAX_VALUE - 1, 1, 1)
    }

    private fun assertEstimate(vararg durations: Long) {
        // Independent exact-arithmetic oracle, including the old floor-after-summing semantics.
        val millis = durations.fold(BigInteger.ZERO) { sum, duration ->
            sum + BigInteger.valueOf(duration.coerceAtLeast(0))
        }
        val bytes = millis * BigInteger.valueOf(OPUS_BYTES_PER_SECOND) / BigInteger.valueOf(1000)
        val expected = bytes.min(BigInteger.valueOf(Long.MAX_VALUE)).toLong()
        val tracks = durations.mapIndexed { index, duration ->
            TauonTrack(index.toLong(), "", "", "", duration, true, false)
        }
        assertEquals(durations.contentToString(), expected, downloadEstimate(tracks))
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
    @Test fun ordinaryRecordsKeepTheirOriginalBytesAndRemainReadable() {
        val song = TauonTrack(7, "Song", "Artist", "Album", 235000, true, false, "Album Artist", "2/15")
        val old = listOf("muon-song-1", "7", "Song", "Artist", "Album", "Album Artist", "235000", "2/15")
            .joinToString("\u0000").toByteArray(Charsets.UTF_8)
        assertArrayEquals(old, encodeSong(song))
        assertEquals(song, decodeSong(old))
    }

    @Test fun nulInAnyTextFieldRoundTripsWithoutChangingOtherMetadata() {
        val song = TauonTrack(7, "Song", "Artist", "Album", 235000, true, false, "Album Artist", "2/15")
        val variants = listOf(song.copy(title = "Song\u0000other"), song.copy(artist = "Artist\u0000other"),
            song.copy(album = "Album\u0000other"), song.copy(albumArtist = "Album Artist\u0000other"),
            song.copy(trackNumber = "2\u0000/15"))
        variants.forEach { actual ->
            assertEquals("muon-song-2", String(encodeSong(actual), Charsets.UTF_8).substringBefore('\u0000'))
            assertEquals(actual, decodeSong(encodeSong(actual)))
        }
    }

    @Test fun escapedUnicodeEmptyTextAndLiteralBase64ArePreserved() {
        val song = TauonTrack(7, "Björk\u0000宇多田ヒカル🎵", "", "U29uZw==", 0, true, false, "", "")
        assertEquals(song, decodeSong(encodeSong(song)))
    }

    @Test fun malformedEscapedAndUnknownRecordsAreIgnored() {
        fun record(version: String, title: String) = listOf(version, "7", title, "", "", "", "0", "")
            .joinToString("\u0000").toByteArray(Charsets.UTF_8)
        assertNull(decodeSong(record("muon-song-2", "%%%")))
        assertNull(decodeSong(record("muon-song-3", "")))
        assertNull(decodeSong(listOf("muon-song-1", "7", "Old\u0000ambiguous", "", "", "", "0", "")
            .joinToString("\u0000").toByteArray(Charsets.UTF_8)))
    }

}
