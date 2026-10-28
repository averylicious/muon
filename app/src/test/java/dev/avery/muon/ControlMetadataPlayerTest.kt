@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.BitmapLoader
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** #738: controller metadata projection over the actual pinned SimpleBasePlayer/session machinery.
 * In-process, hermetic (no network, no artwork I/O): no Binder transaction, cross-process controller
 * or phone. Platform-session readback is Robolectric's, not a system_server or OEM controller. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ControlMetadataPlayerTest {
    /** A raw producer built on the real SimpleBasePlayer state/listener machinery. */
    private class Producer(private var current: SimpleBasePlayer.State) : SimpleBasePlayer(Looper.getMainLooper()) {
        override fun getState(): SimpleBasePlayer.State = current
        override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
        fun publish(next: SimpleBasePlayer.State) { current = next; invalidateState() }
    }

    /** Every real session uses this: artwork can never trigger file or network I/O in these tests. */
    private object NoArtwork : BitmapLoader {
        override fun supportsMimeType(mimeType: String) = false
        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
            Futures.immediateFailedFuture(IOException("No artwork decoding in tests"))
        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
            Futures.immediateFailedFuture(IOException("No artwork loading in tests"))
    }

    private val composer = "C".repeat(2 * 1024 * 1024) // The only large value; others stay small.
    private val artwork = byteArrayOf(1, 2, 3)
    private fun raw(composer: String = this.composer, description: String = "Stream description") = MediaMetadata.Builder()
        .setTitle("Stream title").setArtist("Stream artist").setAlbumTitle("Stream album").setAlbumArtist("Stream album artist")
        .setDisplayTitle("Stream display").setSubtitle("Stream subtitle").setDescription(description)
        .setWriter("Stream writer").setAuthor("Stream author").setComposer(composer).setConductor("Stream conductor")
        .setDiscSubtitle("Stream disc").setGenre("Stream genre").setCompilation("Stream compilation").setStation("Stream station")
        .setSupportedCommands(listOf("stream.command")).setExtras(Bundle().apply { putString("stream", "x") })
        .setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER).setTrackNumber(7).build()
    private fun queued(id: String, title: String) = MediaItem.Builder().setMediaId(id).setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setArtist("Artist").setArtworkUri(Uri.parse("http://h/pic/$id"))
            .setExtras(Bundle().apply { putString("song", id) }).build()).build()
    private val tracks = Tracks(listOf(Tracks.Group(TrackGroup(Format.Builder().setLabel("stream label")
        .setSampleMimeType(MimeTypes.AUDIO_RAW).build()), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))))
    private fun state(item: MediaItem?, metadata: MediaMetadata = raw()): SimpleBasePlayer.State {
        val builder = SimpleBasePlayer.State.Builder().setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
        if (item == null) return builder.setPlaylist(emptyList<SimpleBasePlayer.MediaItemData>()).build()
        return builder.setPlaylist(listOf(SimpleBasePlayer.MediaItemData.Builder(item.mediaId).setMediaItem(item)
            .setMediaMetadata(metadata).setTracks(tracks).build())).setCurrentMediaItemIndex(0).build()
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun pump(done: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do { idle(); if (done()) return; Thread.sleep(1) } while (System.nanoTime() < until)
        fail("Real session did not settle")
    }
    private fun parcelBytes(bundle: Bundle): Int {
        val parcel = Parcel.obtain()
        return try { parcel.writeBundle(bundle); parcel.dataSize() } finally { parcel.recycle() }
    }

    @Test fun initialUpdatedAndEmptyStatesCarryOnlyTheQueuedSongMetadata() {
        val metadata = raw()
        val producer = Producer(state(queued("q1", "Queued"), metadata))
        val player = ControlMetadataPlayer(producer)
        try {
            val projected = player.mediaMetadata
            assertEquals("Queued", projected.title.toString()); assertEquals("Artist", projected.artist.toString())
            assertNull("An unknown queued album must not fall back to stream text", projected.albumTitle)
            assertNull("An unknown queued album artist must not fall back to stream text", projected.albumArtist)
            for (field in listOf(projected.displayTitle, projected.subtitle, projected.description, projected.writer,
                projected.author, projected.composer, projected.conductor, projected.discSubtitle, projected.genre,
                projected.compilation, projected.station)) assertNull(field)
            assertTrue(projected.supportedCommands.isEmpty())
            assertEquals("q1", projected.extras?.getString("song")); assertNull(projected.extras?.getString("stream"))
            assertEquals(Uri.parse("http://h/pic/q1"), projected.artworkUri)
            assertSame("Embedded artwork is shared, not copied", metadata.artworkData, projected.artworkData)
            assertEquals(7, projected.trackNumber)
            // The raw producer, its Tracks (and their Format) are unchanged.
            assertEquals(composer, producer.mediaMetadata.composer.toString())
            assertEquals("Stream writer", producer.mediaMetadata.writer.toString())
            assertSame(producer.currentTracks, player.currentTracks)
            assertEquals("stream label", player.currentTracks.groups.single().getTrackFormat(0).label)
            assertTrue(parcelBytes(metadata.toBundle()) > 1024 * 1024)
            assertTrue(parcelBytes(projected.toBundle()) < 16 * 1024)

            val seen = ArrayList<MediaMetadata>()
            player.addListener(object : Player.Listener {
                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) { seen += mediaMetadata }
            })
            // Only unused stream text changed: controllers see no metadata change at all.
            producer.publish(state(queued("q1", "Queued"), raw(composer = "Other", description = "Other")))
            idle()
            assertTrue(seen.isEmpty())
            producer.publish(state(queued("q2", "Second")))
            idle()
            assertEquals("Second", seen.single().title.toString()); assertNull(seen.single().composer)
            assertEquals("Second", player.mediaMetadata.title.toString())
            producer.publish(state(null))
            idle()
            assertEquals(MediaMetadata.EMPTY, player.mediaMetadata)
        } finally { player.release() }
    }

    @Test fun aRealSessionPublishesTheProjectionToControllersAndThePlatformSession() {
        val context = RuntimeEnvironment.getApplication()
        val producer = Producer(state(queued("q1", "Queued")))
        val player = ControlMetadataPlayer(producer)
        val session = MediaSession.Builder(context, player).setBitmapLoader(NoArtwork).build()
        val connection = MediaController.Builder(context, session.token).buildAsync()
        try {
            pump { connection.isDone }
            val connected = connection.get(1, TimeUnit.SECONDS)
            assertEquals("Queued", connected.mediaMetadata.title.toString())
            assertNull(connected.mediaMetadata.composer); assertNull(connected.mediaMetadata.author)
            // Public platform controller over the legacy session, which Media3 fills from this same player.
            val platform = android.media.session.MediaController(context, session.platformToken)
            fun text(key: String) = platform.metadata?.getString(key)
            pump { text(android.media.MediaMetadata.METADATA_KEY_TITLE) == "Queued" }
            assertEquals("Artist", text(android.media.MediaMetadata.METADATA_KEY_ARTIST))
            for (key in listOf(android.media.MediaMetadata.METADATA_KEY_COMPOSER, android.media.MediaMetadata.METADATA_KEY_WRITER,
                android.media.MediaMetadata.METADATA_KEY_AUTHOR, android.media.MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION,
                android.media.MediaMetadata.METADATA_KEY_GENRE)) assertNull(key, text(key))

            producer.publish(state(queued("q2", "Second")))
            pump { connected.mediaMetadata.title?.toString() == "Second" }
            assertNull(connected.mediaMetadata.composer); assertNull(connected.mediaMetadata.description)
            pump { text(android.media.MediaMetadata.METADATA_KEY_TITLE) == "Second" }
            assertNull(text(android.media.MediaMetadata.METADATA_KEY_COMPOSER))
            assertNull(text(android.media.MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION))
        } finally { MediaController.releaseFuture(connection); session.release(); player.release() }
    }

    @Test fun queueGuardsTransportModesAndReleaseStillReachTheRealExoPlayer() {
        val native = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        var refusals = 0
        val player = ControlMetadataPlayer(PlaybackQueuePlayer(native, SavedQueueLimits(2, 10_000)) { refusals++ })
        fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri("http://h/$id")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Song $id").build()).build()
        fun refused(action: () -> Unit) {
            try { action(); fail("The wrapped queue guard must still refuse") } catch (_: PlaybackQueueLimit) { }
        }
        try {
            player.setMediaItems(listOf(item("a"), item("b")))
            idle()
            refused { player.addMediaItem(item("c")) }
            // Refused from its count alone: the wrapper must not copy or inspect a huge incoming list.
            val huge = object : AbstractList<MediaItem>() {
                override val size get() = Int.MAX_VALUE
                override fun get(index: Int): MediaItem = throw AssertionError("Must not inspect huge input")
            }
            refused { player.setMediaItems(huge) }
            refused { player.addMediaItems(huge) }
            idle()
            assertEquals(listOf("a", "b"), (0 until native.mediaItemCount).map { native.getMediaItemAt(it).mediaId })
            assertEquals(3, refusals); assertEquals(2, player.mediaItemCount)
            assertEquals("Song a", player.mediaMetadata.title.toString())
            player.shuffleModeEnabled = true; player.repeatMode = Player.REPEAT_MODE_ALL
            idle()
            assertTrue(native.shuffleModeEnabled); assertEquals(Player.REPEAT_MODE_ALL, native.repeatMode)
            player.release()
            idle()
            assertTrue(native.isReleased)
        } finally { try { player.release() } finally { if (!native.isReleased) native.release() } }
    }

    @Test fun queueRemovalUndoThroughTheWrappedSessionRestoresTheShuffledDuplicateSlot() {
        val context = RuntimeEnvironment.getApplication()
        val native = ExoPlayer.Builder(context).build()
        val endpoint = ServerEndpoint.parse("http://192.168.1.20:7814") // Private LAN; never prepared.
        fun song(id: Long) = TauonTrack(id, "Fixture $id", "Fixture artist", "Fixture album", 3000L, true, false).mediaItem(endpoint)
        var session: MediaSession? = null
        var connection: ListenableFuture<MediaController>? = null
        var removal: QueueRemovalUndo? = null
        var projected: ControlMetadataPlayer? = null
        try {
            native.setMediaItems(listOf(1L, 2L, 2L, 3L).map(::song), 0, 0L)
            native.setShuffleOrder(QueueShuffleOrder.restored(listOf(0, 2, 3, 1)))
            native.shuffleModeEnabled = true
            // The production stack: the session player is the wrapper, not an ExoPlayer.
            val controlled = ControlMetadataPlayer(PlaybackQueuePlayer(native)).also { projected = it }
            val made = MediaSession.Builder(context, controlled)
                .setBitmapLoader(NoArtwork).setCallback(PlaybackSessionCallback(shufflePlayer = native)).build()
            session = made
            val pending = MediaController.Builder(context, made.token).buildAsync()
            connection = pending
            pump { pending.isDone }
            val p = pending.get(1, TimeUnit.SECONDS)
            val ids = (0 until native.mediaItemCount).map { native.getMediaItemAt(it).mediaId }
            val order = queueShuffledOrder(native)
            val removed = p.getMediaItemAt(2) // A duplicate occurrence; index 0 is playing.
            val result = QueueRemovalUndo.remove(p, 2, removed)
            pump { result.isDone }
            removal = requireNotNull(result.get(1, TimeUnit.SECONDS))
            pump { native.mediaItemCount == 3 }
            val undo = requireNotNull(removal).undo()
            pump { undo.isDone }
            assertTrue(undo.get(1, TimeUnit.SECONDS))
            pump { native.mediaItemCount == 4 }
            assertEquals(ids, (0 until native.mediaItemCount).map { native.getMediaItemAt(it).mediaId })
            assertEquals(removed.mediaMetadata.title, native.getMediaItemAt(2).mediaMetadata.title)
            assertEquals(order, queueShuffledOrder(native))
            assertTrue(native.shuffleModeEnabled); assertEquals(0, native.currentMediaItemIndex)
        } finally {
            removal?.close()
            connection?.let { MediaController.releaseFuture(it) }
            session?.release()
            try { projected?.release() } finally { if (!native.isReleased) native.release() }
        }
    }
}
