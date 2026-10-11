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
    @Test fun scalarProjectionUnknownDirectoryCloseRetainsActualOwnershipAndSharedPermit() {
        val directory=java.nio.file.Files.createTempDirectory("muon-projection-close")
        val actual=java.nio.file.Files.newDirectoryStream(directory)
        val ownership=MigrationIoOwnership(directories={
            object:java.nio.file.DirectoryStream<java.nio.file.Path> {
                override fun iterator()=actual.iterator()
                override fun close():Unit=throw IOException("Injected unknown actual directory close")
            }
        })
        try {
            val tracked=ownership.directory(directory); val barrier=SavedStorageBarrier()
            val base=saved(DataSource.Factory { ByteArrayDataSource(byteArrayOf(1)) })
            val audio=BarrierSavedAudio(object:SavedAudio by base {
                override fun inspect(key:String):SavedAudioState { tracked.close(); throw AssertionError("Unknown close was swallowed") }
            },barrier)
            val failure=assertThrows(MigrationIoUncertain::class.java) { audio.inspect("a") }
            assertSame(ownership,failure.ownership); assertFalse(ownership.quiescent)
            assertEquals(1,barrier.active); assertThrows(IOException::class.java) { barrier.exclusive() }
        } finally { actual.close(); java.nio.file.Files.delete(directory) }
    }

    @Test fun failedSourceConstructionRetainsExactFactoryAndActualUnreturnedDescriptor() {
        val file=java.nio.file.Files.createTempFile("muon-reader-construction",".bin").toFile()
        file.writeBytes(byteArrayOf(4,8)); val raw=java.io.FileInputStream(file)
        val failure=IOException("Factory allocated before throwing"); var made=0
        val factory=DataSource.Factory { made++; check(raw.channel.isOpen); throw failure }
        val barrier=SavedStorageBarrier(1); val reader=BarrierSavedAudio(saved(factory),barrier).source.createDataSource()
        try {
            assertSame(failure,assertThrows(IOException::class.java) { reader.open(spec) })
            assertEquals(1,made); assertEquals(1,barrier.active); assertFalse(barrier.allowsShared)
            assertSame(factory,reader.javaClass.getDeclaredField("construction").apply { isAccessible=true }.get(reader))
            assertSame(failure,reader.javaClass.getDeclaredField("uncertainty").apply { isAccessible=true }.get(reader))
            assertTrue(raw.channel.isOpen); assertEquals(4,raw.read())
            assertThrows(IOException::class.java) { reader.close() }
            assertThrows(IllegalStateException::class.java) { reader.open(spec) }; assertEquals(1,made)
            assertArrayEquals(byteArrayOf(4,8),file.readBytes())
        } finally { raw.close(); file.delete() }
    }
    @Test fun failedSourceGetterRetainsTheExactAudioOwnerBeforeAFactoryCanReturn() {
        val base=saved(DataSource.Factory { ByteArrayDataSource(byteArrayOf(1)) })
        val exact=object:SavedAudio by base { override val source:DataSource.Factory get()=throw IOException("Unknown source getter") }
        val barrier=SavedStorageBarrier(1); val reader=BarrierSavedAudio(exact,barrier).source.createDataSource()
        assertThrows(IOException::class.java) { reader.open(spec) }
        assertSame(exact,reader.javaClass.getDeclaredField("construction").apply { isAccessible=true }.get(reader))
        assertEquals(1,barrier.active); assertFalse(barrier.allowsShared)
    }
    @Test fun refusedReentrantCloseDoesNotPoisonAnOtherwiseKnownActualClose() {
        val barrier=SavedStorageBarrier(1); lateinit var reader:DataSource; var closes=0
        val raw=ByteArrayDataSource(byteArrayOf(1))
        val exact=object:DataSource by raw {
            override fun close() { closes++; try { reader.close() } catch(_:IllegalStateException) {}; raw.close() }
        }
        reader=BarrierSavedAudio(saved(DataSource.Factory { exact }),barrier).source.createDataSource()
        reader.open(spec)
        reader.close()
        assertEquals(1,closes); assertTrue(barrier.quiescent); assertTrue(barrier.allowsShared)
        reader.close(); assertEquals(1,closes)
    }
    @Test fun ordinaryFailedAdmissionOpensNoChildAndDoesNotPoisonTheOwner() {
        val barrier=SavedStorageBarrier(1); var made=0
        val reader=BarrierSavedAudio(saved(DataSource.Factory { made++; ByteArrayDataSource(byteArrayOf(1)) }),barrier).source.createDataSource()
        val exclusive=barrier.exclusive()
        assertThrows(IOException::class.java) { reader.open(spec) }; assertEquals(0,made)
        exclusive.close(); reader.open(spec); reader.close()
        assertEquals(1,made); assertTrue(barrier.quiescent); assertTrue(barrier.allowsShared)
    }
    @Test fun typedReadUncertaintyRetainsTheActualChildAlongsideThePartialOwner() {
        val barrier=SavedStorageBarrier(1); val partial=Any(); val failure=PartitionOwnershipUncertain(partial,IOException("Unknown child"))
        val raw=ByteArrayDataSource(byteArrayOf(1)); var closes=0
        val exact=object:DataSource by raw {
            override fun read(buffer:ByteArray,offset:Int,length:Int):Int=throw failure
            override fun close() { closes++; raw.close() }
        }
        val reader=BarrierSavedAudio(saved(DataSource.Factory { exact }),barrier).source.createDataSource()
        reader.open(spec); assertSame(failure,assertThrows(PartitionOwnershipUncertain::class.java) { reader.read(ByteArray(1),0,1) })
        assertSame(exact,reader.javaClass.getDeclaredField("child").apply { isAccessible=true }.get(reader))
        assertSame(failure,reader.javaClass.getDeclaredField("uncertainty").apply { isAccessible=true }.get(reader))
        assertEquals(1,barrier.active); assertFalse(barrier.allowsShared)
        assertThrows(IOException::class.java) { reader.close() }; assertEquals(0,closes)
        raw.close() // Fixture-owned cleanup only, never a production retry of the uncertain child.
    }

}
