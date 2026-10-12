@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloaderFactory
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class OwnedLegacyShelfTest {
    @get:Rule val folders=TemporaryFolder()
    private val app get()=RuntimeEnvironment.getApplication()
    private val database by lazy { StandaloneDatabaseProvider(app) }
    private val caches=ArrayList<SimpleCache>()
    private val managers=ArrayList<DownloadManager>()
    private val payload=byteArrayOf(1,2,3,4)
    private val spec=DataSpec.Builder().setUri("https://fixture.invalid/saved").setKey("kept").build()
    @After fun close() { try { managers.forEach { it.release() }; caches.forEach { it.release() } } finally { database.close() } }
    private fun shelf(gate:SavedStorageBarrier,files:DataSource.Factory?=null):Shelf {
        val cache=SimpleCache(folders.newFolder(),NoOpCacheEvictor(),database).also { caches+=it; it.checkInitialization() }
        val hole=cache.startReadWrite("kept",0,4)
        try { val file=cache.startFile("kept",0,4); file.writeBytes(payload); cache.commitFile(file,4) }
        finally { cache.releaseHoleSpan(hole) }
        cache.applyContentMetadataMutations("kept",ContentMetadataMutations.setContentLength(ContentMetadataMutations(),4))
        val manager=DownloadManager(app,DefaultDownloadIndex(database,"gate${managers.size}"),
            DownloaderFactory { error("No fixture downloads") }).also { managers+=it }
        return Shelf.ownedLegacy(cache,manager,MuonDownloadService::class.java,gate,files=files)
    }
    @Test fun actualPhoneAndCardReadersAtEofShareOneGateUntilBothClose() {
        val gate=SavedStorageBarrier(); val phone=shelf(gate); val card=shelf(gate)
        val one=phone.savedSource.createDataSource(); val two=card.savedSource.createDataSource()
        try {
            one.open(spec); two.open(spec)
            for(reader in listOf(one,two)) {
                val bytes=ByteArray(4); assertEquals(4,reader.read(bytes,0,4)); assertArrayEquals(payload,bytes)
                assertEquals(C.RESULT_END_OF_INPUT,reader.read(bytes,0,4))
            }
            assertEquals(2,gate.active); assertThrows(IOException::class.java) { gate.exclusive() }
            one.close(); assertEquals(1,gate.active); assertThrows(IOException::class.java) { gate.exclusive() }
            two.close(); gate.exclusive().let { held ->
                assertFalse(phone.available()); assertFalse(card.available())
                assertThrows(IOException::class.java) { phone.audio.inspect("kept") }
                held.close()
            }
            assertTrue(phone.available()); assertTrue(card.available())
            assertEquals(SavedCoverage.Full,phone.audio.inspect("kept").coverage)
        } finally { one.close(); two.close() }
    }
    @Test fun readerBudgetRefusesBeforeAnotherFileOpenAndAllowsRetryAfterKnownDrain() {
        val gate=SavedStorageBarrier(1); val phone=shelf(gate)
        val one=phone.savedSource.createDataSource(); val two=phone.savedSource.createDataSource()
        try {
            one.open(spec); assertFalse(phone.available())
            assertThrows(IOException::class.java) { two.open(spec) }; assertEquals(1,gate.active)
            one.close(); assertTrue(phone.available())
            two.open(spec); assertEquals(1,gate.active)
        } finally { one.close(); two.close() }
        assertTrue(gate.quiescent)
    }
    @Test fun actualFileCloseUncertaintyPausesBothShelvesAndNeverClosesAgain() {
        val gate=SavedStorageBarrier(); var raw:FileDataSource?=null; var closes=0
        val phone=shelf(gate,DataSource.Factory {
            val actual=FileDataSource().also { raw=it }
            object:DataSource by actual { override fun close():Unit { closes++; throw IOException("Injected unknown close") } }
        }); val card=shelf(gate)
        val reader=phone.savedSource.createDataSource()
        try {
            reader.open(spec); assertEquals(4,reader.read(ByteArray(4),0,4))
            assertThrows(IOException::class.java) { reader.close() }
            assertFalse(phone.available()); assertFalse(card.available()); assertEquals(1,gate.active)
            assertThrows(IOException::class.java) { reader.close() }; assertEquals(1,closes)
            assertThrows(IOException::class.java) { card.savedSource.createDataSource().open(spec) }
            assertArrayEquals(payload,requireNotNull(phone.cache.getCachedSpans("kept").single().file).readBytes())
        } finally { raw?.close() } // Fixture-only retirement; production retains exact child/permit.
    }
}
