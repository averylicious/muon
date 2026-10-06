package dev.avery.muon

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], manifest = Config.NONE)
class TauonJsonTest {
    @Test fun ordinaryApiObjectsAndWideArraysRemainReadable() {
        val tracks = (0 until 1000).joinToString(",") { "{\"id\":$it,\"title\":\"Song\"}" }
        assertEquals(1000, parseTauonJson("{\"tracks\":[$tracks]}").getJSONArray("tracks").length())
        assertEquals(1, parseTauonJson("{\"version\":1}").getInt("version"))
    }

    @Test fun exactlyTheAllowedContainerDepthParsesButTheNextDoesNot() {
        fun payload(arrays: Int) = "{\"extra\":" + "[".repeat(arrays) + "0" + "]".repeat(arrays) + "}"
        assertNotNull(parseTauonJson(payload(TAUON_JSON_MAX_DEPTH - 1)))
        val failure = assertThrows(IOException::class.java) { parseTauonJson(payload(TAUON_JSON_MAX_DEPTH)) }
        assertEquals("Tauon response nesting exceeds 64 levels", failure.message)
    }

    @Test fun deeplyNestedIgnoredObjectsAreRejectedBeforeDomConstruction() {
        val payload = "{\"version\":1,\"ignored\":" + "{\"x\":".repeat(10000) + "0" + "}".repeat(10000) + "}"
        assertTrue(payload.toByteArray().size < 16 * 1024 * 1024)
        assertThrows(IOException::class.java) { parseTauonJson(payload) }
    }

    @Test fun quotedBracesEscapesAndUnicodeAreTextNotNesting() {
        val title = "[".repeat(100) + "Björk 宇多田🎵 \" quoted \\ backslash"
        val encoded = org.json.JSONObject().put("title", title).toString()
        assertEquals(title, parseTauonJson(encoded).getString("title"))
    }

    @Test fun platformLeniencyDoesNotHideOrInventNesting() {
        assertEquals("[[[{{{", parseTauonJson("{/* {{{[[[ */ 'title':'[[[{{{', # {{{\n version:1}").getString("title"))
        // A quote occurring inside an unquoted literal is not the start of a quoted string.
        val payload = "{name\"literal:" + "[".repeat(65) + "0" + "]".repeat(65) + "}"
        assertThrows(IOException::class.java) { parseTauonJson(payload) }
    }

    @Test fun nulInAnUnquotedNameCannotTruncateThePreflight() {
        val payload = "{\u0000:" + "[".repeat(65) + "0" + "]".repeat(65) + "}"
        assertThrows(IOException::class.java) { parseTauonJson(payload) }
    }

    @Test fun ordinaryMalformedJsonStillFailsWithoutPretendingItWasValid() {
        assertThrows(Exception::class.java) { parseTauonJson("{\"version\":") }
        assertThrows(IOException::class.java) { parseTauonJson("}") }
        assertThrows(IOException::class.java) { parseTauonJson("{x:/}") }
        assertThrows(IOException::class.java) { parseTauonJson("{x:\u000c}") }
    }
}
