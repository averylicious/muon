package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test

class ServerEndpointTest {
    @Test fun privateOrigins() {
        assertEquals("http://192.168.1.4:7814", ServerEndpoint.parse("192.168.1.4").origin)
        assertEquals("https://10.0.0.4:443", ServerEndpoint.parse("https://10.0.0.4:443/").origin)
        assertEquals("http://[fd00::1]:7814", ServerEndpoint.parse("http://[fd00::1]").origin)
        assertEquals("http://127.0.0.1:7814", ServerEndpoint.parse("127.0.0.1").origin)
    }
    @Test fun rejectUntrustedOrAmbiguousOrigins() {
        for (input in listOf("8.8.8.8", "0.0.0.0", "224.0.0.1", "100.64.0.1", "example.com",
            "192.168.1.999", "192.168.01.1", "http://192.168.1.1:0", "http://user@192.168.1.1",
            "http://192.168.1.1/path", "http://192.168.1.1?x=1", "http://[2001:4860::1]")) {
            assertThrows(input, IllegalArgumentException::class.java) { ServerEndpoint.parse(input) }
        }
    }
}
