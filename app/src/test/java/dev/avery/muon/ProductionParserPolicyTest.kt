@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.exoplayer.source.BundledExtractorsAdapter
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pinned legacy byte reads enforce limits in tests but not by default in production. Check the
 * complete supplied factory under both policies, using original tiny silence fixtures and reopen.
 * This is demux compatibility, not audio decoding, Binder, performance or heap-pressure proof.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ProductionParserPolicyTest {
    @Test fun suppliedFactoryKeepsSmallFixtureDemuxAndReopenUnderProductionEquivalentPolicy() {
        for (path in listOf("avi-admission/control.avi", "flv-admission/control.flv",
            "mkv-admission/control.mka", "mkv-admission/control-vorbis.mka", "mkv-admission/control-opus.mka",
            "container-admission/native.flac", "container-admission/vorbis.ogg", "container-admission/opus.ogg",
            "mp4-admission/control.m4a", "mp4-admission/fragmented.m4a", "mp4-admission/alac.m4a", "mp4-admission/pcm.mov")) {
            assertEquals(path, demux(path, null), demux(path, false))
        }
    }

    private data class Summary(val audioTypes: Set<String>, val samples: Int, val duration: Long)

    private fun demux(path: String, enforce: Boolean?): Summary {
        ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(enforce)
        val adapter = BundledExtractorsAdapter(PlaybackExtractors())
        try {
            val bytes = requireNotNull(javaClass.getResourceAsStream("/$path")).use { it.readBytes() }
            val audio = mutableSetOf<String>()
            var samples = 0
            var map: SeekMap? = null
            val output = object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput by DiscardingTrackOutput() {
                    override fun format(format: Format) { format.sampleMimeType?.takeIf { it.startsWith("audio/") }?.let(audio::add) }
                    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) { samples++ }
                }
                override fun endTracks() = Unit
                override fun seekMap(seekMap: SeekMap) { map = seekMap }
            }
            fun open(start: Long) {
                require(start in 0..bytes.size.toLong())
                var position = start.toInt()
                val reader = DataReader { target, offset, length ->
                    if (length == 0) 0 else if (position == bytes.size) C.RESULT_END_OF_INPUT
                    else minOf(length, bytes.size - position).also {
                        bytes.copyInto(target, offset, position, position + it); position += it
                    }
                }
                adapter.init(reader, Uri.parse("http://fixture.invalid/$path"), emptyMap(), start, bytes.size.toLong(), output)
            }
            fun drain() {
                val holder = PositionHolder()
                repeat(20_000) {
                    when (adapter.read(holder)) {
                        Extractor.RESULT_CONTINUE -> Unit
                        Extractor.RESULT_SEEK -> open(holder.position)
                        else -> return
                    }
                }
                error("Fixture extractor did not finish: $path")
            }
            open(0); drain()
            assertTrue("No audio format: $path", audio.isNotEmpty())
            assertTrue("No samples: $path", samples > 0)
            val first = samples
            adapter.seek(0, 0); open(0); drain()
            assertTrue("No samples after reopen: $path", samples > first)
            return Summary(audio, samples, requireNotNull(map).durationUs)
        } finally {
            try { adapter.release() } finally { ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(null) }
        }
    }
}
