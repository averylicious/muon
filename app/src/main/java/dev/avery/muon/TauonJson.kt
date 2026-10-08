package dev.avery.muon

import java.io.IOException
import org.json.JSONObject
import org.json.JSONTokener

/** API objects are shallow; allow ample extension room without unbounded recursive DOM parsing. */
internal const val TAUON_JSON_MAX_DEPTH = 64

/** Structural allocation limits before the DOM: extensions are counted even when projection ignores them. */
internal data class TauonJsonLimits(val arrayItems: Int = 50_000, val values: Int = 1_600_000)

private class JsonContainer(val array: Boolean, var content: Boolean = false, var separators: Int = 0, var element: Boolean = false)

/**
 * Walk tokens iteratively before JSONObject's recursive parser. Use the platform's documented
 * lexical methods so quoted strings, comments and lenient unquoted literals aren't mistaken for
 * containers. JSONTokener is deliberately not subclassed; its internal self-calls aren't an API.
 * Array slots include omitted/trailing nulls in the platform's lenient syntax. Count scalar tokens
 * (object names included) and containers before allocating the DOM, not just recognized tracks.
 * These are structural bounds, not a byte-exact process/DOM heap budget.
 */
internal fun parseTauonJson(text: String, limits: TauonJsonLimits = TauonJsonLimits()): JSONObject {
    require(limits.arrayItems > 0 && limits.values > 0)

    val tokens = JSONTokener(text)
    val containers = java.util.ArrayDeque<JsonContainer>()
    var values = 0
    fun value() {
        if (values >= limits.values) throw LibraryResourceLimit("Tauon response exceeds Muon's JSON value limit. Reduce the server response and retry.")
        values++
    }
    while (tokens.more()) {
        val token = tokens.nextClean()
        // A nonempty array has one slot plus its separators. Android JSONTokener accepts missing
        // values and a trailing separator as null slots, so counting only literal starts misses them.
        containers.peekLast()?.takeIf { it.array }?.let { parent ->
            when (token) {
                ',', ';' -> {
                    if (!parent.element) value() // An omitted element still allocates a null slot.
                    parent.content = true
                    parent.element = false
                    parent.separators++
                }
                ']' -> if (parent.content && !parent.element) value() // Trailing null.
                else -> { parent.content = true; parent.element = true }
            }
            if (parent.content && parent.separators >= limits.arrayItems)
                throw LibraryResourceLimit("Tauon response exceeds Muon's JSON array limit. Reduce the server response and retry.")
        }
        when (token) {
            '{', '[' -> {
                value()
                containers.addLast(JsonContainer(token == '['))
                if (containers.size > TAUON_JSON_MAX_DEPTH) throw IOException("Tauon response nesting exceeds $TAUON_JSON_MAX_DEPTH levels")
            }
            '}', ']' -> {
                if (containers.isEmpty()) throw IOException("Unbalanced Tauon response")
                containers.removeLast()
            }
            '\'', '"' -> { value(); tokens.nextString(token) }
            ',', ';', ':', '=', '>', '\\' -> Unit
            '/', '\u000c' -> throw IOException("Invalid Tauon response token")
            else -> {
                // nextClean returns NUL at EOF as well as for a literal NUL; never treat a NUL
                // inside an unquoted name as EOF and skip the containers which follow it.
                if (token == '\u0000' && !tokens.more()) break
                value()
                tokens.back()
                tokens.nextTo("{}[]/\\:,=;# \t\u000c")
            }
        }
    }
    return JSONObject(text)
}
