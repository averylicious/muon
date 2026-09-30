package dev.avery.muon

import java.io.IOException
import org.json.JSONObject
import org.json.JSONTokener

/** API objects are shallow; allow ample extension room without unbounded recursive DOM parsing. */
internal const val TAUON_JSON_MAX_DEPTH = 64

/**
 * Walk tokens iteratively before JSONObject's recursive parser. Use the platform's documented
 * lexical methods so quoted strings, comments and lenient unquoted literals aren't mistaken for
 * containers. JSONTokener is deliberately not subclassed; its internal self-calls aren't an API.
 * This bounds nesting only, not the aggregate heap used by a wide response or several playlists.
 */
internal fun parseTauonJson(text: String): JSONObject {
    val tokens = JSONTokener(text)
    var depth = 0
    while (tokens.more()) {
        val token = tokens.nextClean()
        when (token) {
            '{', '[' -> {
                depth++
                if (depth > TAUON_JSON_MAX_DEPTH) throw IOException("Tauon response nesting exceeds $TAUON_JSON_MAX_DEPTH levels")
            }
            '}', ']' -> {
                depth--
                if (depth < 0) throw IOException("Unbalanced Tauon response")
            }
            '\'', '"' -> tokens.nextString(token)
            ',', ';', ':', '=', '>', '\\' -> Unit
            '/', '\u000c' -> throw IOException("Invalid Tauon response token")
            else -> {
                // nextClean returns NUL at EOF as well as for a literal NUL; never treat a NUL
                // inside an unquoted name as EOF and skip the containers which follow it.
                if (token == '\u0000' && !tokens.more()) break
                tokens.back()
                tokens.nextTo("{}[]/\\:,=;# \t\f")
            }
        }
    }
    return JSONObject(text)
}
