package dev.avery.muon

import android.util.AtomicFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** #179 prospective catalog boundary: disposable invalid destination, not a shipped catalog. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AtomicCatalogCommitReceiptTest {
    @get:Rule val folders = TemporaryFolder()

    @Test fun finishWriteCanReturnWithoutPublishingAtAnInvalidDestination() {
        // A nonempty directory cannot be replaced by the candidate file. The payload makes the
        // failure deterministic without changing permissions or relying on a full disk.
        val destination = folders.newFolder("catalog")
        val sentinel = File(destination, "retained")
        val retained = byteArrayOf(4, 8, 15, 16)
        sentinel.writeBytes(retained)
        val candidate = byteArrayOf(23, 42)
        val file = AtomicFile(destination)
        val stream = file.startWrite()
        try {
            stream.write(candidate)
            file.finishWrite(stream) // Normal return is deliberately insufficient as a commit receipt.
            assertTrue(destination.isDirectory)
            assertArrayEquals(retained, sentinel.readBytes())
            assertThrows(IOException::class.java) { file.readFully() }
            assertArrayEquals(retained, sentinel.readBytes())
        } finally {
            // Removes an uncommitted candidate through the API; only this temporary fixture exists.
            file.failWrite(stream)
        }
    }
}
