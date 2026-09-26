package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test

class LanProbeTest {
    @Test fun aHomeNetworkIsItsOtherTwoHundredAndFiftyThreeHosts() {
        val hosts = probeCandidates("192.168.1.42", 24)
        assertEquals(253, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
        assertFalse("192.168.1.42" in hosts)
    }

    @Test fun aWiderNetworkIsLimitedToTheSurroundingSlashTwentyFour() {
        assertEquals(253, probeCandidates("10.0.7.9", 16).size)
        assertTrue(probeCandidates("10.0.7.9", 16).all { it.startsWith("10.0.7.") })
    }

    @Test fun aSmallerSubnetIsAskedWhole() {
        assertEquals(listOf("192.168.1.65", "192.168.1.67", "192.168.1.68", "192.168.1.69", "192.168.1.70"),
            probeCandidates("192.168.1.66", 29))
    }

    @Test fun aPublicAddressHasNothingToAsk() {
        assertEquals(emptyList<String>(), probeCandidates("8.8.8.8", 24))
        assertEquals(emptyList<String>(), probeCandidates("not an address", 24))
    }

    private val a = DiscoveredServer("Tauon Remote (desk)", "http://192.168.1.10:7814")
    private val probed = DiscoveredServer("Tauon at 192.168.1.10", "http://192.168.1.10:7814")

    @Test fun aProbeThatFoundTauonSettlesItWithoutWaitingForDiscovery() {
        val combined = combineDiscovery(DiscoverySnapshot(DiscoveryStatus.SEARCHING, emptyList(), 2), listOf(probed))
        assertEquals(DiscoveryStatus.COMPLETE, combined.status)
        assertEquals(probed, autoConnectTarget(combined, alreadyTried = false, userTyped = false))
    }

    @Test fun discoverysNameIsKeptForAServerBothFound() {
        val combined = combineDiscovery(DiscoverySnapshot(DiscoveryStatus.COMPLETE, listOf(a)), listOf(probed))
        assertEquals(listOf(a), combined.servers)
    }

    @Test fun whileTheProbeRunsNothingIsDecided() {
        assertEquals(DiscoveryStatus.SEARCHING, combineDiscovery(DiscoverySnapshot(DiscoveryStatus.COMPLETE, listOf(a)), null).status)
    }

    @Test fun withNothingProbedDiscoveryStands() {
        assertEquals(DiscoveryStatus.SEARCHING, combineDiscovery(DiscoverySnapshot(DiscoveryStatus.SEARCHING), emptyList()).status)
        val done = combineDiscovery(DiscoverySnapshot(DiscoveryStatus.COMPLETE, listOf(a)), emptyList())
        assertEquals(a, autoConnectTarget(done, false, false))
        assertTrue(combineDiscovery(DiscoverySnapshot(DiscoveryStatus.UNAVAILABLE), emptyList()).servers.isEmpty())
    }
}
