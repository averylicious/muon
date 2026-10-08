package dev.avery.muon

import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadCursor
import androidx.media3.exoplayer.offline.DownloadIndex
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayedClaimDiskTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var index: DefaultDownloadIndex
    @Before fun setup() {
        database = StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        index = DefaultDownloadIndex(database, "played-claims")
    }
    @After fun cleanup() { database.close() }
    private fun put(id: String, key: String, state: Int = Download.STATE_COMPLETED) {
        val request = DownloadRequest.Builder(id, Uri.parse("http://h/api1/file/1"))
            .setCustomCacheKey(key).setData(byteArrayOf(0, 1, 2)).build()
        index.putDownload(Download(request, state, 1, 2, 3, 0, 0))
    }
    @Test fun hiddenAliasesEveryStateAndExactUnicodeNamesRemainProtected() {
        val keys = listOf("played:A", "played:a", "played:é😀\u0000", "played:" + "x".repeat(5000))
        keys.forEachIndexed { i, key -> put("unknown/$i", key, if (i % 2 == 0) Download.STATE_STOPPED else Download.STATE_FAILED) }
        put("ordinary", "saved/ordinary")
        val before = keys.mapIndexed { i, _ -> index.getDownload("unknown/$i")!!.request }
        PlayedClaimDisk(folders.newFile()).use { claims ->
            assertFalse(claims.removable("played:free")); claims.read(index); assertTrue(claims.known)
            keys.forEach { assertFalse(claims.removable(it)) }
            assertTrue(claims.removable("played:free")); assertTrue(claims.removable("played:É😀\u0000"))
        }
        assertEquals(before, keys.mapIndexed { i, _ -> index.getDownload("unknown/$i")!!.request })
    }
    @Test fun restartNeverTrustsPersistentDerivedClaimsBeforeACompleteFreshRead() {
        val file = folders.newFile(); put("hidden", "played:old")
        PlayedClaimDisk(file).use { it.read(index); assertFalse(it.removable("played:old")) }
        index.removeDownload("hidden"); put("new-hidden", "played:new")
        PlayedClaimDisk(file).use {
            assertFalse(it.known); assertFalse(it.removable("played:old")); assertFalse(it.removable("played:new"))
            it.read(index); assertTrue(it.known); assertTrue(it.removable("played:old")); assertFalse(it.removable("played:new"))
        }
    }
    @Test fun midScanAndCloseFailuresRevokeAllEvictionAuthorityUntilSuccessfulRebuild() {
        put("hidden", "played:kept")
        val file = folders.newFile()
        PlayedClaimDisk(file).use { claims ->
            claims.read(index); assertTrue(claims.removable("played:free"))
            val failure = object : DownloadIndex by index {
                override fun getDownloads(vararg states: Int): DownloadCursor {
                    val cursor = index.getDownloads(*states)
                    return object : DownloadCursor by cursor {
                        override fun moveToNext(): Boolean = throw java.io.IOException("read failure")
                    }
                }
            }
            claims.read(failure); assertFalse(claims.known)
            assertFalse(claims.removable("played:free")); assertFalse(claims.removable("played:kept"))
            val closeFailure = object : DownloadIndex by index {
                override fun getDownloads(vararg states: Int): DownloadCursor {
                    val cursor = index.getDownloads(*states)
                    return object : DownloadCursor by cursor {
                        override fun close() { cursor.close(); throw java.io.IOException("close failure") }
                    }
                }
            }
            claims.read(closeFailure); assertFalse(claims.known); assertFalse(claims.removable("played:free"))
            claims.read(index); assertTrue(claims.known); assertFalse(claims.removable("played:kept"))
        }
    }
    @Test fun unusablePrivateDatabaseRefusesEvictionAndPreservesAllIndexData() {
        put("hidden", "played:kept")
        PlayedClaimDisk(folders.newFolder()).use {
            it.read(index); assertFalse(it.known); assertFalse(it.removable("played:free"))
        }
        assertNotNull(index.getDownload("hidden"))
    }
}
