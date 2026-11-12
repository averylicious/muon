@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.exoplayer.source.BundledExtractorsAdapter
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.metadata.flac.PictureFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.extractor.ogg.OggExtractor
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #714 admission through the actual pinned extractors and Muon's factory: demux and metadata only.
 * No audio or bitmap decoding, heap pressure or phone. Malformed counts stay small, so a broken guard
 * would fail these assertions rather than attempt a large allocation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ContainerMetadataAdmissionTest {
    @Test fun validFixturesKeepGainAndArtworkThroughFactoryAndDurationSeeks() {
        val cover = resource("cover.png")
        for (name in listOf("native.flac", "opus.ogg", "vorbis.ogg")) {
            val output = Capture()
            adapt(PlaybackExtractors(), resource(name), output)
            val metadata = requireNotNull(output.format?.metadata) { name }
            assertEquals(name, -6f, requireNotNull(trackLoudness(metadata)).gainDb, 0.01f)
            val pictures = (0 until metadata.length()).map { metadata.get(it) }.filterIsInstance<PictureFrame>()
            assertArrayEquals(name, cover, pictures.single().pictureData)
            assertTrue(name, output.seekMap)
        }
    }

    @Test fun nativePaddingIsFollowedWithoutBudgetOrPrePeek() {
        val parts = nativeParts()
        // 2MiB of padding under a 1KiB allocating budget: skipped by FlacExtractor, never charged or peeked.
        val bytes = flac(parts, FLAC_COMMENT to parts.comment, FLAC_PADDING to ByteArray(2 * 1024 * 1024))
        val output = Capture()
        val faults = Faults()
        val extractor = MetadataBoundedExtractor(FlacExtractor(), containerBytes = 1024)
        pump(extractor, start(extractor, output, bytes, faults), bytes, faults)
        assertEquals(-6f, requireNotNull(trackLoudness(output.format?.metadata)).gainDb, 0.01f)
        assertTrue("Largest upstream request ${faults.maxRequest}", faults.maxRequest <= 64 * 1024)
    }

    @Test fun nativeCommentAndPictureDeclarationsRefuseBeforeTheirParsers() {
        val parts = nativeParts()
        val image = resource("cover.png")
        val oversizedNested = "METADATA_BLOCK_PICTURE=" +
            java.util.Base64.getEncoder().encodeToString(picture(1_000_000, image))
        val cases = listOf(
            // Count32 with no comment bodies: the parser would size its array before noticing.
            true to flac(parts, FLAC_COMMENT to comments(count = 32)),
            // Structurally complete, but more entries than one song may create.
            false to flac(parts, FLAC_COMMENT to comments(*Array(CONTAINER_METADATA_ENTRIES + 1) { "" })),
            // A Base64 picture whose declared data length exceeds what it carries.
            true to flac(parts, FLAC_COMMENT to comments(oversizedNested)),
            // The same declaration in a native PICTURE block.
            true to flac(parts, FLAC_COMMENT to parts.comment, FLAC_PICTURE to picture(1_000_000, image)),
        )
        for ((malformed, bytes) in cases) {
            val output = Capture()
            val faults = Faults()
            val extractor = MetadataBoundedExtractor(FlacExtractor())
            val error = assertThrows(StreamMetadataRefusal::class.java) {
                pump(extractor, start(extractor, output, bytes, faults), bytes, faults)
            }
            assertEquals(malformed, error.contentIsMalformed)
            assertNull("Parser must not publish refused metadata", output.format)
        }
        // Exactly the entry limit is still a valid song.
        val limit = flac(parts, FLAC_COMMENT to comments(*Array(CONTAINER_METADATA_ENTRIES) { "A=" }))
        val output = Capture()
        val faults = Faults()
        val extractor = MetadataBoundedExtractor(FlacExtractor())
        pump(extractor, start(extractor, output, limit, faults), limit, faults)
        val metadata = output.format!!.metadata!!
        assertEquals(CONTAINER_METADATA_ENTRIES,
            (0 until metadata.length()).count { metadata.get(it) is VorbisComment })
    }

    @Test fun nativeOversizedOrCumulativeBlocksRefuseAtTheirHeaders() {
        val parts = nativeParts()
        // A 4KiB comment declared, body absent, under a 1KiB budget: refused at its 4-byte header.
        val truncated = "fLaC".toByteArray(Charsets.US_ASCII) + block(0, parts.streamInfo, false) +
            block(FLAC_COMMENT, ByteArray(0), true, declared = 4096)
        val faults = Faults()
        val extractor = MetadataBoundedExtractor(FlacExtractor(), containerBytes = 1024)
        val input = start(extractor, Capture(), truncated, faults)
        assertFalse(assertThrows(StreamMetadataRefusal::class.java) {
            pump(extractor, input, truncated, faults)
        }.contentIsMalformed)
        val blockStart = 4L + 4 + parts.streamInfo.size
        assertEquals(blockStart, input.position)
        assertTrue(faults.furthest <= blockStart + 4)
        // A retry at the same block is refused again rather than reaching the parser.
        assertThrows(StreamMetadataRefusal::class.java) { extractor.read(input, PositionHolder()) }

        // Two 600-byte comment payloads: the second exceeds the shared 1KiB budget.
        val each = comments(vendor = "x".repeat(592))
        assertEquals(600, each.size)
        val cumulative = flac(parts, FLAC_COMMENT to each, FLAC_COMMENT to each)
        val output = Capture()
        val more = Faults()
        val second = MetadataBoundedExtractor(FlacExtractor(), containerBytes = 1024)
        assertFalse(assertThrows(StreamMetadataRefusal::class.java) {
            pump(second, start(second, output, cumulative, more), cumulative, more)
        }.contentIsMalformed)
        assertNull(output.format)
    }

    @Test fun nativeInterruptedBlockResumesAndSeekToStartRechecks() {
        val bytes = resource("native.flac")
        val parts = nativeParts()
        // Interrupt inside the comment block's body; reopen at the extractor's position, as Media3 does.
        val faults = Faults(4L + 4 + parts.streamInfo.size + 100)
        val output = Capture()
        val extractor = MetadataBoundedExtractor(FlacExtractor())
        pump(extractor, start(extractor, output, bytes, faults), bytes, faults)
        assertEquals(-1L, faults.at)
        assertEquals(-6f, requireNotNull(trackLoudness(output.format?.metadata)).gainDb, 0.01f)

        // A seek to the start rereads metadata from a fresh response, which is checked again.
        extractor.seek(0, 0)
        val changed = flac(parts, FLAC_COMMENT to withCount(parts.comment, 0, 64), FLAC_PADDING to ByteArray(8192))
        val again = Faults()
        assertTrue(assertThrows(StreamMetadataRefusal::class.java) {
            pump(extractor, open(changed, 0, again, changed.size.toLong()), changed, again)
        }.contentIsMalformed)
    }

    @Test fun oggContinuedPacketBoundSurvivesInterruptedRetry() {
        val tags = OPUS_TAGS + le(4) + "muon".toByteArray(Charsets.US_ASCII) + le(0)
        val headers = page(2, 0, intArrayOf(19), OPUS_HEAD) + page(0, 1, intArrayOf(tags.size), tags) +
            page(0, 2, intArrayOf(1), byteArrayOf(8))
        // One audio packet continued over forty 255-byte page-runs, after successful headers.
        var long = ByteArray(0)
        repeat(40) { long += page(if (it == 0) 0 else 1, 3 + it, intArrayOf(255), ByteArray(255)) }
        val bytes = headers + long
        val pageSize = 27 + 1 + 255
        // Sixteen runs (4080 bytes) fit 4096; the seventeenth is refused after only its header peek.
        val bound = headers.size + 16L * pageSize + 28
        for (interruption in listOf(-1L, headers.size + 9L * pageSize + 28 + 100)) {
            val faults = Faults(interruption)
            val output = Capture()
            val extractor = MetadataBoundedExtractor(OggExtractor(), containerBytes = 4096)
            assertFalse(assertThrows(StreamMetadataRefusal::class.java) {
                pump(extractor, start(extractor, output, bytes, faults, UNKNOWN), bytes, faults, UNKNOWN)
            }.contentIsMalformed)
            assertNotNull(output.format)
            assertEquals(-1L, faults.at)
            // A counter reset by the interruption would have read past this bound.
            assertTrue("Read to ${faults.furthest}, bound $bound", faults.furthest <= bound)
        }
    }

    @Test fun opusPartialTagsReopenChecksTheDeliveredBytes() {
        // OpusTags spans a full 255-byte page-run and an 8-byte continuation holding its count.
        val head = OPUS_TAGS + le(243) + ByteArray(243) { 'm'.code.toByte() }
        assertEquals(255, head.size)
        fun stream(tail: ByteArray) = page(2, 0, intArrayOf(19), OPUS_HEAD) + page(0, 1, intArrayOf(255), head) +
            page(1, 2, intArrayOf(tail.size), tail) + page(0, 3, intArrayOf(1), byteArrayOf(8))
        val valid = stream(le(1) + le(0))
        val changed = stream(le(32) + le(0)) // Thirty-two comments declared, one length field present.
        val continuation = 47L + 283 + 28
        // Interrupted at the continuation's body; the reopened response supplies its own bytes.
        for ((reopened, refused) in listOf(valid to false, changed to true)) {
            val faults = Faults(continuation)
            val output = Capture()
            val extractor = MetadataBoundedExtractor(OggExtractor())
            val input = start(extractor, output, valid, faults, UNKNOWN)
            if (refused) {
                assertTrue(assertThrows(StreamMetadataRefusal::class.java) {
                    pump(extractor, input, valid, faults, UNKNOWN, reopened)
                }.contentIsMalformed)
                assertNull(output.format)
            } else {
                pump(extractor, input, valid, faults, UNKNOWN, reopened)
                assertNotNull(output.format)
            }
            assertEquals(-1L, faults.at)
        }
    }

    @Test fun oggSeekToStartRechecksOnlyBeforeSeekMapPublication() {
        val valid = resource("opus.ogg")
        val changed = withCount(valid, indexOf(valid, OPUS_TAGS) + OPUS_TAGS.size, 64)
        // Headers read, seek map not yet published: StreamReader rereads headers, so they are rechecked.
        val output = Capture()
        val extractor = MetadataBoundedExtractor(OggExtractor())
        assertEquals(Extractor.RESULT_CONTINUE, extractor.read(start(extractor, output, valid, Faults(), UNKNOWN), PositionHolder()))
        assertFalse(output.seekMap)
        extractor.seek(0, 0)
        val faults = Faults()
        assertTrue(assertThrows(StreamMetadataRefusal::class.java) {
            pump(extractor, open(changed, 0, faults, UNKNOWN), changed, faults, UNKNOWN)
        }.contentIsMalformed)

        // After publication StreamReader skips its known headers, so no header is parsed or refused.
        val published = Capture()
        val second = MetadataBoundedExtractor(OggExtractor())
        val first = Faults()
        pump(second, start(second, published, valid, first, UNKNOWN), valid, first, UNKNOWN) { published.seekMap }
        assertTrue(published.seekMap)
        second.seek(0, 0)
        val skipped = Faults()
        pump(second, open(changed, 0, skipped, UNKNOWN), changed, skipped, UNKNOWN)
        assertEquals(changed.size.toLong(), skipped.furthest)
    }

    @Test fun factoryAndSingleCandidateRefuseMalformedFixtureCounts() {
        val opus = resource("opus.ogg")
        val vorbis = resource("vorbis.ogg")
        val parts = nativeParts()
        val malformed = listOf(
            withCount(opus, indexOf(opus, OPUS_TAGS) + OPUS_TAGS.size, 64),
            withCount(vorbis, indexOf(vorbis, VORBIS_COMMENT_HEADER) + VORBIS_COMMENT_HEADER.size, 64),
            flac(parts, FLAC_COMMENT to withCount(parts.comment, 0, 64)),
        )
        for (bytes in malformed) {
            assertTrue(assertThrows(StreamMetadataRefusal::class.java) {
                adapt(PlaybackExtractors(), bytes, Capture())
            }.contentIsMalformed)
        }
        // A one-candidate factory reads without sniffing; the wrapper still owns that first read.
        assertTrue(assertThrows(StreamMetadataRefusal::class.java) {
            adapt(PlaybackExtractors(OggExtractor.FACTORY), malformed[0], Capture())
        }.contentIsMalformed)
    }

    // Fixtures and builders.

    private class Parts(val streamInfo: ByteArray, val comment: ByteArray, val audio: ByteArray)

    private fun nativeParts(): Parts {
        val bytes = resource("native.flac")
        assertEquals("fLaC", String(bytes, 0, 4, Charsets.US_ASCII))
        var at = 4
        var info: ByteArray? = null
        var comment: ByteArray? = null
        while (true) {
            val head = bytes[at].toInt() and 255
            val size = ((bytes[at + 1].toInt() and 255) shl 16) or ((bytes[at + 2].toInt() and 255) shl 8) or
                (bytes[at + 3].toInt() and 255)
            val payload = bytes.copyOfRange(at + 4, at + 4 + size)
            when (head and 0x7f) { 0 -> info = payload; FLAC_COMMENT -> comment = payload }
            at += 4 + size
            if (head and 0x80 != 0) break
        }
        return Parts(requireNotNull(info), requireNotNull(comment), bytes.copyOfRange(at, bytes.size))
    }

    private fun flac(parts: Parts, vararg blocks: Pair<Int, ByteArray>): ByteArray {
        var out = "fLaC".toByteArray(Charsets.US_ASCII) + block(0, parts.streamInfo, false)
        blocks.forEachIndexed { i, (type, payload) -> out += block(type, payload, i == blocks.lastIndex) }
        return out + parts.audio
    }

    private fun block(type: Int, payload: ByteArray, last: Boolean, declared: Int = payload.size) =
        byteArrayOf(((if (last) 0x80 else 0) or type).toByte(), (declared ushr 16).toByte(),
            (declared ushr 8).toByte(), declared.toByte()) + payload

    private fun comments(vararg entries: String, count: Int = entries.size, vendor: String = "muon"): ByteArray {
        val out = ByteArrayOutputStream()
        val name = vendor.toByteArray(Charsets.UTF_8)
        out.write(le(name.size)); out.write(name); out.write(le(count))
        for (entry in entries) { val bytes = entry.toByteArray(Charsets.UTF_8); out.write(le(bytes.size)); out.write(bytes) }
        return out.toByteArray()
    }

    private fun picture(declaredBytes: Int, data: ByteArray): ByteArray {
        val mime = "image/png".toByteArray(Charsets.US_ASCII)
        return ByteBuffer.allocate(32 + mime.size + data.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(3).putInt(mime.size).put(mime).putInt(0)
            .putInt(1).putInt(1).putInt(32).putInt(0).putInt(declaredBytes).put(data).array()
    }

    /** A copy whose Vorbis comment count, after the vendor string starting at [vendorAt], is [count]. */
    private fun withCount(bytes: ByteArray, vendorAt: Int, count: Int): ByteArray {
        val vendor = ByteBuffer.wrap(bytes, vendorAt, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return bytes.copyOf().also { le(count).copyInto(it, vendorAt + 4 + vendor) }
    }

    private fun indexOf(bytes: ByteArray, prefix: ByteArray): Int =
        (0..bytes.size - prefix.size).first { at -> prefix.indices.all { bytes[at + it] == prefix[it] } }

    private fun page(type: Int, sequence: Int, laces: IntArray, body: ByteArray): ByteArray {
        require(laces.sum() == body.size)
        val buffer = ByteBuffer.allocate(27 + laces.size + body.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("OggS".toByteArray(Charsets.US_ASCII)).put(0).put(type.toByte()).putLong(0)
            .putInt(7).putInt(sequence).putInt(0).put(laces.size.toByte())
        laces.forEach { buffer.put(it.toByte()) }
        return buffer.put(body).array()
    }

    private fun le(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun resource(name: String) =
        requireNotNull(javaClass.getResourceAsStream("/container-admission/$name")) { name }.use { it.readBytes() }

    // Input like ProgressiveMediaPeriod's: an interruption or RESULT_SEEK reopens at a position.

    private class Faults(var at: Long = -1) { var maxRequest = 0; var furthest = 0L }
    private class Interrupted : IOException("Injected interruption")

    private class Reader(private val bytes: ByteArray, private var at: Long, private val faults: Faults) : DataReader {
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            faults.maxRequest = maxOf(faults.maxRequest, length)
            if (at >= bytes.size) return C.RESULT_END_OF_INPUT
            var count = minOf(length.toLong(), bytes.size - at).toInt()
            val fail = faults.at
            if (fail >= at && fail < at + count) {
                if (fail == at) { faults.at = -1; throw Interrupted() }
                count = (fail - at).toInt()
            }
            bytes.copyInto(target, offset, at.toInt(), (at + count).toInt())
            at += count
            faults.furthest = maxOf(faults.furthest, at)
            return count
        }
    }

    private class Capture : ExtractorOutput {
        var format: Format? = null
        var seekMap = false
        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput by DiscardingTrackOutput() {
            override fun format(format: Format) { this@Capture.format = format }
        }
        override fun endTracks() {}
        override fun seekMap(map: SeekMap) { seekMap = true }
    }

    private fun open(bytes: ByteArray, at: Long, faults: Faults, length: Long): ExtractorInput =
        DefaultExtractorInput(Reader(bytes, at, faults), at, length)

    private fun start(extractor: Extractor, output: Capture, bytes: ByteArray, faults: Faults,
        length: Long = bytes.size.toLong()): ExtractorInput {
        extractor.init(output)
        val input = open(bytes, 0, faults, length)
        assertTrue(extractor.sniff(input))
        input.resetPeekPosition()
        return input
    }

    private fun pump(extractor: Extractor, first: ExtractorInput, bytes: ByteArray, faults: Faults,
        length: Long = bytes.size.toLong(), reopened: ByteArray = bytes, until: () -> Boolean = { false }) {
        var input = first
        val holder = PositionHolder()
        var reads = 0
        while (!until()) {
            check(reads++ < 10_000) { "Fixture extractor did not finish" }
            val result = try { extractor.read(input, holder) } catch (_: Interrupted) {
                input = open(reopened, input.position, faults, length)
                continue
            }
            when (result) {
                Extractor.RESULT_CONTINUE -> Unit
                Extractor.RESULT_SEEK -> input = open(bytes, holder.position, faults, length)
                else -> return
            }
        }
    }

    private fun adapt(factory: ExtractorsFactory, bytes: ByteArray, output: Capture) {
        val adapter = BundledExtractorsAdapter(factory)
        val uri = Uri.parse("http://127.0.0.1:7814/api1/file/1")
        val faults = Faults()
        val length = bytes.size.toLong()
        try {
            adapter.init(Reader(bytes, 0, faults), uri, emptyMap(), 0, length, output)
            val holder = PositionHolder()
            var reads = 0
            while (true) when (check(reads++ < 10_000) { "Fixture adapter did not finish" }.let { adapter.read(holder) }) {
                Extractor.RESULT_CONTINUE -> Unit
                Extractor.RESULT_SEEK ->
                    adapter.init(Reader(bytes, holder.position, faults), uri, emptyMap(), holder.position, length, output)
                else -> return
            }
        } finally { adapter.release() }
    }

    private companion object {
        const val FLAC_PADDING = 1
        const val FLAC_COMMENT = 4
        const val FLAC_PICTURE = 6
        val UNKNOWN = C.LENGTH_UNSET.toLong()
        val OPUS_TAGS = "OpusTags".toByteArray(Charsets.US_ASCII)
        val VORBIS_COMMENT_HEADER = byteArrayOf(3) + "vorbis".toByteArray(Charsets.US_ASCII)
        val OPUS_HEAD: ByteArray = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusHead".toByteArray(Charsets.US_ASCII)).put(1).put(1)
            .putShort(0).putInt(48_000).putShort(0).put(0).array()
    }
}
