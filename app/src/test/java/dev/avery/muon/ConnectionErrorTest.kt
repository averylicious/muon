package dev.avery.muon

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Actual platform parser diagnostics passed through the production Library/Lyrics error mapper. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ConnectionErrorTest {
    @Test fun malformedJsonDoesNotExposeItsFullResponseInTheErrorCard() {
        val payload = "PRIVATE_PAYLOAD_MARKER" + "x".repeat(64 * 1024)
        val failure = assertThrows(JSONException::class.java) { JSONObject("{\"private\":\"$payload") }
        // Characterize the actual platform diagnostic rather than inventing a giant exception.
        assertTrue(failure.message.orEmpty().contains(payload))
        val displayed = friendlyError(failure)
        assertTrue(displayed.length < 512)
        assertFalse(displayed.contains("PRIVATE_PAYLOAD_MARKER"))
        assertTrue(displayed.contains("retry"))
    }

    @Test fun wrongTypedFieldsUseTheSameSafeParserGuidance() {
        val value = "PRIVATE_PLAYLIST_MARKER" + "x".repeat(64 * 1024)
        val json = JSONObject().put("playlists", value)
        val failure = assertThrows(JSONException::class.java) { json.getJSONArray("playlists") }
        assertTrue(failure.message.orEmpty().contains(value))
        assertEquals(friendlyError(JSONException("small diagnostic")), friendlyError(failure))
        assertFalse(friendlyError(failure).contains("PRIVATE_PLAYLIST_MARKER"))
    }

    @Test fun establishedNetworkGuidanceDoesNotCopyExceptionPayloads() {
        val payload = "PRIVATE_NETWORK_MARKER" + "x".repeat(64 * 1024)
        assertEquals("Tauon did not respond. Check the server, LAN firewall and VPN LAN access, then retry.",
            friendlyError(SocketTimeoutException(payload)))
        assertEquals("Cannot reach Tauon. Enable remote control, restart Tauon, and check the address.",
            friendlyError(ConnectException(payload)))
        assertEquals("Server address could not be resolved.", friendlyError(UnknownHostException(payload)))
    }

    @Test fun shortErrorsArePreservedAndOversizedOnesFallBackWithoutTruncation() {
        assertEquals("Tauon returned HTTP 503", friendlyError(java.io.IOException("Tauon returned HTTP 503")))
        val boundary = "\uD83C\uDFB5".repeat(256)
        assertEquals(512, boundary.length)
        assertEquals(boundary, friendlyError(IllegalArgumentException(boundary)))
        val fallback = friendlyError(Exception())
        assertEquals(fallback, friendlyError(IllegalArgumentException(boundary + "\uD83C\uDFB5")))
        assertEquals(fallback, friendlyError(Exception("x".repeat(64 * 1024))))
        assertTrue(fallback.contains("retry"))
    }

    @Test fun missingAndBlankErrorsStillGiveRetryGuidance() {
        val fallback = friendlyError(Exception())
        assertEquals(fallback, friendlyError(Exception("")))
        assertEquals(fallback, friendlyError(Exception(" \n\t")))
        assertTrue(fallback.isNotBlank())
    }
}
