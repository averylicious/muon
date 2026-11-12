@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.net.Uri
import android.util.SparseArray
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ForwardingExtractorInput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Raw pinned dependency behavior for #720, not desired guard behavior or a heap-pressure test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class FragmentedMp4BoundaryTest {
    @Test fun defaultFragmentSelectionBuildsDeclaredTablesBeforeReadingAnyMedia() {
        val original = requireNotNull(javaClass.getResourceAsStream("/mp4-admission/fragmented.m4a"))
            .use { it.readBytes() }
        val moof = box(original, "moof")
        val traf = box(original, "traf")
        val trun = box(original, "trun")
        val mdat = box(original, "mdat")
        // The original fragment has four per-sample duration words. Replace those with tfhd's
        // existing defaults, leaving just a20-byte trun. It needs no per-sample body for its count.
        assertEquals(36, int(original, trun))
        val removed = 16
        val changed = original.copyOfRange(0, trun + 20) + original.copyOfRange(trun + 36, original.size)
        put(changed, moof, int(original, moof) - removed)
        put(changed, traf, int(original, traf) - removed)
        put(changed, trun, 20)
        put(changed, trun + 8, 1) // data_offset present; durations/sizes/flags come from tfhd.
        put(changed, trun + 12, 4096)
        put(changed, trun + 16, int(original, trun + 16) - removed)
        val mediaHeader = (mdat - removed).toLong()
        assertEquals(original.size - removed, changed.size)

        for ((bytes, count, stop) in listOf(
            Triple(original, 4, mdat.toLong()), Triple(changed, 4096, mediaHeader))) {
            fun open(at: Long) = object : ForwardingExtractorInput(DefaultExtractorInput(reader(bytes, at), at, bytes.size.toLong())) {
                override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
                    if (position == stop && offset == 0 && length == 8) throw BeforeMedia()
                    return super.readFully(target, offset, length, allowEndOfInput)
                }
            }
            var input = open(0)
            val candidates = DefaultExtractorsFactory().createExtractors(Uri.parse("http://fixture.invalid/fragmented.m4a"), emptyMap())
            val selected = candidates.first { candidate ->
                try { candidate.sniff(input) } finally { input.resetPeekPosition() }
            }
            assertTrue("Default factory must select the fragmented path", selected is FragmentedMp4Extractor)
            var format: Format? = null
            selected.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput by DiscardingTrackOutput() {
                    override fun format(value: Format) { format = value }
                }
                override fun endTracks() {}
                override fun seekMap(seekMap: SeekMap) {}
            })
            try {
                assertThrows(BeforeMedia::class.java) {
                    val position = PositionHolder()
                    repeat(40) {
                        // DefaultExtractorsFactory enables MFRA seek-map reads. Follow those real
                        // source reopens rather than changing flags to avoid the production path.
                        when (selected.read(input, position)) {
                            Extractor.RESULT_CONTINUE -> Unit
                            Extractor.RESULT_SEEK -> input = open(position.position)
                            else -> fail("EOF before the selected fragment's media boundary")
                        }
                    }
                    fail("Extractor never reached the media boundary")
                }
                assertEquals(MimeTypes.AUDIO_AAC, format?.sampleMimeType)
                // Inspect actual parser state because allocation occurs before sample output. This
                // deliberately pins dependency internals; an upstream fix may change this raw proof.
                val bundlesField = FragmentedMp4Extractor::class.java.getDeclaredField("trackBundles")
                    .apply { isAccessible = true }
                val bundles = bundlesField.get(selected) as SparseArray<*>
                val bundle = requireNotNull(bundles.valueAt(0))
                val fragment = bundle.javaClass.getDeclaredField("fragment").apply { isAccessible = true }
                    .get(bundle)
                fun field(name: String) = fragment.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(fragment)
                assertEquals(count, field("sampleCount"))
                for (name in listOf("sampleSizeTable", "samplePresentationTimesUs", "sampleIsSyncFrameTable", "sampleHasSubsampleEncryptionTable")) {
                    assertEquals(name, count * 125 / 100, java.lang.reflect.Array.getLength(requireNotNull(field(name))))
                }
                assertEquals(stop, input.position) // Neither mdat's header nor audio body was read.
            } finally { candidates.forEach { it.release() } }
        }
    }

    private class BeforeMedia : IOException()
    private fun reader(bytes: ByteArray, start: Long): DataReader {
        var at = start.toInt()
        return DataReader { target, offset, length ->
            if (length == 0) 0 else if (at == bytes.size) C.RESULT_END_OF_INPUT
            else minOf(length, bytes.size - at).also { bytes.copyInto(target, offset, at, at + it); at += it }
        }
    }
    private fun int(bytes: ByteArray, at: Int) = ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.BIG_ENDIAN).int
    private fun put(bytes: ByteArray, at: Int, value: Int) { ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.BIG_ENDIAN).putInt(value) }
    private fun box(bytes: ByteArray, type: String): Int {
        fun find(start: Int, end: Int): Int? {
            var at = start
            while (at < end) {
                val size = int(bytes, at); check(size >= 8 && size <= end - at)
                val name = String(bytes, at + 4, 4, Charsets.US_ASCII)
                if (name == type) return at
                if (name in setOf("moov", "moof", "traf")) find(at + 8, at + size)?.let { return it }
                at += size
            }
            return null
        }
        return requireNotNull(find(0, bytes.size)) { type }
    }
}
