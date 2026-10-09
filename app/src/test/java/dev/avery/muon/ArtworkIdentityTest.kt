package dev.avery.muon

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ArtworkIdentityTest {
    private val url = "http://192.168.1.10:7814/api1/pic/medium/1"
    private val old = "Old\u0000Artist\u0000Album"
    private val replacement = "New\u0000Artist\u0000Album"

    @After fun reset() { ArtworkIdentities.publish(emptyMap()); Snapshot.sendApplyNotifications() }

    @Test fun reusedUrlCannotReadThePreviousIdentityOrSize() {
        val memory = hashMapOf(ArtworkRequest(url, old).memoryKey(128) to "old cover")
        assertNull(memory[ArtworkRequest(url, replacement).memoryKey(128)])
        assertNull(memory[ArtworkRequest(url, old).memoryKey(256)])
        assertNull(memory[ArtworkRequest("http://other:7814/api1/pic/medium/1", old).memoryKey(128)])
        assertEquals("old cover", memory[ArtworkRequest(url, old).memoryKey(128)])
    }

    @Test fun latePublicationIsRejectedAfterIdentityChanges() {
        ArtworkIdentities.publish(mapOf(url to old))
        val request = ArtworkIdentities.request(url)
        ArtworkIdentities.publish(mapOf(url to replacement))
        var cover = "new cover"
        assertNull(ArtworkIdentities.ifCurrent(request) { cover = "old cover"; cover })
        assertEquals("new cover", cover)
        assertEquals("accepted", ArtworkIdentities.ifCurrent(ArtworkIdentities.request(url)) { "accepted" })
    }

    @Test fun identityChangeInvalidatesAnObservedArtworkRequest() {
        ArtworkIdentities.publish(mapOf(url to old))
        Snapshot.sendApplyNotifications()
        var invalidations = 0
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            observer.observeReads(Any(), { _: Any -> invalidations++ }) { ArtworkIdentities.request(url) }
            ArtworkIdentities.publish(mapOf(url to replacement))
            Snapshot.sendApplyNotifications()
            assertEquals(1, invalidations)
        } finally { observer.stop(); observer.clear() }
    }

    @Test fun unchangedIdentitySnapshotDoesNotInvalidateObserversOrRejectWork() {
        ArtworkIdentities.publish(mapOf(url to old))
        Snapshot.sendApplyNotifications()
        val request = ArtworkIdentities.request(url)
        var invalidations = 0
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            observer.observeReads(Any(), { _: Any -> invalidations++ }) { ArtworkIdentities.request(url) }
            ArtworkIdentities.publish(mapOf(url to old))
            Snapshot.sendApplyNotifications()
            assertEquals(0, invalidations)
            assertEquals("cover", ArtworkIdentities.ifCurrent(request) { "cover" })
        } finally { observer.stop(); observer.clear() }
    }

    @Test fun unknownAndKnownIdentitiesDoNotAliasAndDisconnectRejectsKnownRequest() {
        val unknown = ArtworkIdentities.request(url)
        ArtworkIdentities.publish(mapOf(url to old))
        val known = ArtworkIdentities.request(url)
        assertNotEquals(unknown.memoryKey(128), known.memoryKey(128))
        assertNull(ArtworkIdentities.ifCurrent(unknown) { "obsolete unknown cover" })
        ArtworkIdentities.publish(emptyMap())
        assertNull(ArtworkIdentities.ifCurrent(known) { "obsolete known cover" })
    }
}
