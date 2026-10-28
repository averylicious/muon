@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ForwardingExtractorInput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MatroskaMetadataAdmissionTest {
    @Test fun actualAacOpusVorbisKeepFactoryIdentitySniffFormatReopensAndSeek() {
        for ((resource, mime) in listOf("control.mka" to MimeTypes.AUDIO_AAC, "control-opus.mka" to MimeTypes.AUDIO_OPUS, "control-vorbis.mka" to MimeTypes.AUDIO_VORBIS)) {
            val bytes = requireNotNull(javaClass.getResourceAsStream("/mkv-admission/$resource")).use { it.readBytes() }
            val raw = actual()
            val bounded = MetadataBoundedExtractor(raw)
            assertSame(raw, bounded.underlyingImplementation)
            val output = Capture()
            try {
                val first = opened(bytes)
                assertTrue(resource, bounded.sniff(first)); first.resetPeekPosition()
                bounded.init(output)
                finish(bounded, first) { opened(bytes, it) }
                assertEquals(resource, mime, output.format?.sampleMimeType)
                assertEquals(48_000, output.format?.sampleRate)
                val point = requireNotNull(output.map).getSeekPoints(0).first
                bounded.seek(point.position, point.timeUs)
                finish(bounded, opened(bytes, point.position)) { opened(bytes, it) }
                bounded.seek(0, 0)
                finish(bounded, opened(bytes)) { opened(bytes, it) }
            } finally { bounded.release() }
        }
    }

    @Test fun declaredStringAndBinaryAreRefusedBeforeNativePayloadArrayRead() {
        for (leafId in listOf(0x86, 0x63a2, 0x7672, 0x4255, 0x47e2)) {
            val bytes = track(header(leafId, 4096), missing = 4096)
            for (knownLength in listOf(true, false)) {
                var bodyReads = 0
                val probe = object : ForwardingExtractorInput(opened(bytes, knownLength = knownLength)) {
                    override fun readFully(target: ByteArray, offset: Int, length: Int) {
                        if (length == 4096) bodyReads++
                        super.readFully(target, offset, length)
                    }
                }
                val bounded = MetadataBoundedExtractor(actual(), mkvLimits = MatroskaLimits(elementBytes = 1024))
                try {
                    bounded.init(Capture())
                    assertThrows(StreamMetadataRefusal::class.java) { finish(bounded, probe) { error("Unexpected seek") } }
                    assertEquals("No native payload target should exist for $leafId", 0, bodyReads)
                } finally { bounded.release() }
            }
        }
        // A small declaration past a KNOWN EOF is malformed even within the byte policy.
        val bounded = MetadataBoundedExtractor(actual())
        try {
            bounded.init(Capture())
            val refusal = assertThrows(StreamMetadataRefusal::class.java) {
                finish(bounded, opened(track(header(0x63a2, 64), missing = 64))) { error("Unexpected seek") }
            }
            assertTrue(refusal.contentIsMalformed)
        } finally { bounded.release() }
    }

    @Test fun unknownLengthSniffIsRefusedBeforeNativePeekBufferGrowth() {
        val payload = 128 * 1024L
        val child = header(0x4282, payload)
        val bytes = header(0x1a45dfa3, child.size + payload) + child
        val native = opened(bytes, knownLength = false)
        val field = DefaultExtractorInput::class.java.getDeclaredField("peekBuffer").also { it.isAccessible = true }
        val before = (field.get(native) as ByteArray).size
        val bounded = MetadataBoundedExtractor(actual())
        try {
            assertThrows(StreamMetadataRefusal::class.java) { bounded.sniff(native) }
            assertEquals(before, (field.get(native) as ByteArray).size)
        } finally { bounded.release() }
    }

    @Test fun metadataAggregateAndLedgerBudgetsPrecedeFurtherNativeBodies() {
        val leaves = element(0x536e, ByteArray(64) { 'a'.code.toByte() }) +
            element(0x536e, ByteArray(64) { 'b'.code.toByte() }) + element(0x536e, ByteArray(64))
        for (limits in listOf(MatroskaLimits(metadataBytes = 130), MatroskaLimits(metadataEntries = 2))) {
            val bounded = MetadataBoundedExtractor(actual(), mkvLimits = limits)
            try {
                bounded.init(Capture())
                assertThrows(StreamMetadataRefusal::class.java) { finish(bounded, opened(track(leaves))) { error("Unexpected seek") } }
            } finally { bounded.release() }
        }
    }

    @Test fun interruptedSizeVarintKeepsAdmissionUntilItsActualRemainingByteArrives() {
        val content = ByteArray(256) { 'a'.code.toByte() }
        val bytes = track(element(0x536e, content))
        val nameStart = bytes.indexOfSequence(id(0x536e) + size(256))
        assertTrue(nameStart >= 0)
        val restAt = nameStart + id(0x536e).size + 1
        val bounded = MetadataBoundedExtractor(actual(), mkvLimits = MatroskaLimits(elementBytes = 300))
        val fault = object : ForwardingExtractorInput(opened(bytes)) {
            override fun readFully(target: ByteArray, offset: Int, length: Int) {
                if (position == restAt.toLong() && offset == 1 && length == 1) throw IOException("Injected header retry")
                super.readFully(target, offset, length)
            }
        }
        try {
            bounded.init(Capture())
            assertThrows(IOException::class.java) { finish(bounded, fault) { error("Unexpected seek") } }
            assertEquals(restAt.toLong(), fault.position)
            // The retained first size byte is0x41; changing only its remaining byte expands256
            // to511. Admission must read that delivered byte before native array construction.
            val changed = bytes.copyOf().also { it[restAt] = 0xff.toByte() }
            assertThrows(StreamMetadataRefusal::class.java) {
                finish(bounded, opened(changed, restAt.toLong(), knownLength = false)) { error("Unexpected seek") }
            }
        } finally { bounded.release() }
    }

    @Test fun actualCodecPrivateBytesBoundVorbisLacesRegardlessOfCodecFieldOrder() {
        val codec = element(0x86, "A_VORBIS".toByteArray())
        // Tiny payload declares510 info bytes then presents only a packet-type byte. Native
        // allocates the info target before arraycopy checks backing; use modest values only.
        val privateData = element(0x63a2, byteArrayOf(2, 0xff.toByte(), 0xff.toByte(), 0, 0, 1))
        for (leaves in listOf(codec + privateData, privateData + codec)) {
            val bounded = MetadataBoundedExtractor(actual())
            try {
                bounded.init(Capture())
                val refusal = assertThrows(StreamMetadataRefusal::class.java) {
                    finish(bounded, opened(track(leaves))) { error("Unexpected seek") }
                }
                assertTrue(refusal.contentIsMalformed)
            } finally { bounded.release() }
        }
    }

    @Test fun realTrackChapterAndCueRetentionBudgetsPreserveSmallControlsAndPostMapSeek() {
        val tracks = element(0x1654ae6b, pcmEntry(1) + pcmEntry(2))
        val chapterAtoms = (1..2).map { uid -> element(0xb6, element(0x73c4, byteArrayOf(uid.toByte()))) }
            .reduce { a, b -> a + b }
        val chapters = element(0x1043a770, element(0x45b9, chapterAtoms))
        val points = (1..3).map {
            element(0xb7, element(0xf7, byteArrayOf(1)) + element(0xf1, byteArrayOf(0)))
        }.reduce { a, b -> a + b }
        val cues = element(0x1c53bb6b, element(0xbb, element(0xb3, byteArrayOf(0)) + points))
        val info = element(0x1549a966, element(0x2ad7b1, byteArrayOf(0x0f, 0x42, 0x40)) +
            element(0x4489, java.nio.ByteBuffer.allocate(8).putDouble(1000.0).array()))
        val bytes = segment(info + tracks + chapters + cues)
        for (limits in listOf(MatroskaLimits(tracks = 2), MatroskaLimits(chapters = 1), MatroskaLimits(cues = 2))) {
            val bounded = MetadataBoundedExtractor(actual(), mkvLimits = limits)
            try {
                bounded.init(Capture())
                assertThrows(StreamMetadataRefusal::class.java) { finish(bounded, opened(bytes)) { opened(bytes, it) } }
            } finally { bounded.release() }
        }
        val bounded = MetadataBoundedExtractor(actual(), mkvLimits = MatroskaLimits(tracks = 3, chapters = 2, cues = 3))
        val output = Capture()
        try {
            bounded.init(output)
            finish(bounded, opened(bytes)) { opened(bytes, it) }
            assertEquals(MimeTypes.AUDIO_RAW, output.format?.sampleMimeType)
            assertEquals(2, output.format?.metadata?.length())
            assertNotNull(output.map)
            // Native final cues are ignored after a map. Do not charge ignored re-parses or
            // wipe retained track/chapter costs on seek. The exact tight budgets still pass.
            bounded.seek(0, 0)
            finish(bounded, opened(bytes)) { opened(bytes, it) }
        } finally { bounded.release() }
    }

    @Test fun actualUnknownSizedClusterFramesAreBoundedWithoutPretendingTheyAreSchemaDepth() {
        val info = element(0x1549a966, element(0x2ad7b1, byteArrayOf(0x0f, 0x42, 0x40)))
        val clusters = (0..3).map { timestamp ->
            id(0x1f43b675) + byteArrayOf(1, -1, -1, -1, -1, -1, -1, -1) +
                element(0xe7, byteArrayOf(timestamp.toByte()))
        }.reduce { a, b -> a + b }
        val bytes = segment(info + element(0x1654ae6b, pcmEntry(1)) + clusters)
        fun nativeFrames(raw: MatroskaExtractor): Int {
            val reader = MatroskaExtractor::class.java.getDeclaredField("reader").also { it.isAccessible = true }.get(raw)
            val stack = reader.javaClass.getDeclaredField("masterElementsStack").also { it.isAccessible = true }.get(reader)
            return (stack as Collection<*>).size
        }
        val raw = actual() as MatroskaExtractor
        try {
            raw.init(Capture())
            finish(raw, opened(bytes)) { opened(bytes, it) }
            assertEquals("Native retains Segment plus four sibling unknown Cluster frames at EOF", 5, nativeFrames(raw))
        } finally { raw.release() }
        val cappedRaw = actual() as MatroskaExtractor
        val capped = MetadataBoundedExtractor(cappedRaw, mkvLimits = MatroskaLimits(nativeMasters = 4))
        try {
            capped.init(Capture())
            assertThrows(StreamMetadataRefusal::class.java) { finish(capped, opened(bytes)) { opened(bytes, it) } }
            assertEquals("Refused before the fifth native frame", 4, nativeFrames(cappedRaw))
        } finally { capped.release() }
        val bounded = MetadataBoundedExtractor(actual(), mkvLimits = MatroskaLimits(nativeMasters = 5))
        try {
            bounded.init(Capture())
            finish(bounded, opened(bytes)) { opened(bytes, it) }
            bounded.seek(0, 0)
            finish(bounded, opened(bytes)) { opened(bytes, it) }
        } finally { bounded.release() }
    }

    @Test fun knownTextBlocksAreRefusedBeforeScratchAcrossTrackWidthsDefaultZeroAndSeek() {
        for (blockId in listOf(0xa1, 0xa3)) for (number in listOf<Int?>(null, 1))
            for (width in listOf(1, 2, 8)) for (knownLength in listOf(true, false)) {
                val bytes = sampleFixture("S_TEXT/UTF8", number, number ?: 0, width, blockId)
                var bodyReads = 0
                fun input() = object : ForwardingExtractorInput(opened(bytes, knownLength = knownLength)) {
                    override fun readFully(target: ByteArray, offset: Int, length: Int) {
                        if (length == 32 * 1024) bodyReads++
                        super.readFully(target, offset, length)
                    }
                }
                val bounded = MetadataBoundedExtractor(actual(), mkvLimits = MatroskaLimits(elementBytes = 64))
                try {
                    bounded.init(Capture())
                    assertThrows(StreamMetadataRefusal::class.java) { finish(bounded, input()) { error("Unexpected seek") } }
                    assertEquals("No native subtitle target before refusal", 0, bodyReads)
                    if (blockId == 0xa3 && number == 1 && width == 1 && knownLength) {
                        bounded.seek(0, 0)
                        assertThrows(StreamMetadataRefusal::class.java) { finish(bounded, input()) { error("Unexpected seek") } }
                        assertEquals(0, bodyReads)
                    }
                } finally { bounded.release() }
            }
    }

    @Test fun unfinishedLacingIsRefusedAtItsHeaderAndSmallNativeLacesStillDeliver() {
        for (codec in listOf("A_AAC", "S_TEXT/UTF8")) for (blockId in listOf(0xa1, 0xa3)) {
            val lace = byteArrayOf(1) + ByteArray(256) { 0xff.toByte() }
            val bytes = sampleFixture(codec, 1, 1, 1, blockId, lace.size + 32, lace, flags = 0x82)
            var deliveredPrefix = 0
            fun input() = object : ForwardingExtractorInput(opened(bytes)) {
                override fun readFully(target: ByteArray, offset: Int, length: Int) {
                    if (length == 1 && offset >= 4) deliveredPrefix = maxOf(deliveredPrefix, offset + length)
                    super.readFully(target, offset, length)
                }
            }
            val bounded = MetadataBoundedExtractor(actual(), mkvLimits = MatroskaLimits(xiphHeaderBytes = 64))
            try {
                bounded.init(Capture())
                assertThrows(StreamMetadataRefusal::class.java) { finish(bounded, input()) { error("Unexpected seek") } }
                assertEquals("No over-budget native header read reaches the input", 64, deliveredPrefix)
                // The prior refusal left a larger native scratch array. Require real delivery
                // again: its retained capacity must not poison the restarted small reads.
                deliveredPrefix = 0
                bounded.seek(0, 0)
                assertThrows(StreamMetadataRefusal::class.java) { finish(bounded, input()) { error("Unexpected seek") } }
                assertEquals(64, deliveredPrefix)
            } finally { bounded.release() }
        }
        // Two one-byte native AAC samples with a completed Xiph header; extraction, not decoding.
        val bytes = sampleFixture("A_AAC", 1, 1, 1, 0xa3, 4, byteArrayOf(1, 1, 0, 0), flags = 0x82)
        val captured = Capture()
        val sizes = ArrayList<Int>()
        val output = object : ExtractorOutput by captured {
            override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput by captured.track(id, type) {
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                    sizes.add(size)
                }
            }
        }
        val normal = MetadataBoundedExtractor(actual(), mkvLimits = MatroskaLimits(xiphHeaderBytes = 64))
        try {
            normal.init(output); finish(normal, opened(bytes)) { opened(bytes, it) }
            assertEquals(MimeTypes.AUDIO_AAC, captured.format?.sampleMimeType)
            assertEquals(listOf(1, 1), sizes)
        } finally { normal.release() }
    }

    @Test fun largeAudioUnknownTracksAndSmallNativeSubtitlesKeepTheirOwnBehavior() {
        for ((codec, body, blockId) in listOf(
            Triple("A_AAC", ByteArray(32 * 1024), 0xa3),
            Triple("S_TEXT/UTF8", "hello".toByteArray(), 0xa1),
            Triple("S_TEXT/UTF8", "hello ".repeat(128).toByteArray(), 0xa1),
        )) {
            val bytes = sampleFixture(codec, 1, 1, 1, blockId, body.size, body)
            val captured = Capture()
            val sampleSizes = ArrayList<Int>()
            val output = object : ExtractorOutput by captured {
                override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput by captured.track(id, type) {
                    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                        sampleSizes.add(size)
                    }
                }
            }
            val bounded = MetadataBoundedExtractor(actual(), mkvLimits = MatroskaLimits(
                elementBytes = if (codec == "A_AAC") 64 else maxOf(64, body.size + 8).toLong(),
                xiphHeaderBytes = 64))
            try {
                bounded.init(output)
                finish(bounded, opened(bytes)) { opened(bytes, it) }
                assertFalse("Native sample delivery survives the guard", sampleSizes.isEmpty())
                if (codec == "A_AAC") {
                    assertEquals(MimeTypes.AUDIO_AAC, captured.format?.sampleMimeType)
                    assertEquals(listOf(body.size), sampleSizes) // No whole-audio Block cap.
                } else assertEquals(MimeTypes.APPLICATION_MEDIA3_CUES, captured.format?.sampleMimeType)
            } finally { bounded.release() }
        }
        // Unknown track2 is skipped by native code; this is an EOF, not our known-text refusal.
        val unknown = MetadataBoundedExtractor(actual(), mkvLimits = MatroskaLimits(elementBytes = 64))
        try {
            unknown.init(Capture())
            val bytes = sampleFixture("S_TEXT/UTF8", 1, 2, 1, 0xa3)
            assertThrows(java.io.EOFException::class.java) { finish(unknown, opened(bytes)) { error("Unexpected seek") } }
        } finally { unknown.release() }
    }

    private fun sampleFixture(codec: String, number: Int?, blockTrack: Int, width: Int, blockId: Int,
        declared: Int = 32 * 1024, body: ByteArray = byteArrayOf(), flags: Int = 0x80): ByteArray {
        val entry = element(0xae, (number?.let { element(0xd7, byteArrayOf(it.toByte())) } ?: byteArrayOf()) +
            element(0x83, byteArrayOf(if (codec == "A_AAC") 2 else 17)) + element(0x86, codec.toByteArray()) +
            if (codec == "A_AAC") element(0x63a2, byteArrayOf(0x12, 0x10)) else byteArrayOf())
        val marked = (1L shl (7 * width)) or blockTrack.toLong()
        val track = ByteArray(width) { i -> (marked ushr ((width - 1 - i) * 8)).toByte() }
        val blockHeader = track + byteArrayOf(0, 0, flags.toByte())
        val missing = declared - body.size
        var block = header(blockId, blockHeader.size.toLong() + declared) + blockHeader + body
        if (blockId == 0xa1) {
            val duration = element(0x9b, byteArrayOf(1))
            block = header(0xa0, block.size.toLong() + duration.size + missing) + block + duration
        }
        val clusterBody = element(0xe7, byteArrayOf(0)) + block
        val cluster = header(0x1f43b675, clusterBody.size.toLong() + missing) + clusterBody
        val children = element(0x1549a966, byteArrayOf()) + element(0x1654ae6b, entry) + cluster
        return element(0x1a45dfa3, element(0x4282, "matroska".toByteArray())) +
            header(0x18538067, children.size.toLong() + missing) + children
    }

    private fun actual() = DefaultExtractorsFactory().createExtractors().single { it is MatroskaExtractor }
    private fun finish(extractor: Extractor, start: ExtractorInput, reopen: (Long) -> ExtractorInput) {
        var input = start
        val holder = PositionHolder()
        repeat(1000) {
            when (extractor.read(input, holder)) {
                Extractor.RESULT_CONTINUE -> Unit
                Extractor.RESULT_SEEK -> input = reopen(holder.position)
                Extractor.RESULT_END_OF_INPUT -> return
                else -> error("Unexpected extractor result")
            }
        }
        error("Tiny fixture did not finish")
    }
    private class Capture : ExtractorOutput {
        var format: Format? = null
        var map: SeekMap? = null
        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput by DiscardingTrackOutput() {
            override fun format(value: Format) { format = value }
        }
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) { map = seekMap }
    }
    private fun opened(bytes: ByteArray, at: Long = 0, knownLength: Boolean = true): DefaultExtractorInput {
        var cursor = at.toInt()
        val reader = DataReader { target, offset, length ->
            if (length == 0) 0 else if (cursor >= bytes.size) C.RESULT_END_OF_INPUT else
                minOf(length, bytes.size - cursor).also { bytes.copyInto(target, offset, cursor, cursor + it); cursor += it }
        }
        return DefaultExtractorInput(reader, at, if (knownLength) bytes.size.toLong() else C.LENGTH_UNSET.toLong())
    }
    private fun id(value: Int): ByteArray {
        val width = when { value > 0xffffff -> 4; value > 0xffff -> 3; value > 0xff -> 2; else -> 1 }
        return ByteArray(width) { i -> (value ushr ((width - 1 - i) * 8)).toByte() }
    }
    private fun size(value: Long): ByteArray {
        for (width in 1..8) if (value < (1L shl (7 * width)) - 1) {
            val marked = value or (1L shl (7 * width))
            return ByteArray(width) { i -> (marked ushr ((width - 1 - i) * 8)).toByte() }
        }
        error("Fixture too large")
    }
    private fun header(id: Int, bytes: Long) = id(id) + size(bytes)
    private fun element(id: Int, bytes: ByteArray) = header(id, bytes.size.toLong()) + bytes
    private fun pcmEntry(number: Int): ByteArray = element(0xae,
        element(0xd7, byteArrayOf(number.toByte())) + element(0x83, byteArrayOf(2)) +
            element(0x86, "A_PCM/INT/LIT".toByteArray()) + element(0xe1,
                element(0xb5, java.nio.ByteBuffer.allocate(8).putDouble(48_000.0).array()) +
                    element(0x9f, byteArrayOf(1)) + element(0x6264, byteArrayOf(16))))
    private fun segment(children: ByteArray) = element(0x1a45dfa3, element(0x4282, "matroska".toByteArray())) +
        element(0x18538067, children)
    private fun track(leaves: ByteArray, missing: Int = 0): ByteArray {
        val entry = header(0xae, leaves.size.toLong() + missing) + leaves
        val tracks = header(0x1654ae6b, entry.size.toLong() + missing) + entry
        val ebml = element(0x1a45dfa3, element(0x4282, "matroska".toByteArray()))
        return ebml + header(0x18538067, tracks.size.toLong() + missing) + tracks
    }
    private fun ByteArray.indexOfSequence(needle: ByteArray): Int =
        (0..size - needle.size).firstOrNull { at -> needle.indices.all { this[at + it] == needle[it] } } ?: -1
}
