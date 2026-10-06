package dev.avery.muon

import android.os.IBinder
import android.os.Parcel
import androidx.media3.common.BundleListRetriever
import androidx.media3.common.C
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SavedPresentationTest {
    private fun entry(song: TauonTrack) = SavedEntry(
        requireNotNull(SavedRef.download(SavedShelf.Phone, "legacy-request", "legacy-key")), song,
        null, androidx.media3.exoplayer.offline.Download.STATE_COMPLETED, SavedCoverage.Full, 10, false, false)

    @Test fun oversizedPersistedTagsKeepTheirRecordAndPlayableHandleWithSafeIpcText() {
        val song = TauonTrack(42, "T".repeat(700_000), "Artist", "Album", 1000, true, false)
        val bytes = encodeSong(song)
        val saved = entry(song)
        assertSame(song, saved.song)
        assertArrayEquals(bytes, encodeSong(requireNotNull(saved.song)))
        assertTrue(saved.complete)
        assertTrue(saved.metadataTooLarge)
        val item = saved.mediaItem()
        assertEquals(saved.ref.handle, item.mediaId)
        assertEquals(saved.ref.handle, requireNotNull(item.localConfiguration).uri.toString())
        assertEquals("Saved song (metadata too large)", item.mediaMetadata.title.toString())
        assertTrue(item.mediaMetadata.artist.toString().contains(UNVERIFIED))
        assertNull(item.mediaMetadata.albumTitle)
        assertNull(item.mediaMetadata.extras?.getByteArray(SONG_EXTRA))
        val retriever = BundleListRetriever(listOf(item.toBundle()))
        val method = BundleListRetriever::class.java.getDeclaredMethod("onTransact", Int::class.javaPrimitiveType,
            Parcel::class.java, Parcel::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            request.writeInt(0); request.setDataPosition(0)
            assertEquals(true, method.invoke(retriever, IBinder.FIRST_CALL_TRANSACTION, request, reply, 0))
            assertTrue(reply.dataSize() < C.SUGGESTED_MAX_IPC_SIZE)
        } finally { request.recycle(); reply.recycle() }
    }

    @Test fun safeStoredTagsRemainCompleteWhileEscapedExpansionCannotBypassTheDisplayGuard() {
        val ordinary = TauonTrack(42, "宇多田ヒカル 🎵", "Björk; Guest", "Album", 1000, true, false)
        val saved = entry(ordinary)
        assertFalse(saved.metadataTooLarge)
        assertEquals(ordinary.title, saved.title())
        assertEquals(ordinary.album, saved.mediaItem().mediaMetadata.albumTitle.toString())
        val unsafe = ordinary.copy(title = "\u0000".repeat(12_000))
        assertTrue(encodeSong(unsafe).size > TRACK_METADATA_MAX_BYTES)
        val kept = entry(unsafe)
        assertSame(unsafe, kept.song)
        assertTrue(kept.complete)
        assertTrue(kept.metadataTooLarge)
        assertTrue(kept.subtitle().contains("Metadata too large"))
    }
}
