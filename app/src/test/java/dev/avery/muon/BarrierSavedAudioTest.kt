@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.net.Uri
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
class BarrierSavedAudioTest {
    private val spec=DataSpec(Uri.parse("muon-saved:test"))
    private fun saved(factory:DataSource.Factory)=object:SavedAudio {
        override val source=factory
        override fun contains(key:String)=true
        override fun inspect(key:String):SavedAudioState=throw UnsupportedOperationException()
        override fun forEachKey(visit:(String)->Boolean) { if(visit("a")) visit("b") }
    }
    @Test fun eofRetainsPermitUntilActualCloseAndThenAllowsExclusiveWork() {
        val barrier=SavedStorageBarrier(); var closes=0
        val audio=BarrierSavedAudio(saved(DataSource.Factory {
            val child=ByteArrayDataSource(byteArrayOf(3))
            object:DataSource by child { override fun close() { closes++; child.close() } }
        }),barrier)
        val reader=audio.source.createDataSource(); reader.open(spec)
        assertEquals(1,reader.read(ByteArray(1),0,1)); assertEquals(-1,reader.read(ByteArray(1),0,1))
        assertEquals(1,barrier.active); assertThrows(IOException::class.java) { barrier.exclusive() }
        barrier.stopAdmission(); reader.close(); reader.close()
        assertEquals(1,closes); assertTrue(barrier.quiescent)
    }
    @Test fun failedOpenWithKnownCloseReturnsPermitButUnknownCloseRetainsIt() {
        for(unknown in listOf(false,true)) {
            val barrier=SavedStorageBarrier(); var closes=0
            val audio=BarrierSavedAudio(saved(DataSource.Factory {
                val child=ByteArrayDataSource(byteArrayOf(1))
                object:DataSource by child {
                    override fun open(spec:DataSpec):Long=throw IOException("Injected failed open")
                    override fun close() { closes++; if(unknown) throw IOException("Injected unknown close"); child.close() }
                }
            }),barrier)
            val reader=audio.source.createDataSource()
            assertThrows(IOException::class.java) { reader.open(spec) }
            assertEquals(if(unknown) 1 else 0,barrier.active); assertEquals(1,closes)
            if(unknown) {
                assertThrows(IOException::class.java) { reader.close() }
                assertThrows(IOException::class.java) { barrier.shared() }; assertEquals(1,closes)
            } else reader.close()
        }
    }
    @Test fun volumeInvalidationDuringReadIsStickyAndCloseStillDrains() {
        val barrier=SavedStorageBarrier()
        val audio=BarrierSavedAudio(saved(DataSource.Factory {
            val child=ByteArrayDataSource(byteArrayOf(1,2))
            object:DataSource by child {
                override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                    val count=child.read(buffer,offset,length); barrier.invalidate(); return count
                }
            }
        }),barrier)
        val reader=audio.source.createDataSource(); reader.open(spec)
        assertThrows(IOException::class.java) { reader.read(ByteArray(1),0,1) }
        assertThrows(IOException::class.java) { reader.read(ByteArray(1),0,1) }
        reader.close(); assertTrue(barrier.quiescent)
    }
    @Test fun enumerationCallbackCannotStartDestructionAndInvalidationStopsNextRow() {
        val barrier=SavedStorageBarrier(); val audio=BarrierSavedAudio(saved(DataSource.Factory { ByteArrayDataSource(byteArrayOf(1)) }),barrier)
        var visits=0
        assertThrows(IOException::class.java) {
            audio.forEachKey {
                visits++; assertThrows(IOException::class.java) { barrier.exclusive() }
                barrier.invalidate(); true
            }
        }
        assertEquals(1,visits); assertTrue(barrier.quiescent)
    }
}
