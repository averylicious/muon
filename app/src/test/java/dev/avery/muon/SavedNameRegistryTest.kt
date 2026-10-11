package dev.avery.muon

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SavedNameRegistryTest {
    @get:Rule val folders = TemporaryFolder()
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var context: Context
    private lateinit var root: File
    private fun <T> io(block: () -> T): T = worker.submit<T> { block() }.get(30, TimeUnit.SECONDS)
    @Before fun setup() {
        root = folders.newFolder()
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir(): File = root
        }
    }
    @After fun close() {
        worker.shutdownNow()
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test fun exactNamesDoNotNormalizeClipOrAliasNulAndSurrogateCodeUnits(): Unit = io {
        val names = listOf("", "A", "a", "ab\u0000cd", "\ud800", "\ud801", "😀", "é", "é", "x".repeat(40_000))
        SavedNameRegistry.open(context).use { registry ->
            for (name in names) { assertFalse(name in registry); registry.add(name); registry.add(name) }
            names.forEach { assertTrue(it in registry) }
            listOf("ab", "ab\u0000ce", "�", "x".repeat(39_999)).forEach { assertFalse(it in registry) }
            registry.add("new-reservation")
            assertTrue("new-reservation" in registry)
        }
        assertEmptyScratch()
    }

    @Test fun everyNameRemainsQueryableBeyondTheQueueBudgetWithoutASilentPrefix(): Unit = io {
        SavedNameRegistry.open(context).use { registry ->
            repeat(8_192) { registry.add("original/$it") }
            repeat(8_192) { assertTrue("original/$it" in registry) }
            assertFalse("original/8192" in registry)
        }
        assertEmptyScratch()
    }

    @Test fun failedPopulationRollsBackAndDoesNotPoisonTheNextOperation(): Unit = io {
        assertThrows(IOException::class.java) {
            SavedNameRegistry.open(context).use { it.add("partial"); throw IOException("scan failed") }
        }
        SavedNameRegistry.open(context).use {
            assertFalse("partial" in it)
            it.add("retry"); assertTrue("retry" in it)
        }
        assertEmptyScratch()
    }

    @Test fun closeIsIdempotentAndClosedReservationsCannotBecomeAuthority(): Unit = io {
        val registry = SavedNameRegistry.open(context)
        registry.add("old"); registry.close(); registry.close()
        assertThrows(IllegalStateException::class.java) { registry.add("late") }
        assertThrows(IllegalStateException::class.java) { "old" in registry }
        SavedNameRegistry.open(context).use { assertFalse("old" in it); assertFalse("late" in it) }
        assertEmptyScratch()
    }

    @Test fun unavailablePrivateScratchFailsInsteadOfGuessingFreeNames(): Unit = io {
        val file = folders.newFile()
        val unavailable = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File = file
        }
        assertThrows(IllegalStateException::class.java) { SavedNameRegistry.open(unavailable) }
        assertTrue(file.isFile)
    }

    @Test fun mainThreadCannotOpenTheDiskCensus() {
        assertThrows(IllegalStateException::class.java) { SavedNameRegistry.open(context) }
    }

    private fun assertEmptyScratch() {
        SQLiteDatabase.openDatabase(File(root, "saved-save-names-v1.db").path, null,
            SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM names", null).use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        }
    }
}
