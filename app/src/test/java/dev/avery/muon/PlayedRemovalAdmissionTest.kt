@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.IOException
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PlayedRemovalAdmissionTest {
    @get:Rule val folders=TemporaryFolder()
    private val db by lazy { StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()) }
    private val caches=mutableListOf<SimpleCache>()
    private val payload=byteArrayOf(3,6,9)
    private fun cache()=SimpleCache(folders.newFolder(),NoOpCacheEvictor(),db).also { c ->
        caches+=c; c.checkInitialization()
        val span=requireNotNull(c.startReadWrite("played:test",0,3))
        try { val file=c.startFile("played:test",0,3); file.writeBytes(payload); c.commitFile(file,3) }
        finally { c.releaseHoleSpan(span) }
    }
    @After fun close() { try { caches.asReversed().forEach { it.release() } } finally { db.close() } }
    @Test fun actualRemovalOwnsPermitUntilNativeMutationReturns() {
        val actual=cache(); val gate=SavedStorageBarrier(); var calls=0
        val observed=object:Cache by actual {
            override fun removeResource(key:String) {
                calls++; assertEquals(1,gate.active); assertThrows(IOException::class.java) { gate.exclusive() }
                actual.removeResource(key)
            }
        }
        assertEquals(OfflineStore.SavedRemoval.Sent,OfflineStore.removePlayedCopy("played:test",observed,gate,{true},{fail("Known mutation")}))
        assertEquals(1,calls); assertEquals(0L,actual.cacheSpace); assertTrue(gate.quiescent)
    }
    @Test fun exclusiveAdmissionRefusesBeforeAnyClaimOrNativeMutation() {
        val actual=cache(); val gate=SavedStorageBarrier(); val lock=gate.exclusive(); var calls=0
        val observed=object:Cache by actual { override fun removeResource(key:String) { calls++; actual.removeResource(key) } }
        try {
            assertEquals(OfflineStore.SavedRemoval.Busy,OfflineStore.removePlayedCopy("played:test",observed,gate,{fail("Claim consulted before admission");true},{}))
            assertEquals(0,calls); assertEquals(3L,actual.cacheSpace)
        } finally { lock.close() }
    }
    @Test fun aProtectedCopyIsNotRemovedAndKnownRefusalDrainsPermit() {
        val actual=cache(); val gate=SavedStorageBarrier()
        assertEquals(OfflineStore.SavedRemoval.NotOwned,OfflineStore.removePlayedCopy("played:test",actual,gate,{false},{}))
        assertEquals(3L,actual.cacheSpace); assertTrue(gate.quiescent)
    }
    @Test fun thrownNativeMutationNeverReportsSuccessOrAllowsReplacement() {
        val actual=cache(); val gate=SavedStorageBarrier(); var calls=0; var notices=0
        val observed=object:Cache by actual { override fun removeResource(key:String) { calls++; throw IOException("Unknown actual mutation outcome") } }
        assertEquals(OfflineStore.SavedRemoval.Failed,OfflineStore.removePlayedCopy("played:test",observed,gate,{true},{notices++}))
        assertEquals(1,calls); assertEquals(1,notices); assertEquals(1,gate.active)
        assertEquals(OfflineStore.SavedRemoval.Busy,OfflineStore.removePlayedCopy("played:test",observed,gate,{true},{}))
        assertEquals(1,calls); assertEquals(3L,actual.cacheSpace); assertFalse(gate.allowsShared)
    }
}
