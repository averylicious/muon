@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.source.BundledExtractorsAdapter
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ForwardingExtractorsFactory
import androidx.media3.extractor.ForwardingExtractorInput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.jpeg.JpegExtractor
import androidx.media3.extractor.heif.HeifExtractor
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Native nested MP4 admission controls; no decoder, HTTP request or heap-pressure test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class NestedMp4AdmissionTest {
    @Test fun directAndNestedMp4RefuseBeforeTheNativeLeafBody() {
        val declared = 4096
        fun atom(name: String, body: ByteArray) = ByteBuffer.allocate(8 + body.size)
            .putInt(8 + body.size).put(name.toByteArray(Charsets.US_ASCII)).put(body).array()
        val movie = atom("ftyp", "isom".toByteArray() + ByteArray(4) + "isom".toByteArray()) +
            atom("moov", atom("udta", ByteArray(declared - 8)))
        val budget = Mp4Limits(retainedBytes = 128)
        val direct = MetadataBoundedExtractor(Mp4Extractor(), mp4Limits = budget)
        try {
            direct.init(Capture())
            val source = DefaultExtractorInput(reader(movie, 0) { _, _, _ -> }, 0, movie.size.toLong())
            assertThrows(StreamMetadataRefusal::class.java) {
                repeat(64) { direct.read(source, PositionHolder()) }
            }
        } finally { direct.release() }

        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/">""" +
            """<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
            """<rdf:Description xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" """ +
            """GCamera:MotionPhoto="1" GCamera:MicroVideoOffset="${movie.size}"/>""" +
            "</rdf:RDF></x:xmpmeta>"
        val app1 = "http://ns.adobe.com/xap/1.0/".toByteArray() + byteArrayOf(0) + xmp.toByteArray() + byteArrayOf(0)
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte()) +
            byteArrayOf(((app1.size + 2) ushr 8).toByte(), (app1.size + 2).toByte()) + app1 +
            byteArrayOf(0xff.toByte(), 0xda.toByte()) + movie
        assertTrue(bytes.size < 8192)
        // Mirror PlaybackExtractors' two public factory overloads; only inject a modest budget.
        val factory = object : ForwardingExtractorsFactory(DefaultExtractorsFactory()) {
            private fun wrap(items: Array<Extractor>): Array<Extractor> =
                items.map { MetadataBoundedExtractor(it, mp4Limits = budget) }.toTypedArray()
            override fun createExtractors(): Array<Extractor> = wrap(super.createExtractors())
            override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
                wrap(super.createExtractors(uri, responseHeaders))
        }
        val adapter = BundledExtractorsAdapter(factory)
        val uri = Uri.parse("http://fixture.invalid/stream/1")
        val headers = mapOf("Content-Type" to listOf(MimeTypes.IMAGE_JPEG))
        val stop = IOException("Controlled stop before nested native leaf body")
        var observed = 0
        fun source(at: Long) = reader(bytes, at) { target, offset, length ->
            if (target.size == declared && offset == 8 && length == declared - 8) {
                observed = target.size
                throw stop
            }
        }
        val output = Capture()
        try {
            adapter.init(source(0), uri, headers, 0, bytes.size.toLong(), output)
            assertThrows(StreamMetadataRefusal::class.java) {
                val holder = PositionHolder()
                repeat(64) {
                    when (adapter.read(holder)) {
                        Extractor.RESULT_SEEK -> adapter.init(source(holder.position), uri, headers,
                            holder.position, bytes.size.toLong(), output)
                        Extractor.RESULT_END_OF_INPUT -> fail("Ended before nested native allocation")
                    }
                }
                fail("Nested native allocation was not observed")
            }
            assertEquals(0, observed)
            assertEquals(MimeTypes.IMAGE_JPEG, output.format?.containerMimeType)
        } finally { adapter.release() }

        val heicBytes = heic(movie)
        for (bounded in listOf(false, true)) {
            val heicAdapter = BundledExtractorsAdapter(if (bounded) factory else DefaultExtractorsFactory())
            val heicStop = IOException("Controlled HEIC inner leaf body")
            var heicAllocated = 0
            fun heicSource(at: Long) = reader(heicBytes, at) { target, offset, length ->
                if (target.size == declared && offset == 8 && length == declared - 8) {
                    heicAllocated = target.size
                    throw heicStop
                }
            }
            try {
                heicAdapter.init(heicSource(0), uri, mapOf("Content-Type" to listOf(MimeTypes.IMAGE_HEIF)),
                    0, heicBytes.size.toLong(), Capture())
                val failure = assertThrows(IOException::class.java) {
                    val holder = PositionHolder()
                    repeat(64) {
                        when (heicAdapter.read(holder)) {
                            Extractor.RESULT_SEEK -> heicAdapter.init(heicSource(holder.position), uri,
                                mapOf("Content-Type" to listOf(MimeTypes.IMAGE_HEIF)), holder.position,
                                heicBytes.size.toLong(), Capture())
                            Extractor.RESULT_END_OF_INPUT -> fail("HEIC ended before its inner leaf")
                        }
                    }
                    fail("HEIC inner leaf was not reached")
                }
                if (bounded) {
                    assertTrue(failure is StreamMetadataRefusal)
                    assertEquals(0, heicAllocated)
                } else {
                    assertSame(heicStop, failure)
                    assertEquals(declared, heicAllocated)
                }
            } finally { heicAdapter.release() }
        }
    }

    @Test fun validNestedAudioPreservesNativeMapSeekAndRestart() {
        val movie = requireNotNull(javaClass.getResourceAsStream("/mp4-admission/control.m4a")).use { it.readBytes() }
        for ((bytes, mime) in listOf(motion(movie) to MimeTypes.IMAGE_JPEG, heic(movie) to MimeTypes.IMAGE_HEIF)) {
            val adapter = BundledExtractorsAdapter(PlaybackExtractors())
            val output = Capture()
            val native = BundledExtractorsAdapter(DefaultExtractorsFactory())
            val expected = Capture()
            val uri = Uri.parse("http://fixture.invalid/stream/1")
            val headers = mapOf("Content-Type" to listOf(mime))
            fun drive(at: Long, interrupted: Boolean = false, target: BundledExtractorsAdapter = adapter,
                capture: Capture = output) {
                var source = reader(bytes, at) { _, _, _ -> }
                var physical = at
                var interruptedOnce = false
                val nestedStart = bytes.size - movie.size
                val stop = IOException("Controlled inner-sniff reopen")
                if (interrupted) {
                    val original = source
                    source = DataReader { target, offset, length ->
                        // Outer motion-photo sniffs stop before the embedded MP4 payload. Interrupt the actual inner
                        // Sniffer after its ftyp peek, while its delivered read position is still start.
                        if (!interruptedOnce && physical >= nestedStart + 20) {
                            interruptedOnce = true
                            throw stop
                        }
                        original.read(target, offset, length).also { if (it > 0) physical += it }
                    }
                }
                target.init(source, uri, headers, at, bytes.size.toLong(), capture)
                val holder = PositionHolder()
                var retried = false
                repeat(512) {
                    try {
                        when (target.read(holder)) {
                            Extractor.RESULT_END_OF_INPUT -> {
                                if (interrupted) assertTrue("Controlled failure must actually occur", retried)
                                return
                            }
                            Extractor.RESULT_SEEK -> target.init(reader(bytes, holder.position) { _, _, _ -> },
                                uri, headers, holder.position, bytes.size.toLong(), capture)
                        }
                    } catch (failure: IOException) {
                        if (failure !== stop) throw failure
                        assertFalse(retried); retried = true
                        // A real interrupted source reopens at the adapter's actual delivered position.
                        val position = target.currentInputPosition
                        target.init(reader(bytes, position) { _, _, _ -> }, uri, headers,
                            position, bytes.size.toLong(), capture)
                    }
                }
                fail("Nested control did not finish")
            }
            try {
                drive(0, target = native, capture = expected)
                assertEquals(MimeTypes.AUDIO_AAC, expected.format?.sampleMimeType)
                drive(0, interrupted = true)
                assertEquals(MimeTypes.AUDIO_AAC, output.format?.sampleMimeType)
                val nativeMap = requireNotNull(expected.map)
                val guardedMap = requireNotNull(output.map)
                assertTrue(nativeMap.isSeekable)
                assertEquals(nativeMap.isSeekable, guardedMap.isSeekable)
                assertEquals(nativeMap.durationUs, guardedMap.durationUs)
                assertTrue(nativeMap.durationUs > 0)
                for (time in listOf(0L, nativeMap.durationUs / 2, nativeMap.durationUs - 1)) {
                    val nativePoints = nativeMap.getSeekPoints(time)
                    val guardedPoints = guardedMap.getSeekPoints(time)
                    assertEquals(nativePoints.first.timeUs, guardedPoints.first.timeUs)
                    assertEquals(nativePoints.first.position, guardedPoints.first.position)
                    assertEquals(nativePoints.second.timeUs, guardedPoints.second.timeUs)
                    assertEquals(nativePoints.second.position, guardedPoints.second.position)
                }
                val point = requireNotNull(output.map).getSeekPoints(0).first
                assertTrue(point.position >= bytes.size - movie.size)
                adapter.seek(point.position, point.timeUs)
                drive(point.position)
                val formatsBeforeRestart = output.aacFormats
                output.format = null // A stale format must not make the restart assertion pass.
                output.map = null
                adapter.seek(0, 0)
                drive(0)
                assertTrue("Restart must publish fresh AAC format", output.aacFormats > formatsBeforeRestart)
                assertEquals(MimeTypes.AUDIO_AAC, output.format?.sampleMimeType)
                assertEquals(nativeMap.durationUs, requireNotNull(output.map).durationUs)
            } finally { try { adapter.release() } finally { native.release() } }
        }
    }

    @Test fun nestedLeafBodyReopensAtTheSameAdmittedOffset() {
        val movie = requireNotNull(javaClass.getResourceAsStream("/mp4-admission/control.m4a")).use { it.readBytes() }
        for ((bytes, raw) in listOf<Pair<ByteArray, Extractor>>(motion(movie) to JpegExtractor(), heic(movie) to HeifExtractor())) {
            val nestedStart = bytes.size - movie.size
            val stop = IOException("Controlled leaf-body reopen before delivery")
            var interrupted = false
            fun source(at: Long) = object : ForwardingExtractorInput(DefaultExtractorInput(
                reader(bytes, at) { _, _, _ -> }, at, bytes.size.toLong())) {
                override fun readFully(target: ByteArray, offset: Int, length: Int) {
                    if (!interrupted && position >= nestedStart && offset == 8 && length > 0) {
                        interrupted = true
                        throw stop // The native atom header is consumed; body delivery has not begun.
                    }
                    super.readFully(target, offset, length)
                }
            }
            val extractor = MetadataBoundedExtractor(raw)
            val output = Capture(); var input = source(0)
            try {
                extractor.init(output)
                val holder = PositionHolder()
                var finished = false
                var reads = 0
                while (!finished && reads++ < 512) {
                    try {
                        when (extractor.read(input, holder)) {
                            Extractor.RESULT_SEEK -> input = source(holder.position)
                            Extractor.RESULT_END_OF_INPUT -> {
                                assertTrue("Actual inner leaf fault must occur", interrupted)
                                assertEquals(MimeTypes.AUDIO_AAC, output.format?.sampleMimeType)
                                assertTrue(requireNotNull(output.map).isSeekable)
                                finished = true
                            }
                        }
                    } catch (failure: IOException) {
                        if (failure !== stop) throw failure
                        input = source(input.position)
                    }
                }
                assertTrue("Nested body control did not finish", finished)
            } finally { extractor.release() }
        }
    }

    @Test fun innerSnifferRefusesFtypBeforeItsPayloadArray() {
        val movie = atom("ftyp", "isom".toByteArray() + ByteArray(4) + "isom".toByteArray() + ByteArray(1000)) +
            atom("moov", ByteArray(8))
        for ((bytes, raw) in listOf<Pair<ByteArray, Extractor>>(motion(movie) to JpegExtractor(), heic(movie) to HeifExtractor())) {
            var nativePayload = false
            val extractor = MetadataBoundedExtractor(raw, mp4Limits = Mp4Limits(retainedBytes = 128))
            val input = object : ForwardingExtractorInput(DefaultExtractorInput(reader(bytes, 0) { _, _, _ -> },
                0, bytes.size.toLong())) {
                override fun peekFully(target: ByteArray, offset: Int, length: Int) {
                    if (target.size == 1012) nativePayload = true
                    super.peekFully(target, offset, length)
                }
            }
            try {
                extractor.init(Capture())
                assertThrows(StreamMetadataRefusal::class.java) {
                    val holder = PositionHolder()
                    repeat(64) {
                        when (extractor.read(input, holder)) {
                            Extractor.RESULT_SEEK -> fail("Adjacent fixture should not need a source seek")
                            Extractor.RESULT_END_OF_INPUT -> fail("Ended before inner ftyp admission")
                        }
                    }
                }
                assertFalse(nativePayload)
            } finally { extractor.release() }
        }
    }

    @Test fun bothJpegSniffPassesBoundPreambleAndPlainImagesRemainNative() {
        val segment = byteArrayOf(0xff.toByte(), 0xe2.toByte(), 0, 34) + ByteArray(32)
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte()) + segment + segment + segment + segment +
            byteArrayOf(0xff.toByte(), 0xda.toByte())
        val admission = MotionPhotoMp4Admission(Mp4Limits(), preambleBytes = 64)
        fun source() = DefaultExtractorInput(reader(bytes, 0) { _, _, _ -> }, 0, bytes.size.toLong())
        val first = JpegExtractor()
        try {
            assertThrows(StreamMetadataRefusal::class.java) { first.sniff(admission.sniffInput(source())) }
        } finally { first.release() }
        val second = JpegExtractor()
        try {
            second.init(admission.output(Capture()))
            assertThrows(StreamMetadataRefusal::class.java) { second.read(admission.input(source()), PositionHolder()) }
        } finally { second.release() }
        val cancellable = MotionPhotoMp4Admission(Mp4Limits(), preambleBytes = 1024)
        var advances = 0
        val cached = source()
        cached.advancePeekPosition(bytes.size)
        cached.resetPeekPosition()
        val buffered = object : ForwardingExtractorInput(cached) {
            override fun advancePeekPosition(length: Int) {
                super.advancePeekPosition(length)
                if (++advances == 2) Thread.currentThread().interrupt()
            }
        }
        val cancelled = JpegExtractor()
        try {
            assertThrows(InterruptedIOException::class.java) { cancelled.sniff(cancellable.sniffInput(buffered)) }
            assertEquals("Cancellation is triggered from the native header loop", 2, advances)
        } finally { Thread.interrupted(); cancelled.release() }
        // A tiny ordinary image still follows the native single-sample route (no image decoding).
        val plain = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xda.toByte(), 1, 2, 3)
        val guarded = MetadataBoundedExtractor(JpegExtractor())
        val output = Capture(); var input = DefaultExtractorInput(reader(plain, 0) { _, _, _ -> }, 0, plain.size.toLong())
        try {
            assertTrue(guarded.sniff(input)); input.resetPeekPosition(); guarded.init(output)
            val holder = PositionHolder()
            repeat(16) {
                if (guarded.read(input, holder) == Extractor.RESULT_END_OF_INPUT) {
                    assertEquals(MimeTypes.IMAGE_JPEG, output.format?.sampleMimeType)
                    return
                }
            }
            fail("Plain image did not finish")
        } finally { guarded.release() }
    }

    @Test fun heicPreambleSniffsAreBoundedAndPlainImageRouteIsPreserved() {
        val bytes = heic(ByteArray(0), padding = 256)
        fun source() = DefaultExtractorInput(reader(bytes, 0) { _, _, _ -> }, 0, bytes.size.toLong())
        for (firstRead in listOf(false, true)) {
            val admission = MotionPhotoMp4Admission(Mp4Limits(), preambleBytes = 64, metadataBeforeSniff = true)
            val raw = HeifExtractor()
            try {
                raw.init(admission.output(Capture()))
                assertThrows(StreamMetadataRefusal::class.java) {
                    if (firstRead) raw.read(admission.input(source()), PositionHolder())
                    else raw.sniff(admission.sniffInput(source()))
                }
            } finally { raw.release() }
        }
        val cancellable = MotionPhotoMp4Admission(Mp4Limits(), preambleBytes = 1024, metadataBeforeSniff = true)
        val cached = source(); cached.advancePeekPosition(bytes.size); cached.resetPeekPosition()
        var advances = 0
        val buffered = object : ForwardingExtractorInput(cached) {
            override fun advancePeekPosition(length: Int) {
                super.advancePeekPosition(length)
                if (++advances == 2) Thread.currentThread().interrupt()
            }
        }
        val cancelled = HeifExtractor()
        try {
            assertThrows(InterruptedIOException::class.java) { cancelled.sniff(cancellable.sniffInput(buffered)) }
            assertEquals(2, advances)
        } finally { Thread.interrupted(); cancelled.release() }

        // A tiny HEIC-signalled payload follows native single-sample output; no image decoding.
        val plain = heic(null)
        val guarded = MetadataBoundedExtractor(HeifExtractor())
        val output = Capture()
        val input = DefaultExtractorInput(reader(plain, 0) { _, _, _ -> }, 0, plain.size.toLong())
        try {
            assertTrue(guarded.sniff(input)); input.resetPeekPosition(); guarded.init(output)
            val holder = PositionHolder()
            repeat(16) {
                if (guarded.read(input, holder) == Extractor.RESULT_END_OF_INPUT) {
                    assertEquals(MimeTypes.IMAGE_HEIF, output.format?.sampleMimeType)
                    return
                }
            }
            fail("Plain HEIC did not finish")
        } finally { guarded.release() }
    }

    private fun atom(name: String, body: ByteArray) = ByteBuffer.allocate(8 + body.size)
        .putInt(8 + body.size).put(name.toByteArray(Charsets.US_ASCII)).put(body).array()
    private fun motion(movie: ByteArray): ByteArray {
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" GCamera:MotionPhoto="1" GCamera:MicroVideoOffset="${movie.size}"/></rdf:RDF></x:xmpmeta>"""
        val body = "http://ns.adobe.com/xap/1.0/".toByteArray() + byteArrayOf(0) + xmp.toByteArray() + byteArrayOf(0)
        return byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte(),
            ((body.size + 2) ushr 8).toByte(), (body.size + 2).toByte()) + body +
            byteArrayOf(0xff.toByte(), 0xda.toByte()) + movie
    }

    private fun heic(movie: ByteArray?, padding: Int = 0): ByteArray =
        atom("ftyp", "heic".toByteArray() + ByteArray(4) + "heic".toByteArray()) +
            (if (padding == 0) ByteArray(0) else atom("free", ByteArray(padding))) +
            (movie?.let { atom("mpvd", it) } ?: ByteArray(0))

    private class Capture : ExtractorOutput {
        var format: Format? = null
        var aacFormats = 0
        var map: SeekMap? = null
        override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput by DiscardingTrackOutput() {
            override fun format(format: Format) {
                this@Capture.format = format
                if (format.sampleMimeType == MimeTypes.AUDIO_AAC) aacFormats++
            }
        }
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) { map = seekMap }
    }
    private fun reader(bytes: ByteArray, start: Long, inspect: (ByteArray, Int, Int) -> Unit): DataReader {
        var at = start.toInt()
        return DataReader { target, offset, length ->
            inspect(target, offset, length)
            if (length == 0) 0
            else if (at >= bytes.size) C.RESULT_END_OF_INPUT
            else minOf(length, bytes.size - at).also { count ->
                bytes.copyInto(target, offset, at, at + count); at += count
            }
        }
    }
}
