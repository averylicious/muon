package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test

class DiscoveryScanTest {
    private val one = DiscoveredServer("Desk", "http://192.168.1.2:7814")
    private val two = DiscoveredServer("Laptop", "http://192.168.1.3:7814")

    @Test fun allServersFoundDuringResolutionAreQueued() {
        val scan = DiscoveryScan<String>(); val token = scan.begin()
        scan.found(token, "desk", "first")
        val first = scan.nextRequest()!!
        scan.found(token, "laptop", "second")
        scan.found(token, "laptop", "duplicate")
        scan.resolved(first, one)
        val second = scan.nextRequest()!!
        assertEquals("second", second.value)
        scan.resolved(second, two); scan.finish(token)
        assertEquals(listOf(one, two), scan.snapshot().servers)
        assertEquals(0, scan.snapshot().unresolvedCount)
        assertNull(scan.nextRequest())
    }
    @Test fun oldCallbacksCannotPopulateOrFinishRestartedScan() {
        val scan = DiscoveryScan<String>(); val old = scan.begin()
        scan.found(old, "desk", "old"); val request = scan.nextRequest()!!
        val fresh = scan.begin()
        assertFalse(scan.resolved(request, one))
        scan.found(old, "laptop", "stale"); scan.finish(old, unavailable = true)
        assertEquals(DiscoveryStatus.SEARCHING, scan.snapshot().status)
        assertTrue(scan.snapshot().servers.isEmpty())
        scan.found(fresh, "desk", "fresh")
        assertEquals("fresh", scan.nextRequest()!!.value)
    }
    @Test fun lostThenRediscoveredNameRejectsOldResolution() {
        val scan = DiscoveryScan<String>(); val token = scan.begin()
        scan.found(token, "desk", "old"); val old = scan.nextRequest()!!
        scan.lost(token, "desk"); scan.found(token, "desk", "new")
        assertFalse(scan.resolved(old, one))
        assertTrue(scan.resolved(scan.nextRequest()!!, two))
        assertEquals(listOf(two), scan.snapshot().servers)
    }
    @Test fun lostResultsAreRemovedFromSnapshot() {
        val scan = DiscoveryScan<String>(); val token = scan.begin()
        scan.found(token, "desk", "first"); scan.resolved(scan.nextRequest()!!, one)
        scan.lost(token, "desk")
        assertTrue(scan.snapshot().servers.isEmpty())
    }
    @Test fun duplicateAddressesAreOneCandidateButFailedNamesRemainUnresolved() {
        val scan = DiscoveryScan<String>(); val token = scan.begin()
        for (key in listOf("a", "b", "c")) scan.found(token, key, key)
        scan.resolved(scan.nextRequest()!!, one)
        scan.resolved(scan.nextRequest()!!, one.copy(name = "Alias"))
        scan.resolved(scan.nextRequest()!!, null); scan.finish(token)
        assertEquals(listOf(one), scan.snapshot().servers)
        assertEquals(1, scan.snapshot().unresolvedCount)
    }
    @Test fun deadlineFreezesResultsAndRetainsUnresolvedCount() {
        val scan = DiscoveryScan<String>(); val token = scan.begin()
        scan.found(token, "desk", "first"); val late = scan.nextRequest()!!
        scan.found(token, "laptop", "second"); scan.finish(token)
        assertFalse(scan.resolved(late, one))
        assertNull(scan.nextRequest())
        assertEquals(DiscoveryStatus.COMPLETE, scan.snapshot().status)
        assertEquals(2, scan.snapshot().unresolvedCount)
    }
    @Test fun unavailableIsNotAnEmptySuccessfulScan() {
        val scan = DiscoveryScan<String>(); val token = scan.begin()
        scan.finish(token, unavailable = true); scan.finish(token)
        assertEquals(DiscoveryStatus.UNAVAILABLE, scan.snapshot().status)
    }
    @Test fun cancellationDiscardsCandidatesAndIgnoresLateCallbacks() {
        val scan = DiscoveryScan<String>(); val token = scan.begin()
        scan.found(token, "desk", "first"); val request = scan.nextRequest()!!
        scan.cancel()
        assertFalse(scan.resolved(request, one)); scan.finish(token)
        assertEquals(DiscoverySnapshot(DiscoveryStatus.IDLE), scan.snapshot())
    }
    @Test fun capacityLimitMarksResultsAsIncomplete() {
        val scan = DiscoveryScan<String>(limit = 1); val token = scan.begin()
        scan.found(token, "desk", "first"); scan.found(token, "laptop", "second")
        scan.resolved(scan.nextRequest()!!, one); scan.finish(token)
        assertEquals(listOf(one), scan.snapshot().servers)
        assertEquals(1, scan.snapshot().unresolvedCount)
    }
    @Test fun snapshotsAreDetachedFromLaterMutations() {
        val scan = DiscoveryScan<String>(); val token = scan.begin()
        scan.found(token, "desk", "first"); scan.resolved(scan.nextRequest()!!, one)
        val before = scan.snapshot(); scan.cancel()
        assertEquals(listOf(one), before.servers)
        assertTrue(scan.snapshot().servers.isEmpty())
    }
}
