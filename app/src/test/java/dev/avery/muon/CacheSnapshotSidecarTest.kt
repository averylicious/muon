package dev.avery.muon

import android.net.Uri
import android.util.AtomicFile
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.DefaultContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * #179 S3 feasibility, same approach as RemovableCacheCharacterizationTest: real pinned SimpleCache,
 * native SQLite, disposable folders. A healthy snapshot of public span fields and full content metadata
 * goes through a TEST-ONLY sidecar format in an actual AtomicFile, then into a fresh cache. Not a shipped
 * schema, migration, durability proof or crash-safety claim. The old cache directory is never reopened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CacheSnapshotSidecarTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var database: StandaloneDatabaseProvider
    private val caches = mutableListOf<SimpleCache>()
    private val key = "downloaded-song"
    private val payload = ByteArray(256) { it.toByte() }
    private val split = 100
    private val redirect = Uri.parse("http://192.168.1.20:7814/api1/fileopus/7")
    private val raw = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x80.toByte(), 0xC3.toByte()) // Not UTF-8.

    @Before fun setUp() {
        database = StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
    }

    @After fun tearDown() {
        try {
            caches.asReversed().forEach { it.release() }
        } finally {
            database.close()
        }
    }

    @Test fun twoSpansAndAllMetadataSurviveASidecarRoundTripIntoAFreshCache() {
        val original = open(folders.newFolder("card"))
        val first = seed(original, 0, payload.copyOfRange(0, split))
        val second = seed(original, split.toLong(), payload.copyOfRange(split, payload.size))
        original.applyContentMetadataMutations(key, ContentMetadataMutations().apply {
            ContentMetadataMutations.setContentLength(this, payload.size.toLong())
            ContentMetadataMutations.setRedirectedUri(this, redirect)
            set("custom_raw", raw)
        })
        val captured = capture(original)
        assertEquals(2, captured.spans.size)
        assertEquals(setOf(ContentMetadata.KEY_CONTENT_LENGTH, ContentMetadata.KEY_REDIRECTED_URI, "custom_raw"),
            captured.metadata.keys)

        val sidecar = File(folders.newFolder("phone-files"), "card-snapshot")
        write(AtomicFile(sidecar), captured)
        original.release() // Mounted and healthy: the release the existing fixture shows is preserving.
        caches -= original
        val reloaded = decode(AtomicFile(sidecar).openRead().use { it.readBytes() })
        assertCatalogEquals(captured, reloaded)

        val freshFolder = folders.newFolder("fresh")
        val fresh = open(freshFolder)
        import(fresh, reloaded)
        fresh.release()
        caches -= fresh
        val reopened = open(freshFolder)
        assertTrue(reopened.isCached(key, 0, payload.size.toLong()))
        val spans = reopened.getCachedSpans(key).sortedBy { it.position }
        assertEquals(listOf(0L to split.toLong(), split.toLong() to (payload.size - split).toLong()),
            spans.map { it.position to it.length })
        assertArrayEquals(payload, spans.fold(byteArrayOf()) { acc, span -> acc + requireNotNull(span.file).readBytes() })
        val metadata = reopened.getContentMetadata(key)
        assertEquals(payload.size.toLong(), ContentMetadata.getContentLength(metadata))
        assertEquals(redirect, ContentMetadata.getRedirectedUri(metadata))
        assertArrayEquals(raw, metadata.get("custom_raw", null as ByteArray?))
        assertMetadataEquals(captured.metadata, entries(metadata))

        // Originals are retained, at the captured paths, byte for byte.
        assertEquals(listOf(first.path, second.path), captured.spans.map { it.path })
        assertArrayEquals(payload.copyOfRange(0, split), first.readBytes())
        assertArrayEquals(payload.copyOfRange(split, payload.size), second.readBytes())
    }

    @Test fun failedSidecarReplacementKeepsThePreviouslyCommittedCatalog() {
        val cache = open(folders.newFolder("card"))
        seed(cache, 0, payload)
        cache.applyContentMetadataMutations(key, ContentMetadataMutations().apply {
            ContentMetadataMutations.setContentLength(this, payload.size.toLong())
            set("custom_raw", raw)
        })
        val committed = capture(cache)
        val sidecar = File(folders.newFolder("phone-files"), "card-snapshot")
        write(AtomicFile(sidecar), committed)

        // A replacement that is abandoned through the actual API after writing part of a different catalog.
        val replacement = Catalog(key, committed.spans, committed.metadata + ("custom_other" to byteArrayOf(1)))
        val file = AtomicFile(sidecar)
        val stream = file.startWrite()
        val encoded = encode(replacement)
        stream.write(encoded, 0, encoded.size / 2)
        file.failWrite(stream)

        assertCatalogEquals(committed, decode(AtomicFile(sidecar).openRead().use { it.readBytes() }))
        assertCatalogEquals(committed, decode(AtomicFile(sidecar).readFully()))
    }

    /** One key's public span fields and full metadata, copied out of the cache; test memory only. */
    private class Catalog(val key: String, val spans: List<SpanRecord>, val metadata: Map<String, ByteArray>)
    private data class SpanRecord(val position: Long, val length: Long, val path: String)

    private fun capture(cache: SimpleCache): Catalog = Catalog(key,
        cache.getCachedSpans(key).sortedBy { it.position }.map { SpanRecord(it.position, it.length, requireNotNull(it.file).path) },
        entries(cache.getContentMetadata(key)))

    /**
     * Full enumeration needs the concrete class: in pinned Media3 1.11.0 SimpleCache returns
     * DefaultContentMetadata, whose entrySet exposes its internal arrays, so each is copied. The
     * ContentMetadata interface itself promises no enumeration.
     */
    private fun entries(metadata: ContentMetadata): Map<String, ByteArray> =
        (metadata as DefaultContentMetadata).entrySet().associate { it.key to it.value.copyOf() }

    private fun import(cache: SimpleCache, catalog: Catalog) {
        catalog.spans.forEach { span ->
            val hole = cache.startReadWrite(catalog.key, span.position, span.length)
            assertFalse(hole.isCached)
            try {
                val target = cache.startFile(catalog.key, span.position, span.length)
                File(span.path).copyTo(target)
                assertEquals(span.length, target.length())
                cache.commitFile(target, span.length)
            } finally { cache.releaseHoleSpan(hole) }
        }
        cache.applyContentMetadataMutations(catalog.key, ContentMetadataMutations().apply {
            catalog.metadata.forEach { (name, value) -> set(name, value) }
        })
    }

    private fun write(file: AtomicFile, catalog: Catalog) {
        val stream = file.startWrite()
        try {
            stream.write(encode(catalog))
        } catch (failure: Throwable) {
            file.failWrite(stream)
            throw failure
        }
        file.finishWrite(stream)
    }

    // TEST-ONLY sidecar encoding: a version, the key, spans, then metadata entries with raw bytes.
    private fun encode(catalog: Catalog): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(1)
            out.writeUTF(catalog.key)
            out.writeInt(catalog.spans.size)
            catalog.spans.forEach { out.writeLong(it.position); out.writeLong(it.length); out.writeUTF(it.path) }
            out.writeInt(catalog.metadata.size)
            catalog.metadata.toSortedMap().forEach { (name, value) -> out.writeUTF(name); out.writeInt(value.size); out.write(value) }
        }
    }.toByteArray()

    private fun decode(data: ByteArray): Catalog = DataInputStream(data.inputStream()).use { input ->
        check(input.readInt() == 1) { "Unknown test sidecar version" }
        val key = input.readUTF()
        val spans = List(input.readInt()) { SpanRecord(input.readLong(), input.readLong(), input.readUTF()) }
        val metadata = (0 until input.readInt()).associate { input.readUTF() to ByteArray(input.readInt()).also(input::readFully) }
        check(input.read() == -1) { "Trailing bytes in test sidecar" }
        Catalog(key, spans, metadata)
    }

    private fun assertCatalogEquals(expected: Catalog, actual: Catalog) {
        assertEquals(expected.key, actual.key)
        assertEquals(expected.spans, actual.spans)
        assertMetadataEquals(expected.metadata, actual.metadata)
    }

    private fun assertMetadataEquals(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (name, value) -> assertArrayEquals(name, value, actual.getValue(name)) }
    }

    private fun open(folder: File): SimpleCache =
        SimpleCache(folder, NoOpCacheEvictor(), database).also {
            caches += it
            it.checkInitialization()
        }

    private fun seed(cache: SimpleCache, position: Long, bytes: ByteArray): File {
        val hole = cache.startReadWrite(key, position, bytes.size.toLong())
        assertFalse(hole.isCached)
        try {
            val file = cache.startFile(key, position, bytes.size.toLong())
            file.writeBytes(bytes)
            cache.commitFile(file, bytes.size.toLong())
            return file
        } finally { cache.releaseHoleSpan(hole) }
    }
}
