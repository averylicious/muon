package dev.avery.muon

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #253 inventory: which representations of accepted library metadata main keeps, through the actual
 * HTTP/platform-JSON path ([TauonApi]) and the existing load and projection helpers. Counts, equality
 * and object identity only. Not a heap or time measurement, an IPC failure, a phone result or a limit.
 * Two expressions are mirrored rather than called, because they live in the view model and composable:
 * `LibraryModel.allTracks` (flatten, then distinct by id) and `startQueue`/`playAll`'s queue mapping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LibraryRetentionCharacterizationTest {
    @Test fun aWideBoundedPlaylistIsKeptWholeAndAWholeLibraryQueueCarriesEveryRecord() {
        val count = 10_000
        val body = tracklist((1..count).map { song(it.toLong(), "Song $it", "Artist ${it % 400}", "Album ${it % 1_500}") })
        val responseBytes = body.toByteArray(Charsets.UTF_8).size
        // Wide, but far below the 16 MiB wire cap: the cap is not what bounds this.
        assertTrue(responseBytes < 16 * 1024 * 1024)
        Server(mapOf("/api1/tracklist/1" to body)).use { server ->
            val tracks = runBlocking { server.api.tracks("1") }
            assertEquals((1..count).map { it.toLong() }, tracks.map { it.id })
            val load = combineLoad(listOf(TauonPlaylist("1", "Wide", count)), mapOf("1" to tracks), null)
            assertSame(tracks, load.tracks["1"])
            // MuonApp.startQueue/playAll: one MediaItem per playable song, each holding its whole record.
            val queue = tracks.filter { it.playable }.map { it.mediaItem(server.endpoint) }
            assertEquals(count, queue.size)
            var recordBytes = 0L
            queue.forEachIndexed { i, item ->
                val record = requireNotNull(item.mediaMetadata.extras?.getByteArray(SONG_EXTRA))
                assertEquals(tracks[i], decodeSong(record))
                recordBytes += record.size
            }
            println("MUON_RESOURCE_FIXTURE wide tracks=$count responseBytes=$responseBytes queueItems=${queue.size} queueRecordBytes=$recordBytes")
        }
    }

    @Test fun aSongInSeveralPlaylistsIsKeptOncePerOccurrenceAndAFailedRefreshKeepsTheOldLists() {
        val playlists = JSONObject().put("playlists", JSONArray(listOf(playlist("1"), playlist("2"), playlist("3")))).toString()
        val shared = song(7, "Shared", "Artist", "Album")
        val routes = mapOf(
            "/api1/playlists" to playlists,
            // Repeated inside one playlist and across all three: every occurrence is legitimate.
            "/api1/tracklist/1" to tracklist(listOf(shared, song(8, "Eight", "Artist", "Album"), shared)),
            "/api1/tracklist/2" to tracklist(listOf(song(9, "Nine", "Other", "Other album"), shared)),
            "/api1/tracklist/3" to tracklist(listOf(shared)),
        )
        val first = Server(routes).use { server -> load(server.api, null) }
        assertEquals(0, first.failed)
        val occurrences = first.tracks.values.flatten().filter { it.id == 7L }
        assertEquals(4, occurrences.size)
        // Equal, but each parse made its own track and its own tag strings.
        assertTrue(occurrences.all { it == occurrences[0] })
        assertEquals(4, identityCount(occurrences))
        assertEquals(4, identityCount(occurrences.map { it.title }))
        // LibraryModel.allTracks keeps the first occurrence; grouping keeps that same object.
        val unique = first.tracks.values.flatten().distinctBy { it.id }
        assertEquals(listOf(7L, 8L, 9L), unique.map { it.id })
        assertSame(first.tracks.getValue("1")[0], unique[0])
        assertSame(unique[0], groupAlbums(unique).first { album -> album.tracks.any { it.id == 7L } }.tracks.single { it.id == 7L })
        assertSame(unique[0], groupArtists(unique).first { artist -> artist.tracks.any { it.id == 7L } }.tracks.single { it.id == 7L })

        // A refresh where playlist 2 fails: its previous list object stays, beside newly parsed lists.
        val refreshed = Server(routes + ("/api1/tracklist/2" to null)).use { server ->
            load(server.api, first.tracks).also { assertTrue("GET /api1/tracklist/2 HTTP/1.1" in server.requests) }
        }
        assertEquals(1, refreshed.failed)
        assertEquals(listOf("1", "2", "3"), refreshed.playlists.map { it.id })
        assertSame(first.tracks.getValue("2"), refreshed.tracks.getValue("2"))
        assertNotSame(first.tracks.getValue("1"), refreshed.tracks.getValue("1"))
        // Both generations' copies of song 7 are now held by one snapshot.
        val mixed = refreshed.tracks.values.flatten().filter { it.id == 7L }
        assertEquals(4, mixed.size)
        assertTrue(mixed.any { it === first.tracks.getValue("2")[1] })
        assertTrue(mixed.none { it === first.tracks.getValue("1")[0] })
        println("MUON_RESOURCE_FIXTURE repeated playlists=3 occurrences=${occurrences.size} unique=${unique.size} keptAfterFailedRefresh=${refreshed.tracks.getValue("2").size}")
    }

    @Test fun aLongAcceptedTagIsCopiedIntoEachRetainedRepresentation() {
        // About 12 KB: accepted by main, and meant to stay under #258's proposed 16 KiB per-record guard
        // (open, not on main; its final rule is not checked here). Mixed case, so lowercasing copies it.
        val album = "Long Album Title ".repeat(706).trim()
        val albumArtist = "Album Artist"
        assertTrue(album.length > 12_000)
        val members = (1L..4L).map { song(it, "Part $it", "Performer", album, albumArtist) }
        val routes = mapOf(
            "/api1/playlists" to JSONObject().put("playlists", JSONArray(listOf(playlist("1"), playlist("2")))).toString(),
            "/api1/tracklist/1" to tracklist(members),
            "/api1/tracklist/2" to tracklist(members.take(2)),
        )
        Server(routes).use { server ->
            val occurrences = load(server.api, null).tracks.values.flatten()
            assertEquals(6, occurrences.size)
            assertTrue(occurrences.all { it.album == album })
            // One string per parsed occurrence, not one per song or per album.
            assertEquals(6, identityCount(occurrences.map { it.album }))

            val unique = occurrences.distinctBy { it.id }
            val albums = groupAlbums(unique)
            assertEquals(1, albums.size)
            assertEquals(album, albums[0].title)
            // The grouping key holds a lowercased copy of the album and album artist.
            assertTrue(albums[0].key.contains(album.lowercase()))
            assertTrue(albums[0].key.length > album.length)

            // The artwork identities published for the library: one concatenated copy per song.
            val identities = artworkIdentities(server.endpoint, unique)
            assertEquals(4, identities.size)
            assertTrue(identities.values.all { it.endsWith("\u0000$album") })

            // Queued: each item's metadata shows the album, and its record holds another encoded copy.
            val queue = unique.map { it.mediaItem(server.endpoint) }
            val records = queue.map { requireNotNull(it.mediaMetadata.extras?.getByteArray(SONG_EXTRA)) }
            assertTrue(queue.all { it.mediaMetadata.albumTitle.toString() == album })
            assertTrue(records.all { decodeSong(it)?.album == album && it.size > album.length })
            println("MUON_RESOURCE_FIXTURE longTag chars=${album.length} occurrences=${occurrences.size} albumKeyChars=${albums[0].key.length} " +
                "identityChars=${identities.values.sumOf { it.length }} queueRecordBytes=${records.sumOf { it.size }}")
        }
    }

    /** The view model's load: playlists, then each playlist on its own, then [combineLoad]. */
    private fun load(api: TauonApi, previous: Map<String, List<TauonTrack>>?): LibraryLoad = runBlocking {
        val lists = api.playlists()
        val loaded = linkedMapOf<String, List<TauonTrack>>()
        lists.forEach { list -> runCatching { api.tracks(list.id) }.onSuccess { loaded[list.id] = it } }
        combineLoad(lists, loaded, previous)
    }

    private fun identityCount(items: List<Any>): Int =
        Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()).apply { addAll(items) }.size

    private fun playlist(id: String) = JSONObject().put("id", id).put("name", "Playlist $id").put("count", 1)

    private fun song(id: Long, title: String, artist: String, album: String, albumArtist: String? = null): JSONObject =
        JSONObject().put("id", id).put("title", title).put("artist", artist).put("album", album)
            .put("duration", 180_000).put("can_download", true).put("has_lyrics", false)
            .apply { if (albumArtist != null) put("album_artist", albumArtist) }

    private fun tracklist(songs: List<JSONObject>): String = JSONObject().put("tracks", JSONArray(songs)).toString()

    /** Serves each path's body (null: HTTP 503), one connection per request, until closed. */
    private class Server(private val routes: Map<String, String?>) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        private val closing = AtomicBoolean(false)
        val requests = CopyOnWriteArrayList<String>()
        val endpoint = ServerEndpoint.parse("http://127.0.0.1:${socket.localPort}")
        val api = TauonApi(endpoint)
        private val task = executor.submit {
            try {
                while (true) socket.accept().use { client ->
                    client.soTimeout = 5000
                    val input = client.getInputStream().bufferedReader(Charsets.UTF_8)
                    val requestLine = input.readLine().orEmpty()
                    requests += requestLine
                    while (true) if (input.readLine().isNullOrEmpty()) break
                    val path = requestLine.split(' ').getOrNull(1)
                    val body = routes[path]
                    val bytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                    val status = when {
                        body != null -> "200 OK"
                        path in routes -> "503 Service Unavailable"
                        else -> "404 Not Found"
                    }
                    client.getOutputStream().apply {
                        write(("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        write(bytes)
                        flush()
                    }
                }
            } catch (failure: SocketException) {
                if (!closing.get()) throw failure
            }
        }
        override fun close() {
            closing.set(true)
            socket.close()
            try { task.get(5, TimeUnit.SECONDS) }
            finally { executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS) }
        }
    }
}
