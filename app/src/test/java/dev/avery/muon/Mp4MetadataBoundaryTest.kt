@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.source.BundledExtractorsAdapter
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ForwardingExtractorInput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Raw pinned-library behavior, not desired guard behavior or production clearance for #720. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
class Mp4MetadataBoundaryTest {
    @Test fun validM4aUsesDefaultFactoryAndLeafAllocatesBeforeMissingPayloadIsRead() {
        val output=Capture()
        val control=fixture()
        val adapter=BundledExtractorsAdapter(DefaultExtractorsFactory())
        val uri=Uri.parse("http://fixture.invalid/control.m4a")
        try {
            adapter.init(reader(control,0),uri,emptyMap(),0,control.size.toLong(),output)
            val holder=PositionHolder(); var done=false; var reads=0
            while(!done) {
                check(reads++<1000) {"Fixture adapter did not finish"}
                when(adapter.read(holder)) {
                    Extractor.RESULT_CONTINUE -> Unit
                    Extractor.RESULT_SEEK -> adapter.init(reader(control,holder.position),uri,emptyMap(),holder.position,control.size.toLong(),output)
                    else -> done=true
                }
            }
            assertEquals(MimeTypes.AUDIO_AAC,output.format?.sampleMimeType)
            assertTrue(requireNotNull(output.map).isSeekable)
        } finally {adapter.release()}

        // Only 8 bytes exist, and length is known; observe the real parser's modest 4KiB array
        // before its body read fails. Never substitute a huge positive allocation declaration.
        val declared=4096
        val header=ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putInt(declared).put("udta".toByteArray(Charsets.US_ASCII)).array()
        var observed=0
        val input=object:ForwardingExtractorInput(DefaultExtractorInput(reader(header,0),0,header.size.toLong())) {
            override fun readFully(target:ByteArray,offset:Int,length:Int) {
                if(offset==8 && length==declared-8) observed=target.size
                super.readFully(target,offset,length)
            }
        }
        val extractor=Mp4Extractor()
        try {
            extractor.init(Capture())
            assertThrows(EOFException::class.java) {extractor.read(input,PositionHolder())}
            assertEquals(declared,observed)
            assertEquals(header.size.toLong(),input.position)
        } finally {extractor.release()}
    }

    @Test fun fixedSizeSampleCountBuildsSeekTableBeyondActualMediaWithoutGrowingFile() {
        val bytes=fixture()
        val stsz=box(bytes,"stsz")
        val stts=box(bytes,"stts")
        val stsc=box(bytes,"stsc")
        val stco=box(bytes,"stco")
        assertEquals(20,int(bytes,stsz)) // Fixed-size stsz carries no per-sample entries.
        val fixed=int(bytes,stsz+12); assertTrue(fixed>0)
        val oldCount=int(bytes,stsz+16); assertTrue(oldCount in 1..10)
        val offset=int(bytes,stco+16).toLong()
        val count=4096 // Roughly128KiB of tables, not a heap-pressure test.
        val changed=bytes.copyOf()
        put(changed,stsz+16,count)
        put(changed,stts+12,1); put(changed,stts+16,count)
        put(changed,stsc+20,count)
        assertEquals(bytes.size,changed.size)
        val extractor=Mp4Extractor(); val output=Capture(); val holder=PositionHolder()
        var input=DefaultExtractorInput(reader(changed,0),0,changed.size.toLong())
        try {
            extractor.init(output)
            var reads=0
            while(output.map==null) {
                check(reads++<1000) {"Fixture map was not published"}
                when(extractor.read(input,holder)) {
                    Extractor.RESULT_CONTINUE -> Unit
                    Extractor.RESULT_SEEK -> input=DefaultExtractorInput(reader(changed,holder.position),holder.position,changed.size.toLong())
                    else -> fail("EOF before sample table publication")
                }
            }
            assertEquals(MimeTypes.AUDIO_AAC,output.format?.sampleMimeType)
            val last=requireNotNull(output.map).getSeekPoints(3_600_000_000L).first.position
            assertEquals(offset+(count-1L)*fixed,last)
            assertTrue("Metadata tables name samples beyond the whole750-byte file",last>changed.size)
            // The declared table was built/used, not proof the named audio is present or decodable.
        } finally {extractor.release()}
    }

    private class Capture:ExtractorOutput {
        var format:Format?=null
        var map:SeekMap?=null
        override fun track(id:Int,type:Int):TrackOutput=object:TrackOutput by DiscardingTrackOutput() {
            override fun format(format:Format) {this@Capture.format=format}
        }
        override fun endTracks() {}
        override fun seekMap(seekMap:SeekMap) {map=seekMap}
    }
    private fun fixture()=requireNotNull(javaClass.getResourceAsStream("/mp4-admission/control.m4a")).use {it.readBytes()}
    private fun reader(bytes:ByteArray,start:Long):DataReader {
        var at=start.toInt()
        return DataReader {target,offset,length ->
            if(length==0) 0
            else if(at>=bytes.size) C.RESULT_END_OF_INPUT
            else minOf(length,bytes.size-at).also {count ->bytes.copyInto(target,offset,at,at+count);at+=count}
        }
    }
    private fun int(bytes:ByteArray,at:Int)=ByteBuffer.wrap(bytes,at,4).order(ByteOrder.BIG_ENDIAN).int
    private fun put(bytes:ByteArray,at:Int,value:Int) {ByteBuffer.wrap(bytes,at,4).order(ByteOrder.BIG_ENDIAN).putInt(value)}
    private fun box(bytes:ByteArray,type:String):Int {
        val parents=setOf("moov","trak","mdia","minf","stbl")
        fun find(start:Int,end:Int):Int? {
            var at=start
            while(at<end) {
                val size=int(bytes,at);check(size>=8 && size<=end-at)
                val name=String(bytes,at+4,4,Charsets.US_ASCII)
                if(name==type) return at
                if(name in parents) find(at+8,at+size)?.let {return it}
                at+=size
            }
            return null
        }
        return requireNotNull(find(0,bytes.size)) {type}
    }
}
