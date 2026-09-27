package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageShareTest {
    @Test fun nothingStoredDrawsNothing() {
        assertEquals(0f, storageShare(0, 75_000_000_000), 0f)
    }

    @Test fun aFewMegabytesStillShowAsASliver() {
        assertEquals(0.015f, storageShare(39_000_000, 75_000_000_000), 0f)
    }

    @Test fun largerAmountsKeepTheirTrueShare() {
        assertEquals(0.25f, storageShare(25, 100), 1e-6f)
    }
}
