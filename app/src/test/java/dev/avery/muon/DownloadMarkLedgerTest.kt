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
