package dev.avery.muon

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class SavedStorageBarrierTest {
    @Test fun fixedBudgetAndExclusiveOwnershipRefuseWithoutWaiting() {
        val barrier=SavedStorageBarrier(2); val a=barrier.shared(); val b=barrier.shared()
        assertEquals(2,barrier.active)
        assertThrows(IOException::class.java) { barrier.shared() }
        assertThrows(IOException::class.java) { barrier.exclusive() }
        a.close(); a.close(); assertEquals(1,barrier.active); b.close()
        val exclusive=barrier.exclusive()
        assertThrows(IOException::class.java) { barrier.shared() }
        assertThrows(IOException::class.java) { barrier.exclusive() }
        exclusive.close(); assertTrue(barrier.quiescent)
        assertThrows(IOException::class.java) { exclusive.check() }
    }
    @Test fun stopAdmissionAllowsExistingKnownOperationsToDrain() {
        val barrier=SavedStorageBarrier(); val lease=barrier.shared(); barrier.stopAdmission()
        lease.check(); assertThrows(IOException::class.java) { barrier.shared() }
        assertThrows(IOException::class.java) { barrier.exclusive() }
        lease.close(); assertTrue(barrier.quiescent)
    }
    @Test fun invalidationFailsExistingChecksButKnownCloseReturnsItsPermit() {
        val barrier=SavedStorageBarrier(); val lease=barrier.shared(); barrier.invalidate()
        assertThrows(IOException::class.java) { lease.check() }
        lease.close(); assertTrue(barrier.quiescent)
        assertThrows(IOException::class.java) { barrier.shared() }
    }
    @Test fun quarantinedOwnerRemainsCountedAndCannotResetOrRetryClosure() {
        val barrier=SavedStorageBarrier(); val lease=barrier.shared(); val other=barrier.shared()
        lease.quarantine(Any()); assertEquals(2,barrier.active)
        assertThrows(IOException::class.java) { other.check() }; other.close()
        assertThrows(IOException::class.java) { lease.close() }
        assertEquals(1,barrier.active); assertFalse(barrier.quiescent)
        assertThrows(IOException::class.java) { barrier.exclusive() }
    }
}
