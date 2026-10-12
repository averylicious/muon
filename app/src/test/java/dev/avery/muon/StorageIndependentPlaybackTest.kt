@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
class StorageIndependentPlaybackTest {
    @Test fun liveRequestStreamsWithoutOpeningOrReadingAnySavedStorage() {
        var routes=0; var network=0
        val reader=OfflineDataSource(live=DataSource.Factory { network++; ByteArrayDataSource(byteArrayOf(2,5,8)) }) {
            routes++; throw IOException("Saved storage unavailable")
        }
        try {
            assertEquals(3L,reader.open(DataSpec.Builder().setUri("http://127.0.0.1/fixture").build()))
            val bytes=ByteArray(3); assertEquals(3,reader.read(bytes,0,3)); assertArrayEquals(byteArrayOf(2,5,8),bytes)
            assertEquals(0,routes); assertEquals(1,network)
        } finally { reader.close() }
    }
    @Test fun pendingSavedHandleIncludingMalformedHandleNeverFallsBackToLiveUpstream() {
        var network=0; var routes=0
        val reader=OfflineDataSource(live=DataSource.Factory { network++; ByteArrayDataSource(byteArrayOf(9)) }) {
            routes++; throw IOException("Saved storage still opening")
        }
        try {
            assertThrows(IOException::class.java) { reader.open(DataSpec.Builder().setUri("muon-saved:invalid").build()) }
            assertEquals(1,routes); assertEquals(0,network)
        } finally { reader.close() }
    }
}
