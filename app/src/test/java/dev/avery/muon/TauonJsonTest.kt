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

    @Test fun arrayWidthBoundaryIncludesOmittedAndTrailingNullSlots() {
        val limits = TauonJsonLimits(arrayItems = 3)
        for (array in listOf("[1,2,3]", "[,1,]", "[;1;]", "[,,]", "[[],{},null]"))
            assertEquals(array, 3, parseTauonJson("{x:$array}", limits).getJSONArray("x").length())
        for (array in listOf("[1,2,3,4]", "[,,,]", "[;;;]", "[1,2,3,]"))
            assertThrows(array, LibraryResourceLimit::class.java) { parseTauonJson("{x:$array}", limits) }
        assertEquals(0, parseTauonJson("{x:[]}", limits).getJSONArray("x").length())
    }

    @Test fun nestedArraysHaveSeparateWidthBudgetsAndIgnoredExtensionsAreBounded() {
        val limits = TauonJsonLimits(arrayItems = 2)
        assertEquals(2, parseTauonJson("{x:[[1,2],[3,4]]}", limits).getJSONArray("x").length())
        assertThrows(LibraryResourceLimit::class.java) { parseTauonJson("{version:1,ignored:[[1,2,3]]}", limits) }
    }

    @Test fun valueBudgetCoversNamesContainersAndScalarsAcrossSiblingArrays() {
        // Root object + name + array + three scalars = six values.
        assertEquals(3, parseTauonJson("{x:[1,2,3]}", TauonJsonLimits(values = 6)).getJSONArray("x").length())
        assertThrows(LibraryResourceLimit::class.java) { parseTauonJson("{x:[1,2,3]}", TauonJsonLimits(values = 5)) }
        assertThrows(LibraryResourceLimit::class.java) { parseTauonJson("{x:[1,2],y:[3,4]}", TauonJsonLimits(values = 8)) }
        assertThrows(LibraryResourceLimit::class.java) { parseTauonJson("{a:1,b:2,c:3}", TauonJsonLimits(values = 6)) }
    }

    @Test fun quotedSeparatorsAndCommentedBracketsDoNotConsumeStructureBudgets() {
        val text = "{/* [,;{ */ 'x':['[,;{}', # [,;\n '\"\\\\'], y => 'ok'}"
        val parsed = parseTauonJson(text, TauonJsonLimits(arrayItems = 2, values = 7))
        assertEquals(2, parsed.getJSONArray("x").length())
        assertEquals("ok", parsed.getString("y"))
    }

    @Test fun omittedArraySlotsAlsoConsumeTheAggregateValueBudget() {
        assertEquals(3, parseTauonJson("{x:[,,]}", TauonJsonLimits(values = 6)).getJSONArray("x").length())
        assertThrows(LibraryResourceLimit::class.java) { parseTauonJson("{x:[,,]}", TauonJsonLimits(values = 5)) }
        assertThrows(LibraryResourceLimit::class.java) { parseTauonJson("{x:[[,],[,]]}", TauonJsonLimits(values = 8)) }
    }

    @Test fun manyIndividuallyAllowedArraysCannotBypassTheProductionAggregateLimit() {
        // 32 individually legal arrays, all omitted slots: below the wire/depth limits but above
        // the default value budget. Refused by this preflight, not after materializing their DOM.
        val array = "[" + ",".repeat(49_999) + "]"
        val text = "{ignored:[" + List(32) { array }.joinToString(",") + "]}"
        assertTrue(text.toByteArray().size < 16 * 1024 * 1024)
        val failure = assertThrows(LibraryResourceLimit::class.java) { parseTauonJson(text) }
        assertTrue(failure.message.orEmpty().contains("JSON value limit"))
    }

}
