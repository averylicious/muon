package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test

class LocalNetworkTest {
    @Test fun onlyAndroidSeventeenAndLaterAsk() {
        assertFalse(localNetworkNeedsAsking(36))
        assertTrue(localNetworkNeedsAsking(37))
        assertTrue(localNetworkNeedsAsking(38))
    }

    @Test fun earlierAndroidVersionsAlwaysMayConnect() {
        assertTrue(mayUseLocalNetwork(34, granted = false))
        assertTrue(mayUseLocalNetwork(36, granted = false))
    }

    @Test fun fromSeventeenOnlyOnceGranted() {
        assertFalse(mayUseLocalNetwork(37, granted = false))
        assertTrue(mayUseLocalNetwork(37, granted = true))
    }

    @Test fun onceAndroidStopsAskingTheButtonOpensSettings() {
        assertEquals(LocalNetworkAsk.Ask, localNetworkAsk(denied = false, rationale = false))
        assertEquals(LocalNetworkAsk.Ask, localNetworkAsk(denied = true, rationale = true))
        assertEquals(LocalNetworkAsk.OpenSettings, localNetworkAsk(denied = true, rationale = false))
    }
}
