@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.common.C
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ForwardingExtractorInput

/** Initial metadata/sniff policies (#726), not sample, codec, track/cue or global heap limits. */
internal class MatroskaLimits(
    val elementBytes: Long = 4L * 1024 * 1024,
    val metadataBytes: Long = 16L * 1024 * 1024,
    val metadataEntries: Int = 4096,
    val sniffBytes: Long = 64L * 1024,
) {
    init { require(elementBytes > 0 && metadataBytes > 0 && metadataEntries > 0 && sniffBytes >= 4) }
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

    fun seek() {
        expectingSize = false; remaining = 0; prefix = 0; restAt = -1
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
            val first = target.size == 8 && offset == 0 && length == 1 && (allowEndOfInput || expectingSize)
            if (first && remaining != 0) refuse(true)
            if (!super.readFully(target, offset, length, allowEndOfInput)) return false
            if (first) start(target[0].toInt() and 255, !allowEndOfInput, position, this.length)
            return true
        }
        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            val rest = remaining != 0 && target.size == 8 && offset == 1 && length == remaining
            if (rest && position != restAt) refuse(true)
            super.readFully(target, offset, length)
            if (rest) {
                var value = prefix
                for (i in offset until offset + length) value = (value shl 8) or (target[i].toLong() and 255)
                remaining = 0
                complete(value, pendingSize, position, this.length)
            }
        }
    }

    private fun start(first: Int, size: Boolean, at: Long, fileLength: Long) {
        if (size != expectingSize || first == 0) refuse(true)
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
    }

    private fun isMetadata(id: Int) = when (id) {
        // Exact pinned string/binary dispatch, excluding media Block/SimpleBlock.
        0x4282, 0x536e, 0x86, 0x22b59c, 0x85, 0x437c,
        0x53ab, 0x41ed, 0x4255, 0x47e2, 0x63a2, 0x7672, 0xa5 -> true
        else -> false
    }
    private fun refuse(malformed: Boolean = false): Nothing = throw StreamMetadataRefusal(malformed)
}
