package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test

class AutoConnectTest {
    private val one = DiscoveredServer("Tauon", "http://192.168.1.10:7814")
    private val two = DiscoveredServer("Tauon (2)", "http://192.168.1.11:7814")
    private fun done(vararg servers: DiscoveredServer, unresolved: Int = 0) =
        DiscoverySnapshot(DiscoveryStatus.COMPLETE, servers.toList(), unresolved)

    @Test fun exactlyOneFinishedAnswerConnects() {
        assertEquals(one, autoConnectTarget(done(one), alreadyTried = false, userTyped = false))
    }

    @Test fun severalAnswersAreLeftToTheUser() {
        assertNull(autoConnectTarget(done(one, two), false, false))
    }

    @Test fun anUnfinishedOrPartialScanIsNotUnambiguous() {
        assertNull(autoConnectTarget(DiscoverySnapshot(DiscoveryStatus.SEARCHING, listOf(one)), false, false))
        assertNull(autoConnectTarget(done(one, unresolved = 1), false, false))
        assertNull(autoConnectTarget(DiscoverySnapshot(DiscoveryStatus.UNAVAILABLE, listOf(one)), false, false))
    }

    @Test fun itTriesOnceAndNeverOverTyping() {
        assertNull(autoConnectTarget(done(one), alreadyTried = true, userTyped = false))
        assertNull(autoConnectTarget(done(one), alreadyTried = false, userTyped = true))
    }
}
