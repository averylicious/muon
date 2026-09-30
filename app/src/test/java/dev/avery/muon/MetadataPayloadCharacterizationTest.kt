package dev.avery.muon

import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.Parcel
import androidx.media3.common.BundleListRetriever
import androidx.media3.common.C
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** #253: actual serialization bounds, not a device Binder-failure or heap benchmark. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MetadataPayloadCharacterizationTest {
    private val endpoint = ServerEndpoint.parse("http://192.168.1.10:7814")
    private fun song(title: String) = TauonTrack(42, title, "Artist", "Album", 180_000, true, false)

    @Test fun ordinarySongFitsOneReplyAndRetainsOfflineRecord() {
        val track = song("Ordinary title")
        val item = track.mediaItem(endpoint)
        assertEquals(track, decodeSong(requireNotNull(item.mediaMetadata.extras?.getByteArray(SONG_EXTRA))))
        val reply = retrieverReply(item.toBundle())
        try {
            assertTrue(reply.dataSize() < C.SUGGESTED_MAX_IPC_SIZE)
            reply.setDataPosition(0)
            assertEquals(1, reply.readInt()) // REPLY_CONTINUE
            val restored = androidx.media3.common.MediaItem.fromBundle(requireNotNull(reply.readBundle()))
            assertEquals(track.title, restored.mediaMetadata.title.toString())
            assertEquals(track, decodeSong(requireNotNull(restored.mediaMetadata.extras?.getByteArray(SONG_EXTRA))))
            assertEquals(0, reply.readInt()) // REPLY_END_OF_LIST
        } finally { reply.recycle() }
    }

    @Test fun oneLargeMetadataItemExceedsRetrieverBudgetDespiteListChunking() {
        // Well below Muon's16MiB wire cap; one flat title, not excessive JSON nesting.
        val track = song("L".repeat(700_000))
        val item = track.mediaItem(endpoint)
        val record = requireNotNull(item.mediaMetadata.extras?.getByteArray(SONG_EXTRA))
        assertEquals(track, decodeSong(record))
        val reply = retrieverReply(item.toBundle())
        try {
            // Current Media3 checks size BEFORE writing an entire item, not inside that item.
            assertTrue(reply.dataSize() > C.SUGGESTED_MAX_IPC_SIZE)
            assertTrue(reply.dataSize() > 1_048_576)
            println("MUON_PAYLOAD_FIXTURE mediaItem titleChars=${track.title.length} songBytes=${record.size} replyBytes=${reply.dataSize()}")
            reply.setDataPosition(0)
            assertEquals(1, reply.readInt())
            val restored = androidx.media3.common.MediaItem.fromBundle(requireNotNull(reply.readBundle()))
            assertEquals(track.title, restored.mediaMetadata.title.toString())
            assertArrayEquals(record, restored.mediaMetadata.extras?.getByteArray(SONG_EXTRA))
            assertEquals(0, reply.readInt())
        } finally { reply.recycle() }
    }

    @Test fun largeOfflineRecordIsWrittenWholeIntoActualDownloadServiceIntent() {
        val track = song("D".repeat(1_100_000))
        val record = encodeSong(track)
        val id = downloadId(endpoint.origin, track.id)
        val request = DownloadRequest.Builder(id, Uri.parse(endpoint.url("/api1/fileopus/${track.id}")))
            .setCustomCacheKey(id).setData(record).build()
        val intent = DownloadService.buildAddDownloadIntent(RuntimeEnvironment.getApplication(),
            MuonDownloadService::class.java, request, false)
        val parcel = Parcel.obtain()
        try {
            intent.writeToParcel(parcel, 0)
            assertTrue(parcel.dataSize() > 1_048_576)
            println("MUON_PAYLOAD_FIXTURE download titleChars=${track.title.length} songBytes=${record.size} intentBytes=${parcel.dataSize()}")
            parcel.setDataPosition(0)
            val restored = Intent.CREATOR.createFromParcel(parcel)
            restored.setExtrasClassLoader(DownloadRequest::class.java.classLoader)
            val restoredRequest = requireNotNull(restored.getParcelableExtra<DownloadRequest>(DownloadService.KEY_DOWNLOAD_REQUEST))
            assertArrayEquals(record, restoredRequest.data)
            assertEquals(track, decodeSong(restoredRequest.data))
        } finally { parcel.recycle() }
    }

    private fun retrieverReply(bundle: android.os.Bundle): Parcel {
        val retriever = BundleListRetriever(listOf(bundle))
        val method = BundleListRetriever::class.java.getDeclaredMethod("onTransact",
            Int::class.javaPrimitiveType, Parcel::class.java, Parcel::class.java, Int::class.javaPrimitiveType)
        method.isAccessible = true
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            request.writeInt(0)
            request.setDataPosition(0)
            assertEquals(true, method.invoke(retriever, IBinder.FIRST_CALL_TRANSACTION, request, reply, 0))
            return reply
        } catch (failure: Throwable) {
            reply.recycle()
            throw failure
        } finally { request.recycle() }
    }
}
