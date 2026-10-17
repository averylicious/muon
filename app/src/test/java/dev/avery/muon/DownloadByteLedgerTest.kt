package dev.avery.muon

import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34],manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DownloadByteLedgerTest {
    @get:Rule val folders = TemporaryFolder()

    @Test fun nativeDiskTallyMatchesThePriorMapThroughExactNamesReplacementRemovalAndOverflow() {
        val ledger = DownloadByteLedger(folders.newFile())
        val old = HashMap<String,Long>()
        val random = Random(253)
        val names = listOf("", "a\u0000b", "a", "\uD800", "\uD801", "x".repeat(2048))
        val sizes = longArrayOf(0,1,7_000,Long.MAX_VALUE,Long.MAX_VALUE-1,Long.MIN_VALUE)
        try {
            repeat(1200) { step ->
                val id = if (step % 3 == 0) names[step % names.size] else "saved/${random.nextInt(400)}"
                if (random.nextInt(3) == 0) { ledger.remove(id); old.remove(id) }
                else { val bytes = sizes[random.nextInt(sizes.size)]; ledger.put(id,bytes); old[id] = bytes }
                assertTrue(ledger.known)
                assertEquals("step $step",old.values.sum(),ledger.total)
            }
        } finally { ledger.close() }
    }

    @Test fun productionFacadeUsesFreshDiskAccountingAndDoesNotTrustThePreviousProcess() {
        val root = folders.newFolder()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir() = root
        }
        DownloadByteTotals(context).use { totals ->
            totals.put("old",7); totals.put("old",8); totals.put("second",4)
            assertEquals(12L,totals.total); assertTrue(totals.known)
        }
        DownloadByteTotals(context).use { totals ->
            totals.put("actual",5)
            assertEquals(5L,totals.total); totals.remove("old")
            assertEquals(5L,totals.total); assertTrue(totals.known)
        }
    }

    @Test fun derivedStorageFailureAndCloseRevokeExactnessWithoutThrowingIntoCallbacks() {
        val directory = folders.newFolder()
        val original = File(directory,"original-audio").apply { writeBytes(byteArrayOf(1,2,3)) }
        val bad = DownloadByteLedger(directory) // A directory cannot be opened as the tally DB.
        bad.put("kept",7); bad.remove("kept")
        assertFalse(bad.known)
        assertArrayEquals(byteArrayOf(1,2,3),original.readBytes())
        bad.close()
        val actual = DownloadByteLedger(folders.newFile())
        actual.put("kept",7); assertTrue(actual.known)
        actual.close(); actual.put("new",8); actual.remove("kept")
        assertFalse(actual.known); assertEquals(7L,actual.total)
    }
}
