package dev.avery.muon

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class StorageStartupAdmissionTest {
    @Test fun activityRecreationCannotSwitchAReviewProcessBackToNormal() {
        val gate=StorageStartupAdmission();assertTrue(gate.review());assertTrue(gate.review());assertTrue(gate.reviewing)
        repeat(2) {assertThrows(IOException::class.java) {gate.normal()}}
    }
    @Test fun existingNormalOwnerPreventsReviewWithoutInterruptingNormalCalls() {
        val gate=StorageStartupAdmission();gate.normal();assertFalse(gate.review());gate.normal();assertFalse(gate.reviewing)
    }
    @Test fun simultaneousServiceAndReviewEntryChooseExactlyOneProcessMode() {
        val executor=Executors.newFixedThreadPool(2)
        try {repeat(100) {
            val gate=StorageStartupAdmission();val start=CountDownLatch(1)
            val review=executor.submit<Boolean> {start.await();gate.review()}
            val normal=executor.submit<Boolean> {start.await();try {gate.normal();true} catch(_:IOException) {false}}
            start.countDown();val r=review.get(5,TimeUnit.SECONDS);val n=normal.get(5,TimeUnit.SECONDS)
            assertNotEquals(r,n);assertEquals(r,gate.reviewing)
        }} finally {executor.shutdownNow()}
    }
}
