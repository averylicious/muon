@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadCursor
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.WritableDownloadIndex
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class StorageDownloadIndexTest {
    private lateinit var database:StandaloneDatabaseProvider
    private lateinit var index:DefaultDownloadIndex
    private lateinit var barrier:SavedStorageBarrier
    private lateinit var saved:Download
    @Before fun before() {
        database=StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        index=DefaultDownloadIndex(database,"owned_index_test")
        barrier=SavedStorageBarrier(1)
        saved=Download(DownloadRequest.Builder("saved",Uri.parse("http://127.0.0.1:1/fixture")).setCustomCacheKey("saved").build(),
            Download.STATE_COMPLETED,1,2,5,0,Download.FAILURE_REASON_NONE)
        index.putDownload(saved)
    }
    @After fun after() { database.close() }
    private fun held() { assertEquals(1,barrier.active); assertThrows(IOException::class.java) { barrier.exclusive() } }
    private fun guarded(actual:WritableDownloadIndex=index)=StorageDownloadIndex(actual,barrier)

    @Test fun actualNativeCursorHoldsPermitAtEofUntilKnownClose() {
        val cursor=guarded().getDownloads(); held()
        assertTrue(cursor.moveToNext()); assertEquals("saved",cursor.download.request.id)
        assertFalse(cursor.moveToNext()); held(); assertFalse(cursor.isClosed)
        cursor.close(); cursor.close(); assertTrue(cursor.isClosed); assertTrue(barrier.quiescent)
        assertThrows(IOException::class.java) { cursor.moveToFirst() }
    }

    @Test fun earlyExitClosesNativeCursorBeforeReleasingItsPermit() {
        var closes=0
        val actual=object:WritableDownloadIndex by index {
            override fun getDownloads(vararg states:Int):DownloadCursor {
                val raw=index.getDownloads(*states)
                return object:DownloadCursor by raw { override fun close() { held(); closes++; raw.close() } }
            }
        }
        guarded(actual).getDownloads().use { assertTrue(it.moveToNext()); held() }
        assertEquals(1,closes); assertTrue(barrier.quiescent)
    }

    @Test fun exclusiveAdmissionRefusesReadsAndEveryMutationWithoutTouchingNativeIndex() {
        var calls=0
        val actual=object:WritableDownloadIndex by index {
            override fun getDownload(id:String):Download? { calls++; return index.getDownload(id) }
            override fun getDownloads(vararg states:Int):DownloadCursor { calls++; return index.getDownloads(*states) }
            override fun putDownload(download:Download) { calls++; index.putDownload(download) }
            override fun removeDownload(id:String) { calls++; index.removeDownload(id) }
            override fun setDownloadingStatesToQueued() { calls++; index.setDownloadingStatesToQueued() }
            override fun setStatesToRemoving() { calls++; index.setStatesToRemoving() }
            override fun setStopReason(reason:Int) { calls++; index.setStopReason(reason) }
            override fun setStopReason(id:String,reason:Int) { calls++; index.setStopReason(id,reason) }
        }
        val owned=guarded(actual); val exclusive=barrier.exclusive()
        val attempts=listOf<()->Unit>({owned.getDownload("saved")},{owned.getDownloads()},{owned.putDownload(saved)},
            {owned.removeDownload("saved")},{owned.setDownloadingStatesToQueued()},{owned.setStatesToRemoving()},
            {owned.setStopReason(8)},{owned.setStopReason("saved",8)})
        attempts.forEach { assertThrows(IOException::class.java) { it() } }
        assertEquals(0,calls); assertEquals(Download.STATE_COMPLETED,index.getDownload("saved")!!.state)
        exclusive.close(); assertTrue(barrier.quiescent)
    }

    @Test fun successfulScalarAndMutationCallsHoldPermitAndPreserveSupportedDelegation() {
        var calls=0
        val actual=object:WritableDownloadIndex by index {
            override fun getDownload(id:String):Download? { held(); calls++; return index.getDownload(id) }
            override fun putDownload(download:Download) { held(); calls++; index.putDownload(download) }
            override fun removeDownload(id:String) { held(); calls++; index.removeDownload(id) }
            override fun setDownloadingStatesToQueued() { held(); calls++; index.setDownloadingStatesToQueued() }
            override fun setStatesToRemoving() { held(); calls++; index.setStatesToRemoving() }
            override fun setStopReason(reason:Int) { held(); calls++; index.setStopReason(reason) }
            override fun setStopReason(id:String,reason:Int) { held(); calls++; index.setStopReason(id,reason) }
        }
        val owned=guarded(actual)
        assertEquals("saved",owned.getDownload("saved")!!.request.id)
        owned.setStopReason("saved",8); assertEquals(8,index.getDownload("saved")!!.stopReason)
        owned.setStopReason(9); assertEquals(9,index.getDownload("saved")!!.stopReason)
        owned.setDownloadingStatesToQueued(); owned.setStatesToRemoving()
        assertEquals(Download.STATE_REMOVING,index.getDownload("saved")!!.state)
        owned.putDownload(saved); owned.removeDownload("saved")
        assertNull(index.getDownload("saved")); assertEquals(7,calls); assertTrue(barrier.quiescent)
    }

    @Test fun unknownCursorCloseRetainsExactCursorAndPermitWithoutRetry() {
        var closes=0; var quarantines=0; lateinit var raw:DownloadCursor
        val actual=object:WritableDownloadIndex by index {
            override fun getDownloads(vararg states:Int):DownloadCursor {
                val cursor=index.getDownloads(*states)
                raw=object:DownloadCursor by cursor { override fun close() { closes++; cursor.close(); throw IOException("Unknown close") } }
                return raw
            }
        }
        val cursor=StorageDownloadIndex(actual,barrier,{quarantines++}).getDownloads()
        assertTrue(cursor.moveToNext())
        assertThrows(IOException::class.java) { cursor.close() }
        assertEquals(1,barrier.active); assertFalse(barrier.allowsShared); assertEquals(1,quarantines)
        assertThrows(IOException::class.java) { cursor.close() }; assertEquals(1,closes)
        assertSame(raw,cursor.javaClass.getDeclaredField("actualCursor").apply { isAccessible=true }.get(cursor))
        assertNotNull(index.getDownload("saved"))
    }

    @Test fun swallowedReentrantCursorCloseStillQuarantinesInsteadOfReleasingPermit() {
        lateinit var cursor:DownloadCursor; var closes=0
        val actual=object:WritableDownloadIndex by index {
            override fun getDownloads(vararg states:Int):DownloadCursor {
                val raw=index.getDownloads(*states)
                return object:DownloadCursor by raw {
                    override fun close() { closes++; try { cursor.close() } catch(_:IOException) {}; raw.close() }
                }
            }
        }
        cursor=guarded(actual).getDownloads()
        assertThrows(IOException::class.java) { cursor.close() }
        assertEquals(1,closes); assertEquals(1,barrier.active); assertFalse(barrier.allowsShared)
    }

    @Test fun failedConstructionOrOpaqueScalarDoesNotGrantRetryAuthority() {
        var calls=0
        val actual=object:WritableDownloadIndex by index {
            override fun getDownloads(vararg states:Int):DownloadCursor { calls++; throw IOException("Unreturned native cursor") }
        }
        val owned=guarded(actual)
        assertThrows(IOException::class.java) { owned.getDownloads() }
        assertEquals(1,barrier.active); assertFalse(barrier.allowsShared)
        assertThrows(IOException::class.java) { owned.getDownloads() }; assertEquals(1,calls)
        val second=SavedStorageBarrier(1)
        val scalar=StorageDownloadIndex(object:WritableDownloadIndex by index {
            override fun getDownload(id:String):Download?=throw IOException("Opaque failure")
        },second)
        assertThrows(IOException::class.java) { scalar.getDownload("saved") }
        assertEquals(1,second.active); assertFalse(second.allowsShared)
        assertThrows(IOException::class.java) { scalar.removeDownload("saved") }; assertNotNull(index.getDownload("saved"))
    }

    @Test fun aKnownCursorReadFailureCanStillCloseExactlyOnceAndReleaseItsPermit() {
        var closes=0
        val actual=object:WritableDownloadIndex by index {
            override fun getDownloads(vararg states:Int):DownloadCursor {
                val raw=index.getDownloads(*states)
                return object:DownloadCursor by raw {
                    override fun getDownload():Download=throw IllegalStateException("Malformed fixture row")
                    override fun close() { closes++; raw.close() }
                }
            }
        }
        val cursor=guarded(actual).getDownloads(); assertTrue(cursor.moveToNext())
        assertThrows(IllegalStateException::class.java) { cursor.download }
        cursor.close(); assertEquals(1,closes); assertTrue(barrier.quiescent); assertTrue(barrier.allowsShared)
    }

    @Test fun heldCursorBudgetRefusesAnotherCursorWithoutOpeningIt() {
        val owned=guarded(); val first=owned.getDownloads()
        assertThrows(IOException::class.java) { owned.getDownloads() }; first.close()
        owned.getDownloads().use { assertTrue(it.moveToFirst()); assertEquals("saved",it.download.request.id) }
        assertTrue(barrier.quiescent)
    }
}
