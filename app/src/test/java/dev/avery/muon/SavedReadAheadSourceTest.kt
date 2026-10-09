@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
class SavedReadAheadSourceTest {
    private fun spec(saved:Boolean=true,position:Long=0,length:Long=C.LENGTH_UNSET.toLong())=
        DataSpec.Builder().setUri(if(saved) "muon-saved:fixture" else "https://example.invalid/live")
            .setPosition(position).setLength(length).build()
    private class Actual(val bytes:ByteArray=ByteArray(20) { it.toByte() }):DataSource {
        var reads=0; var closes=0; var opened:DataSpec?=null; var cursor=0; var fail=false
        var unknownLength=false; var failedOpen=false; var failedClose=false; var zeroOnce=false
        var onRead:(()->Unit)?=null
        val sizes=mutableListOf<Int>();val listeners=mutableListOf<TransferListener>()
        override fun addTransferListener(listener:TransferListener) { listeners+=listener }
        override fun open(spec:DataSpec):Long {
            opened=spec; cursor=spec.position.toInt()
            if(failedOpen) throw IOException("Fixture open failure")
            return if(unknownLength) C.LENGTH_UNSET.toLong() else minOf((bytes.size-cursor).toLong(),
                if(spec.length<0) Long.MAX_VALUE else spec.length)
        }
        override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
            reads++;sizes+=length;onRead?.invoke()
            if(fail) throw IOException("Fixture unavailable")
            if(zeroOnce) { zeroOnce=false; return 0 }
            if(cursor==bytes.size) return C.RESULT_END_OF_INPUT
            val n=minOf(length,bytes.size-cursor);System.arraycopy(bytes,cursor,buffer,offset,n);cursor+=n;return n
        }
        override fun getUri():Uri?=opened?.uri
        override fun getResponseHeaders()=mapOf("Fixture" to listOf("local"))
        override fun close() { closes++;if(failedClose) throw IOException("Fixture close uncertainty");opened=null }
    }
    @Test fun savedBytePeeksReuseBoundedRefillsWithoutChangingBytesOrOpenLength() {
        val actual=Actual();val source=SavedReadAheadSource(actual,8)
        assertEquals(20L,source.open(spec()));val got=ByteArray(20)
        repeat(20) { i -> assertEquals(1,source.read(got,i,1)) }
        assertArrayEquals(actual.bytes,got);assertEquals(listOf(8,8,4),actual.sizes)
        assertEquals(-1,source.read(ByteArray(1),0,1));assertEquals(3,actual.reads)
        source.close();assertEquals(1,actual.closes);assertNull(source.uri)
    }
    @Test fun liveStreamsHaveNoReadAheadAndPreserveHeadersUriAndListeners() {
        val actual=Actual();val source=SavedReadAheadSource(actual,8)
        val listener=object:TransferListener {
            override fun onTransferInitializing(s:DataSource,d:DataSpec,n:Boolean)=Unit
            override fun onTransferStart(s:DataSource,d:DataSpec,n:Boolean)=Unit
            override fun onBytesTransferred(s:DataSource,d:DataSpec,n:Boolean,b:Int)=Unit
            override fun onTransferEnd(s:DataSource,d:DataSpec,n:Boolean)=Unit
        }
        source.addTransferListener(listener);source.open(spec(saved=false))
        repeat(3) { source.read(ByteArray(1),0,1) };assertEquals(listOf(1,1,1),actual.sizes)
        assertEquals(actual.uri,source.uri);assertEquals(actual.responseHeaders,source.responseHeaders)
        assertEquals(listOf(listener),actual.listeners);source.close()
    }
    @Test fun unknownLengthAndSpecifiedRangeCannotReadPastRequestedEnd() {
        val actual=Actual().apply { unknownLength=true };val source=SavedReadAheadSource(actual,8)
        assertEquals(-1L,source.open(spec(position=5,length=3)))
        val got=ByteArray(3);assertEquals(3,source.read(got,0,3));assertArrayEquals(byteArrayOf(5,6,7),got)
        assertEquals(-1,source.read(ByteArray(1),0,1));assertEquals(listOf(3),actual.sizes)
        source.close();source.open(spec(position=10,length=2))
        val reopened=ByteArray(2);assertEquals(2,source.read(reopened,0,2));assertArrayEquals(byteArrayOf(10,11),reopened)
        source.close()
    }
    @Test fun cachedRamBytesRemainBoundedButFailedRefillStaysFailedUntilClose() {
        val actual=Actual();val source=SavedReadAheadSource(actual,4);source.open(spec())
        assertEquals(1,source.read(ByteArray(1),0,1));actual.fail=true
        assertEquals(3,source.read(ByteArray(10),0,10));assertEquals(1,actual.reads)
        assertThrows(IOException::class.java) { source.read(ByteArray(1),0,1) };assertEquals(2,actual.reads)
        actual.fail=false;assertThrows(IOException::class.java) { source.read(ByteArray(1),0,1) }
        assertEquals(2,actual.reads);assertNull(source.uri);assertTrue(source.responseHeaders.isEmpty())
        source.close();source.open(spec(position=8));val got=ByteArray(1)
        assertEquals(1,source.read(got,0,1));assertEquals(8,got[0].toInt());source.close()
    }
    @Test fun zeroLengthAndTemporaryZeroReadDoNotInventBytesOrCallTheDelegateUnnecessarily() {
        val actual=Actual().apply { zeroOnce=true };val source=SavedReadAheadSource(actual,4);source.open(spec())
        assertEquals(0,source.read(ByteArray(0),0,0));assertEquals(0,actual.reads)
        assertEquals(0,source.read(ByteArray(1),0,1));assertEquals(1,actual.reads)
        assertEquals(1,source.read(ByteArray(1),0,1));assertEquals(2,actual.reads);source.close()
    }
    @Test fun failedOpenStillClosesAndUnknownCloseCannotReopenOrReleaseAgain() {
        val actual=Actual().apply { failedOpen=true };val source=SavedReadAheadSource(actual,4)
        assertThrows(IOException::class.java) { source.open(spec()) }
        assertThrows(IOException::class.java) { source.read(ByteArray(1),0,1) };source.close()
        assertEquals(1,actual.closes);actual.failedOpen=false;source.open(spec());actual.failedClose=true
        assertThrows(IOException::class.java) { source.close() }
        assertThrows(IllegalStateException::class.java) { source.open(spec()) }
        assertThrows(IOException::class.java) { source.close() };assertEquals(2,actual.closes)
    }
    @Test fun invalidBoundsAndReentrantCloseCannotConsumeOrReplaceAnActiveRead() {
        val actual=Actual();val source=SavedReadAheadSource(actual,4);source.open(spec())
        assertThrows(IllegalArgumentException::class.java) { source.read(ByteArray(1),1,1) }
        assertEquals(0,actual.reads)
        actual.onRead={ assertThrows(IllegalStateException::class.java) { source.close() } }
        assertEquals(1,source.read(ByteArray(1),0,1));assertEquals(0,actual.closes)
        source.close();assertEquals(1,actual.closes)
        assertThrows(IllegalArgumentException::class.java) { SavedReadAheadSource(actual,0) }
        assertThrows(IllegalArgumentException::class.java) { SavedReadAheadSource(actual,SAVED_READ_AHEAD_BYTES+1) }
    }
}
