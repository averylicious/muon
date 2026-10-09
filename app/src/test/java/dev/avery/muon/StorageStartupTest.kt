package dev.avery.muon

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class StorageStartupTest {
    @Test fun successfulOpeningIsStableAndNeverReplacesServiceOwner() {
        val owner = StorageStartup<Any>()
        val manager = Any()
        assertSame(manager, owner.open { it.own { manager } })
        assertSame(manager, owner.open { error("Must not replace the installed manager") })
        assertEquals(1, owner.retained)
    }
    @Test fun partialFailureKeepsExactParticipantsAndNeverRunsAnotherFactory() {
        val owner = StorageStartup<Any>()
        val cache = Any(); val workers = Any(); var factories = 0
        assertThrows(IOException::class.java) { owner.open { opening ->
            factories++; opening.own { cache }; opening.own { workers }; throw IOException("manager constructor failed")
        } }
        assertEquals(2, owner.retained)
        repeat(3) { assertThrows(IOException::class.java) { owner.open { factories++; Any() } } }
        assertEquals(1, factories); assertEquals(2, owner.retained)
    }
    @Test fun capacityIsRefusedBeforeAnotherResourceIsConstructed() {
        val owner = StorageStartup<Any>(1); var allocated = 0
        assertThrows(IOException::class.java) { owner.open { opening ->
            opening.own { allocated++; Any() }; opening.own { allocated++; Any() }
        } }
        assertEquals(1, allocated); assertEquals(1, owner.retained)
    }
    @Test fun reentrantOpeningDoesNotStartAnotherCacheAndOuterFailureRemainsSticky() {
        val owner = StorageStartup<Any>(); var nested = false
        assertThrows(IOException::class.java) { owner.open { owner.open { nested = true; Any() } } }
        assertFalse(nested)
        assertThrows(IOException::class.java) { owner.open { Any() } }
    }
    @Test fun leakedOpeningHandleCannotAllocateAfterStartupFinished() {
        val owner = StorageStartup<Any>(); var opening: StorageStartup<Any>.Opening? = null
        owner.open { opening = it; Any() }
        assertThrows(IllegalStateException::class.java) { opening!!.own { Any() } }
        assertEquals(0, owner.retained)
    }
}
