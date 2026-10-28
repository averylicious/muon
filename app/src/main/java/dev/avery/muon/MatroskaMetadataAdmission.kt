@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.common.C
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ForwardingExtractorInput

/** Metadata/sniff/retention policies (#726), not all codec/sample or global heap limits. */
internal class MatroskaLimits(
    val elementBytes: Long = 4L * 1024 * 1024,
    val metadataBytes: Long = 16L * 1024 * 1024,
    val metadataEntries: Int = 4096,
    val sniffBytes: Long = 64L * 1024,
    val tracks: Int = 128,
    val chapters: Int = 4096,
    val cues: Int = 65_536,
    val nativeMasters: Int = 4096,
) {
    init { require(elementBytes > 0 && metadataBytes > 0 && metadataEntries > 0 && sniffBytes >= 4 &&
        tracks > 0 && chapters > 0 && cues > 0 && nativeMasters > 0) }
}

/**
 * Observe the actual pinned DefaultEbmlReader/VarintReader ID and size reads. Their distinct
 * first-byte allowEndOfInput calls distinguish element headers from Block/lacing varints.
 * Admission completes before native readString/binaryElement allocates its declared payload.
 * Keep the actual factory extractor, flags, subtitle settings and output behavior unchanged.
 */
internal class MatroskaMetadataAdmission(private val limits: MatroskaLimits = MatroskaLimits()) {
    private var expectingSize = false
    private var elementId = 0
    private var pendingSize = false
    private var remaining = 0
    private var prefix = 0L
    private var restAt = -1L
    // Position-keyed high-water costs bound this ledger and avoid charging ordinary seek rereads
    // twice. Keep it on seek: native track/chapter objects may also remain retained after seek.
    private val charged = HashMap<Long, Long>()
    private var total = 0L
    private var payloadId = 0
    private var payloadAt = -1L
    private var payloadSize = 0
    private var vorbis = false
    private var codecPrivate: ByteArray? = null
    private val trackIds = HashSet<Int>()
    private val chapterIds = HashSet<Long>()
    private var cueCount = 0
    // Native output track IDs tell us which Block headers may allocate subtitle scratch. No per-
    // sample ledger/buffer: retain only the already bounded text-track IDs and one pending varint.
    private val textTracks = HashSet<Int>()
    private var textBlockAt = -1L
    private var textTrackPrefix = 0L
    private var textTrackRemaining = 0
    private var sentMap = false
    private val masterEnds = ArrayList<Long>()

    fun output(actual: ExtractorOutput): ExtractorOutput = object : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput {
            if (type == C.TRACK_TYPE_TEXT) {
                if (id !in textTracks && textTracks.size >= limits.tracks) refuse()
                textTracks.add(id)
            } else textTracks.remove(id)
            return actual.track(id, type)
        }
        override fun endTracks() = actual.endTracks()
        override fun seekMap(seekMap: SeekMap) {
            actual.seekMap(seekMap)
            sentMap = true
        }
    }

    fun seek() {
        expectingSize = false; remaining = 0; prefix = 0; restAt = -1
        textBlockAt = -1; textTrackRemaining = 0
        payloadAt = -1 // Retain current track codec state just as native seek retains currentTrack.
        masterEnds.clear() // Native reader.reset clears all master frames.
        if (!sentMap) cueCount = 0 // Native clears only unfinished cue lists on seek.
    }

    // Internal native seeks return after end-master callbacks, sometimes reopening backwards.
    // Reconcile at the old position before that reopen, not only at the next lower header.
    fun returned(position: Long) {
        while (masterEnds.isNotEmpty() && position >= masterEnds.last()) masterEnds.removeAt(masterEnds.lastIndex)
    }

    fun sniffInput(actual: ExtractorInput): ExtractorInput = object : ForwardingExtractorInput(actual) {
        private val origin = actual.position
        private fun admit(length: Int) {
            if (length < 0 || peekPosition < origin || length.toLong() > limits.sniffBytes - (peekPosition - origin)) refuse()
        }
        override fun peekFully(target: ByteArray, offset: Int, length: Int) {
            admit(length); super.peekFully(target, offset, length)
        }
        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            admit(length); return super.peekFully(target, offset, length, allowEndOfInput)
        }
        override fun advancePeekPosition(length: Int) { admit(length); super.advancePeekPosition(length) }
        override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
            admit(length); return super.advancePeekPosition(length, allowEndOfInput)
        }
    }

    fun input(actual: ExtractorInput): ExtractorInput = object : ForwardingExtractorInput(actual) {
        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            val before = position
            val first = target.size == 8 && offset == 0 && length == 1 && (allowEndOfInput || expectingSize)
            if (first && remaining != 0) refuse(true)
            if (!super.readFully(target, offset, length, allowEndOfInput)) return false
            if (first) start(target[0].toInt() and 255, !allowEndOfInput, position, this.length)
            observeTextBlockTrack(target, offset, length, before)
            return true
        }
        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            val before = position
            val rest = remaining != 0 && target.size == 8 && offset == 1 && length == remaining
            if (rest && position != restAt) refuse(true)
            super.readFully(target, offset, length)
            if (rest) {
                var value = prefix
                for (i in offset until offset + length) value = (value shl 8) or (target[i].toLong() and 255)
                remaining = 0
                complete(value, pendingSize, position, this.length)
            } else if (offset == 0 && length == payloadSize &&
                payloadAt >= 0 && position == payloadAt + payloadSize &&
                (target.size == payloadSize || (target.size == 8 && isCensusInteger(payloadId)))) {
                deliveredPayload(target)
            }
            observeTextBlockTrack(target, offset, length, before)
        }
    }

    // Pinned MatroskaExtractor reads a Block's track number through VarintReader's fixed8-byte
    // scratch before allocating subtitleSample. Observe those successful reads without peeking,
    // rewriting data, or imposing an audio/Segment/Cluster length cap. The remaining Block bytes
    // (timestamps/flags/laces/sample payload) still go straight to the native extractor.
    private fun observeTextBlockTrack(target: ByteArray, offset: Int, length: Int, before: Long) {
        if (textBlockAt < 0 || target.size != 8) return
        if (before == textBlockAt && offset == 0 && length == 1) {
            val first = target[0].toInt() and 255
            val width = Integer.numberOfLeadingZeros(first) - 24 + 1
            if (width !in 1..8) refuse(true)
            textTrackPrefix = (first and (0xff ushr width)).toLong()
            textTrackRemaining = width - 1
        } else if (before > textBlockAt && before - textBlockAt == 1L && offset == 1 &&
            textTrackRemaining > 0 && length == textTrackRemaining) {
            for (i in offset until offset + length)
                textTrackPrefix = (textTrackPrefix shl 8) or (target[i].toLong() and 255)
            textTrackRemaining = 0
        } else return
        if (textTrackRemaining == 0) {
            textBlockAt = -1
            // Match the pinned native cast, including its accepted default/missing TrackNumber0.
            if (textTrackPrefix.toInt() in textTracks) refuse()
        }
    }

    private fun start(first: Int, size: Boolean, at: Long, fileLength: Long) {
        if (size != expectingSize || first == 0) refuse(true)
        if (!size) returned(at - 1)
        val width = Integer.numberOfLeadingZeros(first) - 24 + 1
        if (width > if (size) 8 else 4) refuse(true) // Do not silently enter native ID resync.
        prefix = (if (size) first and (0xff ushr width) else first).toLong()
        pendingSize = size
        remaining = width - 1
        restAt = at
        if (remaining == 0) complete(prefix, size, at, fileLength)
    }

    private fun complete(value: Long, size: Boolean, at: Long, fileLength: Long) {
        if (!size) {
            elementId = value.toInt(); expectingSize = true
            return
        }
        expectingSize = false
        payloadAt = -1
        textBlockAt = -1; textTrackRemaining = 0
        if ((elementId == 0xa1 || elementId == 0xa3) && value > limits.elementBytes && textTracks.isNotEmpty())
            textBlockAt = at
        if (isMaster(elementId)) {
            if (at > Long.MAX_VALUE - value) refuse(true)
            if (masterEnds.size >= limits.nativeMasters) refuse()
            masterEnds.add(at + value)
        }
        if (elementId == 0xae) {
            vorbis = false; codecPrivate = null
            admitTrack(0) // Native Track default when no TrackNumber is supplied.
        }
        if (elementId == 0xb7 && !sentMap) {
            if (cueCount >= limits.cues) refuse()
            cueCount++
        }
        if (isCensusInteger(elementId) && value <= 8) {
            payloadId = elementId; payloadAt = at; payloadSize = value.toInt()
            return // Native fixed8-byte integer target, no declared-size payload allocation.
        }
        if (elementId == 0x86) vorbis = false // Native empty string needs no payload read.
        if (!isMetadata(elementId)) return // No whole Segment/Cluster/audio-block length cap.
        if (value > Int.MAX_VALUE || at > Long.MAX_VALUE - value) refuse(true)
        if (value > limits.elementBytes) refuse()
        if (fileLength != C.LENGTH_UNSET.toLong() && at + value > fileLength) refuse(true)
        // Native SeekID uses a fixed4-byte target with offset4-size, unlike other binary leaves.
        if (elementId == 0x53ab && value > 4) refuse(true)
        val previous = charged[at]
        if (previous == null && charged.size >= limits.metadataEntries) refuse()
        val growth = maxOf(0L, value - (previous ?: 0))
        if (growth > limits.metadataBytes - total) refuse()
        total += growth
        charged[at] = maxOf(previous ?: 0, value)
        if (elementId == 0x86 || elementId == 0x63a2) {
            payloadId = elementId; payloadAt = at; payloadSize = value.toInt()
        }
    }

    private fun deliveredPayload(bytes: ByteArray) {
        if (isCensusInteger(payloadId)) {
            var value = 0L
            for (i in 0 until payloadSize) value = (value shl 8) or (bytes[i].toLong() and 255)
            if (payloadId == 0xd7) admitTrack(value.toInt())
            else if (value != 0L && value !in chapterIds) {
                if (chapterIds.size >= limits.chapters) refuse()
                chapterIds.add(value)
            }
            payloadAt = -1
            return
        }
        if (payloadId == 0x86) {
            var trimmed = bytes.size
            while (trimmed > 0 && bytes[trimmed - 1] == 0.toByte()) trimmed--
            val expected = "A_VORBIS"
            vorbis = trimmed == expected.length && expected.indices.all { bytes[it].toInt() == expected[it].code }
        } else codecPrivate = bytes // Same native array, not an additional payload copy.
        payloadAt = -1
        val data = codecPrivate
        if (vorbis && data != null) admitVorbis(data)
    }

    /** Native Xiph lengths precede arraycopy's backing check. Validate actual bytes first. */
    private fun admitVorbis(data: ByteArray) {
        if (data.isEmpty() || data[0] != 2.toByte()) refuse(true)
        var cursor = 1
        fun lace(): Long {
            var size = 0L
            while (true) {
                if (cursor >= data.size) refuse(true)
                val part = data[cursor++].toInt() and 255
                size += part
                if (part != 255) return size
            }
        }
        val info = lace()
        val skip = lace()
        if (cursor >= data.size || data[cursor] != 1.toByte() || info > data.size - cursor) refuse(true)
        cursor += info.toInt()
        if (cursor >= data.size || data[cursor] != 3.toByte() || skip > data.size - cursor) refuse(true)
        cursor += skip.toInt()
        if (cursor >= data.size || data[cursor] != 5.toByte()) refuse(true)
        // Native info/books copies together fit the already admitted codec-private payload.
    }

    // Exact pinned getElementType MASTER set. This mirrors native retained frames, not EBML
    // schema depth: native unknown-size sibling Clusters can accumulate frames until seek/EOF.
    private fun isMaster(id: Int) = when (id) {
        0x1a45dfa3, 0x18538067, 0x1043a770, 0x45b9, 0xb6, 0x8f, 0x80, 0x114d9b74,
        0x4dbb, 0x1549a966, 0x1f43b675, 0x1654ae6b, 0xae, 0x41e4, 0xe1, 0xe0,
        0x6d80, 0x6240, 0x5034, 0x5035, 0x47e7, 0x1c53bb6b, 0xbb, 0xb7, 0xa0,
        0x75a1, 0xa6, 0x7670, 0x55b0, 0x55d0 -> true
        else -> false
    }
    private fun isCensusInteger(id: Int) = id == 0xd7 || id == 0x73c4
    private fun admitTrack(id: Int) {
        if (id !in trackIds) {
            if (trackIds.size >= limits.tracks) refuse()
            trackIds.add(id)
        }
    }

    private fun isMetadata(id: Int) = when (id) {
        // Exact pinned string/binary dispatch, excluding media Block/SimpleBlock.
        0x4282, 0x536e, 0x86, 0x22b59c, 0x85, 0x437c,
        0x53ab, 0x41ed, 0x4255, 0x47e2, 0x63a2, 0x7672, 0xa5 -> true
        else -> false
    }
    private fun refuse(malformed: Boolean = false): Nothing = throw StreamMetadataRefusal(malformed, audioFormat = true)
}
