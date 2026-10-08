package dev.avery.muon

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34],manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DownloadMarkLedgerTest {
    @get:Rule val folders = TemporaryFolder()
    @Test fun nativePointReadsAndCountsMatchThePriorWholeMapThroughReplacementsAndRemoval() {
        val prior = HashMap<String,DownloadMark>()
        val random = Random(253)
        val names = listOf("a", "a\u0000b", "\uD800", "\uD801", "x".repeat(2048))
        DownloadMarkLedger(folders.newFile()).use { ledger ->
            repeat(1200) { step ->
                val id = if (step % 3 == 0) names[step % names.size] else "saved/${random.nextInt(400)}"
                val mark = if (random.nextInt(4) == 0) null else DownloadMark.entries[random.nextInt(3)]
                if (mark == null) prior.remove(id) else prior[id] = mark
                ledger.put(id,mark)
                assertTrue(ledger.known)
                assertEquals(prior.values.count { it == DownloadMark.Done }.toLong(),ledger.done)
                assertEquals(prior[id],ledger.get(id))
            }
            for (id in names) assertEquals(prior[id],ledger.get(id))
        }
    }
    @Test fun derivedStateIsResetRatherThanTrustedAfterRestart() {
        val file = folders.newFile()
        DownloadMarkLedger(file).use { ledger -> ledger.put("old",DownloadMark.Done); assertEquals(1L,ledger.done) }
        DownloadMarkLedger(file).use { ledger ->
            assertNull(ledger.get("old")); assertEquals(0L,ledger.done)
            ledger.put("current",DownloadMark.Downloading); ledger.put("current",DownloadMark.Done)
            assertEquals(1L,ledger.done); assertTrue(ledger.known)
        }
    }
    @Test fun aWorkerOnlyReadFailurePublishesUnknownOnceWithoutWaitingForADownloadEvent() {
        val main=android.os.Handler(android.os.Looper.getMainLooper())
        DownloadMarks.countsKnown=true
        var signals=0
        val ledger=DownloadMarkLedger(folders.newFolder()) {
            signals++; main.post { DownloadMarks.countsKnown=false }
        }
        val worker=Thread { repeat(1000) { assertNull(ledger.get("kept")) } }
        worker.start(); worker.join(5000)
        assertFalse(worker.isAlive); assertFalse(ledger.known); assertEquals(1,signals)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(DownloadMarks.countsKnown)
        ledger.put("kept",DownloadMark.Done); ledger.close(); assertEquals(1,signals)
        DownloadMarks.countsKnown=true // Shared fixture state restored.
    }
    @Test fun aThrowingNotificationNeverRestoresTrustOrThrowsIntoTheNativeStateCallback() {
        var signals=0
        val ledger=DownloadMarkLedger(folders.newFolder()) { signals++; throw IllegalStateException("sink failed") }
        ledger.put("kept",DownloadMark.Done); assertFalse(ledger.known)
        repeat(100) { assertNull(ledger.get("kept")); ledger.put("kept",DownloadMark.Done) }
        assertEquals(1,signals); ledger.close(); assertEquals(1,signals)
    }
    @Test fun failureRevokesCountsAndBadgesWithoutTouchingOriginalAudio() {
        val dir = folders.newFolder()
        val original = File(dir,"original").apply { writeBytes(byteArrayOf(1,2,3)) }
        DownloadMarkLedger(dir).use { bad ->
            bad.put("original",DownloadMark.Done); assertNull(bad.get("original")); assertFalse(bad.known)
        }
        val good = DownloadMarkLedger(folders.newFile())
        good.put("original",DownloadMark.Done); good.close()
        good.put("other",DownloadMark.Done); assertNull(good.get("original")); assertFalse(good.known)
        assertArrayEquals(byteArrayOf(1,2,3),original.readBytes())
    }
}
