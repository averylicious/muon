@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractor
import androidx.media3.extractor.ForwardingExtractorsFactory
import androidx.media3.extractor.MpegAudioUtil
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.jpeg.JpegExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ogg.OggExtractor
import java.io.EOFException
import java.io.InterruptedIOException

/** Initial ID3 prefix admission, not a whole-decoder/heap bound. Covers already have a4MiB limit. */
internal const val STREAM_ID3_BYTES = 4 * 1024 * 1024
private const val ID3_HEADER = 10
// Pinned Mp3Extractor1.11.0 searches this far for each leading ID3 header, before MPEG audio.
private const val MP3_TAG_SEARCH_BYTES = 128 * 1024
private const val LARGE_TAG_NOTICE = "This song's embedded information is too large. Use smaller artwork or tags."
private const val INVALID_TAG_NOTICE = "This song has invalid embedded information. Check its tags and retry."

/** ParserException is permanent to Media3's retry policy; never render its diagnostic suffix. */
internal class StreamMetadataRefusal(malformed: Boolean = false) : ParserException(
    if (malformed) INVALID_TAG_NOTICE else LARGE_TAG_NOTICE, null, malformed, C.DATA_TYPE_MEDIA) {
    override val message: String get() = if (contentIsMalformed) INVALID_TAG_NOTICE else LARGE_TAG_NOTICE
}

/** Every candidate needs the initial check: FLAC's sniffer also invokes Id3Peeker before rejecting MP3.
 * Preserve factory ordering, format options, headers and underlying identities used for MP3 seeking.
 * Fragmented read observation supports the app's default flags; its optional forward sidx scan
 * (FLAG_MERGE_FRAGMENTED_SIDX) needs a separate observer adaptation before enabling it.
 */
internal class PlaybackExtractors(factory: ExtractorsFactory = DefaultExtractorsFactory()) : ForwardingExtractorsFactory(factory) {
    override fun createExtractors(): Array<Extractor> = wrap(super.createExtractors())
    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(super.createExtractors(uri, responseHeaders))
    private fun wrap(extractors: Array<Extractor>) = extractors.map { MetadataBoundedExtractor(it) }.toTypedArray<Extractor>()
}

internal class MetadataBoundedExtractor(private val actual: Extractor, private val maximum: Int = STREAM_ID3_BYTES,
    containerBytes: Long = CONTAINER_METADATA_BYTES, containerEntries: Int = CONTAINER_METADATA_ENTRIES,
    private val mp4Limits: Mp4Limits = Mp4Limits()) :
    ForwardingExtractor(actual) {
    init { require(maximum >= ID3_HEADER) }
    // Container metadata parsed after sniffing (#714); other candidates keep only the ID3 prefix check.
    private val flac = if (actual.underlyingImplementation is FlacExtractor)
        FlacMetadataAdmission(containerBytes, containerEntries) else null
    private val ogg = if (actual.underlyingImplementation is OggExtractor)
        OggPacketAdmission(containerBytes, containerEntries) else null
    // MP4 metadata, fragmented table/index retention and MFRA tails are admitted (#720).
    // This is not every codec/decoder/heap path; preserve native factory identities and flags.
    private val mp4 = when (actual.underlyingImplementation) {
        is Mp4Extractor -> Mp4MetadataAdmission(mp4Limits)
        is FragmentedMp4Extractor -> Mp4MetadataAdmission(mp4Limits, fragmented = true)
        else -> null
    }
    private val jpeg = if (actual.underlyingImplementation is JpegExtractor) JpegMotionPhotoAdmission(mp4Limits) else null
    private val fragmented = if (actual.underlyingImplementation is FragmentedMp4Extractor)
        FragmentedMp4Admission(mp4Limits) { rows -> mp4?.admitTailRows(rows) } else null
    private val mp4Sniffer = actual.underlyingImplementation.let { it is Mp4Extractor || it is FragmentedMp4Extractor }
    override fun sniff(input: ExtractorInput): Boolean {
        checkPrefix(input)
        return super.sniff(if (mp4Sniffer) Mp4SniffInput(input, mp4Limits.retainedBytes) else jpeg?.sniffInput(input) ?: input)
    }
    override fun init(output: ExtractorOutput) = super.init(ogg?.output(output) ?: jpeg?.output(output) ?: output)
    // A single-candidate factory can select without sniffing; seek-to-start must recheck too.
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        checkPrefix(input)
        val tailGuarded = fragmented?.input(input) ?: input
        val guarded = flac?.input(input) ?: ogg?.input(input) ?: mp4?.input(tailGuarded) ?: jpeg?.input(tailGuarded) ?: tailGuarded
        return super.read(guarded, seekPosition).also { ogg?.returned() }
    }
    override fun seek(position: Long, timeUs: Long) {
        flac?.seek(position)
        ogg?.seek(position)
        mp4?.seek(position)
        fragmented?.seek()
        jpeg?.seek(position)
        super.seek(position, timeUs)
    }
    private fun checkPrefix(input: ExtractorInput) {
        if (input.position != 0L) return // The pinned extractors only peek leading ID3 at position0.
        val search = actual.underlyingImplementation is Mp3Extractor
        val header = ByteArray(ID3_HEADER)
        var fresh = true
        var searched = 0
        input.resetPeekPosition()
        try {
            while (true) {
                // Already-buffered tiny tags can otherwise loop without another interruptible read.
                if (Thread.interrupted()) throw InterruptedIOException("Embedded information read cancelled")
                try {
                    if (fresh) {
                        if (!input.peekFully(header, 0, header.size, true)) return
                    } else {
                        header.copyInto(header, 0, 1, header.size)
                        if (!input.peekFully(header, header.lastIndex, 1, true)) return
                    }
                } catch (_: EOFException) { return } // Leave short input handling to its extractor.
                if (header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) {
                    if ((6..9).any { header[it].toInt() and 0x80 != 0 }) throw StreamMetadataRefusal(true)
                    var bytes = 0
                    for (i in 6..9) bytes = (bytes shl 7) or (header[i].toInt() and 0x7f)
                    // Check declared END before any body peek/skip can grow ExtractorInput's buffer,
                    // or Id3Peeker can allocate its full tag array. Includes earlier tags/padding.
                    if (input.peekPosition + bytes > maximum.toLong()) throw StreamMetadataRefusal()
                    input.advancePeekPosition(bytes)
                    fresh = true
                    searched = 0
                    continue
                }
                val mpegHeader = ((header[0].toInt() and 255) shl 24) or
                    ((header[1].toInt() and 255) shl 16) or ((header[2].toInt() and 255) shl 8) or (header[3].toInt() and 255)
                if (!search || MpegAudioUtil.getFrameSize(mpegHeader) != C.LENGTH_UNSET || searched++ >= MP3_TAG_SEARCH_BYTES) return
                if (input.peekPosition > maximum.toLong() + ID3_HEADER) throw StreamMetadataRefusal()
                fresh = false
            }
        } finally { input.resetPeekPosition() }
    }
}
