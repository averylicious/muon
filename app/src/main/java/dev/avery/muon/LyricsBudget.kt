package dev.avery.muon

import java.io.IOException

internal const val LYRICS_RESPONSE_BYTES = 512 * 1024
internal const val LYRICS_TEXT_UNITS = 64 * 1024
internal const val LYRICS_LINES = 2_048

/** Fixed text: never stringify or reflect server content into an error. */
internal class LyricsResourceLimit : IOException(
    "Lyrics exceed Muon's limits (512 KiB response, 64 Ki UTF-16 units or 2,048 lines). Check stored lyrics in Tauon and retry.")

/** Preserve all accepted words; bound text/layout input before it reaches selectable Compose text. */
internal fun requireLyricsBudget(text:String):String {
    if(text.length>LYRICS_TEXT_UNITS) throw LyricsResourceLimit()
    var lines=1
    var previous='\u0000'
    for(character in text) {
        when(character) {
            '\r','\u0085','\u2028','\u2029' -> lines++
            '\n' -> if(previous!='\r') lines++ // CRLF is one separator.
        }
        if(lines>LYRICS_LINES) throw LyricsResourceLimit()
        previous=character
    }
    return text
}
