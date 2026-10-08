package dev.avery.muon

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
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
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SavedOwnerProjectionTest {
    @get:Rule val folders = TemporaryFolder()
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var context: Context
    private fun <T> io(block: () -> T): T = worker.submit<T> { block() }.get(20, TimeUnit.SECONDS)
    @Before fun setup() {
        val root = folders.newFolder()
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir(): File = root
        }
    }
    @After fun close() { worker.shutdownNow() }

    @Test fun everyHiddenStateAndAliasMatchesExistingWholeOwnershipRule(): Unit = io {
        val rows = listOf(row("saved/a"), row("hidden/" + "x".repeat(1500), "saved/a", Download.STATE_FAILED),
            row("saved/b"), row("saved/removing", "saved/b", Download.STATE_REMOVING),
            row("saved/c"), row("saved/stopped", "saved/c", Download.STATE_STOPPED),
            row("saved/sole"), row("saved/other", "different-key"), row(PLAYED_PREFIX + "legacy"))
        SavedOwnerProjection.openFrom(context, { emit -> rows.forEach { emit(IndexRow.of(it)) } }).use { owners ->
            rows.forEach { assertEquals(it.request.id, soleOwner(rows, it.request.id), owners.soleOwner(it)) }
            assertFalse(owners.soleOwner(row("saved/missing")))
        }
        assertScratchEmpty()
    }

    @Test fun duplicateIdsAndExactUtf16NamesNeverBecomeSoleOwners(): Unit = io {
        val repeated = row("saved/duplicate")
        // String.getBytes(UTF_8) would replace both unpaired surrogate IDs with the same byte.
        val first = row("saved/\uD800"); val second = row("saved/\uD801")
        val rows = listOf(repeated, repeated, first, second, row("saved/nul\u0000end"), row("saved/nul"))
        SavedOwnerProjection.openFrom(context, { emit -> rows.forEach { emit(IndexRow.of(it)) } }).use { owners ->
            assertFalse(owners.soleOwner(repeated))
            rows.drop(2).forEach { assertTrue(it.request.id, owners.soleOwner(it)) }
        }
    }

    @Test fun sourceFailureAndCancellationCannotLeakAnOldOrPartialCensus(): Unit = io {
        val original = row("saved/kept")
        assertThrows(IOException::class.java) {
            SavedOwnerProjection.openFrom(context, { emit -> emit(IndexRow.of(original)); throw IOException("index read failed") })
        }
        assertScratchEmpty()
        var checks = 0
        assertThrows(CancellationException::class.java) {
            SavedOwnerProjection.openFrom(context, { emit -> repeat(20) { emit(IndexRow.of(row("saved/$it"))) } }) {
                if (++checks == 6) throw CancellationException()
            }
        }
        assertScratchEmpty()
        SavedOwnerProjection.openFrom(context, { emit -> emit(IndexRow.of(row("saved/new"))) }).use {
            assertFalse(it.soleOwner(original)); assertTrue(it.soleOwner(row("saved/new")))
        }
        assertScratchEmpty()
    }

    @Test fun nativeIndexAndCacheProjectionKeepsHiddenOwnersAndOriginalData(): Unit = io {
        val database = StandaloneDatabaseProvider(context)
        val cache = SimpleCache(folders.newFolder(), NoOpCacheEvictor(), database)
        try {
            cache.checkInitialization()
            val index = DefaultDownloadIndex(database, "owner_projection")
            val tags = encodeSong(TauonTrack(7, "Original", "Artist", "Album", 1000, true, false))
            val original = row("saved/original", data = tags).also(index::putDownload)
            val hidden = row("hidden/" + "h".repeat(1400), original.request.id, Download.STATE_FAILED,
                ByteArray(256 * 1024)).also(index::putDownload)
            val sole = row("saved/sole", data = tags).also(index::putDownload)
            for (key in listOf(original.request.id, sole.request.id)) {
                val hole = requireNotNull(cache.startReadWrite(key, 0, 4))
                try {
                    val file = cache.startFile(key, 0, 4); file.writeBytes(byteArrayOf(1,2,3,4))
                    cache.commitFile(file, 4)
                    cache.applyContentMetadataMutations(key, ContentMetadataMutations.setContentLength(ContentMetadataMutations(), 4L))
                } finally { cache.releaseHoleSpan(hole) }
            }
            val expected = savedInventory(SavedShelf.Phone, index, cache, null) { false }
            val actual = mutableListOf<SavedEntry>()
            SavedOwnerProjection.open(context, index).use { owners ->
                forEachSavedEntry(SavedShelf.Phone, index, cache, null, { false }, ownership = owners::soleOwner) { actual += it }
            }
            assertEquals(expected, actual)
            assertFalse(actual.single { it.ref.requestId == original.request.id }.removable)
            assertTrue(actual.single { it.ref.requestId == sole.request.id }.removable)
            assertArrayEquals(tags, index.getDownload(original.request.id)!!.request.data)
            assertArrayEquals(hidden.request.data, index.getDownload(hidden.request.id)!!.request.data)
            assertTrue(cache.isCached(original.request.id, 0, 4)); assertTrue(cache.isCached(sole.request.id, 0, 4))
            assertScratchEmpty()
        } finally { try { cache.release() } finally { database.close() } }
    }

    @Test fun aLargeStreamIsQueryableWithoutAWholeJavaCensusAndIsGoneAfterClose(): Unit = io {
        SavedOwnerProjection.openFrom(context, { emit ->
            repeat(2501) { emit(IndexRow("saved/$it", "saved/$it", true, Download.STATE_COMPLETED)) }
            emit(IndexRow("hidden/alias", "saved/2500", false, Download.STATE_FAILED))
        }).use { owners ->
            assertTrue(owners.soleOwner(row("saved/0")))
            assertTrue(owners.soleOwner(row("saved/2499")))
            assertFalse(owners.soleOwner(row("saved/2500")))
        }
        assertScratchEmpty()
    }

    @Test fun mainThreadAndUseAfterCloseAreRefusedBeforeIo() {
        assertThrows(IllegalStateException::class.java) { SavedOwnerProjection.openFrom(context, { }) }
        val owners = io { SavedOwnerProjection.openFrom(context, { it(IndexRow.of(row("saved/a"))) }) }
        assertThrows(IllegalStateException::class.java) { owners.soleOwner(row("saved/a")) }
        io {
            owners.close(); owners.close()
            assertThrows(IllegalStateException::class.java) { owners.soleOwner(row("saved/a")) }
            assertScratchEmpty()
        }
    }

    private fun assertScratchEmpty() {
        SQLiteDatabase.openOrCreateDatabase(File(context.noBackupFilesDir, "saved-projection-owners-v1.db"), null).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM names", null).use { assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0)) }
        }
    }
    private fun row(id: String, key: String = id, state: Int = Download.STATE_COMPLETED,
        data: ByteArray = byteArrayOf()): Download {
        val request = DownloadRequest.Builder(id, Uri.parse("http://127.0.0.1:7814/api1/file/7"))
            .setCustomCacheKey(key).setData(data).build()
        return Download(request, state, 1, 2, 4,
            if (state == Download.STATE_STOPPED) RETAINED_STOP_REASON else Download.STOP_REASON_NONE,
            if (state == Download.STATE_FAILED) Download.FAILURE_REASON_UNKNOWN else Download.FAILURE_REASON_NONE)
    }
}
