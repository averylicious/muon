@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.BitmapLoader
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real pinned session/provider caches, without posting a notification, preparing audio or a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class NotificationArtworkRevisionTest {
    private val endpoint = ServerEndpoint.parse("http://127.0.0.1:7814")
    private val old = TauonTrack(1, "Old", "Artist", "Album", 1000, true, false)
    private val replacement = old.copy(title = "Replacement")

    @After fun clearIdentity() { ArtworkIdentities.publish(emptyMap()) }

    @Test fun realProviderKeepsAPlainUriStaleButReloadsForARevisionAndThenCachesIt() {
        val context = RuntimeEnvironment.getApplication()
        val red = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val blue = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val requests = ArrayList<Uri>()
        val delegate = object : BitmapLoader {
            override fun supportsMimeType(mimeType: String) = true
            override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
                throw AssertionError("URI artwork expected")
            override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
                requests += uri
                return Futures.immediateFuture(if (requests.size == 1) red else blue)
            }
        }
        val player = ExoPlayer.Builder(context).build()
        // Match PlaybackService: its cache plus the session's and provider's own cache layers.
        val session = MediaSession.Builder(context, player).setBitmapLoader(CacheBitmapLoader(delegate)).build()
        val provider = DefaultMediaNotificationProvider.Builder(context).build()
        val actions = object : MediaNotification.ActionFactory {
            private val intent = PendingIntent.getBroadcast(context, 0, Intent("muon.test.artwork"), PendingIntent.FLAG_IMMUTABLE)
            override fun createMediaAction(session: MediaSession, icon: IconCompat, title: CharSequence, command: Int) =
                NotificationCompat.Action.Builder(icon, title, intent).build()
            override fun createCustomAction(session: MediaSession, icon: IconCompat, title: CharSequence,
                customAction: String, extras: Bundle): NotificationCompat.Action = throw AssertionError("No custom actions")
            override fun createCustomActionFromCustomCommandButton(session: MediaSession,
                button: CommandButton): NotificationCompat.Action = throw AssertionError("No custom buttons")
            override fun createMediaActionPendingIntent(session: MediaSession, command: Int) = intent
        }
        fun icon(item: MediaItem): Int {
            player.setMediaItem(item)
            val notification = provider.createNotification(session, ImmutableList.of(), actions) {
                throw AssertionError("The deterministic bitmap future is already complete")
            }.notification
            val drawable = requireNotNull(notification.getLargeIcon()).loadDrawable(context) as BitmapDrawable
            return drawable.bitmap.getPixel(0, 0)
        }
        fun plain(track: TauonTrack): MediaItem {
            val item = track.mediaItem(endpoint)
            return item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon()
                .setArtworkUri(Uri.parse(endpoint.url("/api1/pic/medium/1"))).build()).build()
        }
        try {
            assertEquals(Color.RED, icon(plain(old)))
            assertEquals(Color.RED, icon(plain(replacement))) // Reproduces the old app route.
            assertEquals(1, requests.size)
            assertEquals(Color.BLUE, icon(replacement.mediaItem(endpoint)))
            assertEquals(2, requests.size)
            assertEquals(Uri.parse(replacement.liveArtworkUrl(endpoint)), requests.last())
            assertEquals(Color.BLUE, icon(replacement.mediaItem(endpoint)))
            assertEquals("Repeated metadata must not refetch", 2, requests.size)
        } finally { session.release(); player.release(); red.recycle(); blue.recycle() }
    }

    @Test fun revisionsAreStableDisplayKeysAndNeverChangeTheAudioOrDownloadIdentity() {
        val original = old.mediaItem(endpoint)
        for (changed in listOf(replacement, old.copy(artist = "New artist"), old.copy(album = "New album"))) {
            val item = changed.mediaItem(endpoint)
            assertNotEquals(original.mediaMetadata.artworkUri, item.mediaMetadata.artworkUri)
            assertEquals(original.mediaId, item.mediaId)
            assertEquals(original.localConfiguration?.uri, item.localConfiguration?.uri)
        }
        assertEquals(original.mediaMetadata.artworkUri, old.copy(durationMs = 2000).mediaItem(endpoint).mediaMetadata.artworkUri)
        assertEquals("http://127.0.0.1:7814/api1/pic/medium/1", canonicalArtworkUrl(old.liveArtworkUrl(endpoint)))
        // Length framing keeps an embedded separator from aliasing a different field combination.
        assertNotEquals(old.copy(title = "a\u0000b", artist = "c").liveArtworkUrl(endpoint),
            old.copy(title = "a", artist = "b\u0000c").liveArtworkUrl(endpoint))
        val identities = artworkIdentities(endpoint, listOf(old))
        ArtworkIdentities.publish(identities)
        assertEquals(1, identities.size)
        val request = ArtworkIdentities.request(old.liveArtworkUrl(endpoint))
        assertEquals(ArtworkIdentities.request(endpoint.url("/api1/pic/medium/1")), request)
        assertEquals("kept", ArtworkIdentities.ifCurrent(request) { "kept" })
        ArtworkIdentities.publish(artworkIdentities(endpoint, listOf(replacement)))
        assertNull(ArtworkIdentities.ifCurrent(request) { "obsolete" })
    }

    @Test fun theRealNotificationHttpLoaderSendsNoRevisionFragmentToTauon() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/fixtures/notification-art-64.png")).use { it.readBytes() }
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 5000
        val requestLine = java.util.concurrent.CompletableFuture<String>()
        val worker = kotlin.concurrent.thread(isDaemon = true, name = "notification-art-request") {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val line = requireNotNull(reader.readLine())
                    while (!reader.readLine().isNullOrEmpty()) { /* Drain this client's headers. */ }
                    val output = socket.getOutputStream()
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${bytes.size}" +
                        "\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    output.write(bytes); output.flush()
                    requestLine.complete(line)
                }
            } catch (failure: Exception) { requestLine.completeExceptionally(failure) }
        }
        try {
            val address = ServerEndpoint.parse("http://127.0.0.1:${server.localPort}")
            val loader = notificationBitmapLoader(RuntimeEnvironment.getApplication(),
                androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(Transport.metadataClient))
            val bitmap = loader.loadBitmap(Uri.parse(replacement.liveArtworkUrl(address)))
                .get(10, java.util.concurrent.TimeUnit.SECONDS)
            try {
                assertEquals(64, bitmap.width)
                assertEquals("GET /api1/pic/medium/1 HTTP/1.1", requestLine.get(5, java.util.concurrent.TimeUnit.SECONDS))
            } finally { bitmap.recycle() }
        } finally { server.close(); worker.join(6000); assertFalse("Fixture did not drain", worker.isAlive) }
    }

    @Test fun canonicalizationLeavesSavedHandlesAndOtherFragmentsUntouched() {
        val saved = savedArtUrl("saved/entry")
        for (url in listOf(saved, "$saved#muon-artwork-v1=" + "a".repeat(64),
            endpoint.url("/api1/pic/medium/1") + "#other", old.liveArtworkUrl(endpoint) + "x",
            old.liveArtworkUrl(endpoint).replace("#muon-artwork-v1=", "#other#muon-artwork-v1="))) {
            assertEquals(url, canonicalArtworkUrl(url))
        }
    }
}
