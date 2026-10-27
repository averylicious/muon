@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.common.C
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ForwardingExtractorInput
import java.io.EOFException
import java.io.IOException

/** Requested sniff lookahead only; native buffer growth headroom/old arrays can exceed it. */
internal const val AC4_SNIFF_BYTES = 16L * 1024 * 1024

/** AC-4's search-position limit does not bound frame-body peeks. Preserve the native sniffer,
 * returning false at this resource limit so another format (including MP3 with junk) can try.
 * Known-length truncation retains EOF semantics. Reads/samples/whole-song size are unchanged.
 */
internal fun sniffAc4(actual: Extractor, input: ExtractorInput, maximum: Long): Boolean = try {
    actual.sniff(Ac4SniffInput(input, maximum))
} catch (_: Ac4SniffLimit) { false }

private class Ac4SniffLimit : IOException("AC-4 format detection lookahead limit")

private class Ac4SniffInput(input: ExtractorInput, private val maximum: Long) : ForwardingExtractorInput(input) {
    init { require(maximum > 0) }
    private val start = input.peekPosition
    private fun admit(length: Int) {
        val used = peekPosition - start
        // Negative frame-body advances are malformed, not a way to rewind the resource census.
        if (length < 0 || used < 0 || used > maximum || length.toLong() > maximum - used) throw Ac4SniffLimit()
        val known = this.length
        if (known != C.LENGTH_UNSET.toLong() && (peekPosition > known || length.toLong() > known - peekPosition))
            throw EOFException()
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
