@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.DefaultContentMetadata
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
class PartitionMoveDestinationTest {
    private val payload=byteArrayOf(2,4,6,8)
    private val spec=DataSpec.Builder().setUri("muon-saved:test").setKey("saved/digest").setLength(4).build()
    private fun <T> worker(work:()->T):T {
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {return try {executor.submit<T> {work()}.get(10,TimeUnit.SECONDS)}
            catch(error:java.util.concurrent.ExecutionException) {throw requireNotNull(error.cause)} }
        finally {executor.shutdownNow(); assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS))}
    }
    private fun destination()=PartitionMoveDestination("phone","(dev=1,ino=2)",PartitionSaveTicket(
        CachePartitionAllocation("saved/digest","00000000-0000-0000-0000-000000000001"),
        "00000000-0000-0000-0000-000000000002"),3,"a".repeat(64),4,"b".repeat(64),"c".repeat(64))
    @Test fun boundedDestinationRoundtripAndMalformedOrNonfreshIdentityRefusal() {
        val expected=destination(); val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use {expected.write(it)}
        assertEquals(expected,DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use {PartitionMoveDestination.read(it)})
        for(wrong in listOf(expected.copy(volume=""),expected.copy(uid=-1),expected.copy(length=0),
            expected.copy(audioDigest="C".repeat(64)),expected.copy(rootIdentity="x".repeat(257)),
            expected.copy(ticket=expected.ticket.copy(allocation=expected.ticket.allocation.copy(key="old"))),
            expected.copy(ticket=expected.ticket.copy(token="bad"))))
            assertThrows(IOException::class.java) {wrong.validate()}
    }
    @Test fun metadataDigestIsOrderedAndPreservesUnknownFieldsWithoutAmbiguousConcatenation() {
        fun metadata(m:ContentMetadataMutations)=DefaultContentMetadata.EMPTY.copyWithMutationsApplied(m)
        val a=metadata(ContentMetadataMutations().set("one",byteArrayOf(1)).set("two",byteArrayOf(2)))
        val b=metadata(ContentMetadataMutations().set("two",byteArrayOf(2)).set("one",byteArrayOf(1)))
        assertEquals(partitionMoveMetadataDigest(a),partitionMoveMetadataDigest(b))
        assertNotEquals(partitionMoveMetadataDigest(a),partitionMoveMetadataDigest(a.copyWithMutationsApplied(
            ContentMetadataMutations().set("unknown",byteArrayOf(3)))))
        assertThrows(IOException::class.java) {partitionMoveMetadataDigest(metadata(ContentMetadataMutations()
            .set("huge",ByteArray(MIGRATION_METADATA_BYTES+1))))}
    }
    @Test fun savedDigestReturnsOnlyAfterActualCloseAndRejectsShortSource() {
        var closes=0; val raw=ByteArrayDataSource(payload)
        val source=DataSource.Factory {object:DataSource by raw {override fun close() {closes++; raw.close()}}}
        val actual=worker {SavedAudioDigest().read(spec,source) {}}
        assertEquals(MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") {"%02x".format(it)},actual)
        assertEquals(1,closes)
        assertThrows(IOException::class.java) {worker {SavedAudioDigest().read(spec,DataSource.Factory {ByteArrayDataSource(byteArrayOf(2))}) {}}}
    }
    @Test fun verifiedDigestBindsBeforeAndAfterMultiBlockReadAndActualClose() {
        val bytes=ByteArray(3*SAVED_DIGEST_READ_BYTES+17) {it.toByte()};var reads=0;var closes=0;var guards=0;var live=0
        val raw=ByteArrayDataSource(bytes)
        val source=DataSource.Factory {object:DataSource by raw {
            override fun read(buffer:ByteArray,offset:Int,length:Int):Int {reads++;return raw.read(buffer,offset,length)}
            override fun close() {closes++;raw.close()}
        }}
        val hash=worker {SavedAudioDigest().readVerified(spec.buildUpon().setLength(bytes.size.toLong()).build(),source,{live++}) {
            if(guards++==0) {assertEquals(0,reads);assertEquals(0,closes)}
            else {assertTrue(reads>=5);assertEquals(1,closes)}
        }}
        assertEquals(2,guards);assertTrue(live>=reads)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)},hash)
    }
    @Test fun digestCancellationAfterShortReadClosesBeforeAnySecondReadOrBindingResult() {
        val bytes=ByteArray(2*SAVED_DIGEST_READ_BYTES+17);val raw=ByteArrayDataSource(bytes)
        var reads=0;var closes=0;var guards=0
        val source=DataSource.Factory {object:DataSource by raw {
            override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                reads++;return raw.read(buffer,offset,minOf(length,4093))
            }
            override fun close() {closes++;raw.close()}
        }}
        assertThrows(java.io.InterruptedIOException::class.java) {worker {
            SavedAudioDigest().readVerified(spec.buildUpon().setLength(bytes.size.toLong()).build(),source,
                {if(reads>0) throw java.io.InterruptedIOException("Cancel after actual short read")},{guards++})
        }}
        assertEquals(1,reads);assertEquals(1,closes);assertEquals(1,guards)
    }

    @Test fun changedBindingDuringActualReadCannotPublishDigestAndKnownReaderClosesOnce() {
        var valid=true;var closes=0;var guards=0;var reads=0
        val raw=ByteArrayDataSource(payload)
        val source=DataSource.Factory {object:DataSource by raw {
            override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                val n=raw.read(buffer,offset,length);if(n>0) {reads++;valid=false};return n
            }
            override fun close() {closes++;raw.close()}
        }}
        assertThrows(IOException::class.java) {worker {SavedAudioDigest().readVerified(spec,source,{}) {
            guards++;if(!valid) throw IOException("Binding changed during actual digest read")
        }}}
        assertEquals(1,reads);assertEquals(1,closes);assertEquals(2,guards)
    }
    @Test fun savedDigestUnknownCloseRetainsActualSourceAndRefusesSecondAttempt() {
        val raw=ByteArrayDataSource(payload); var closes=0
        val actual=object:DataSource by raw {override fun close():Unit {closes++; throw IOException("Unknown digest reader close")}}
        val digest=SavedAudioDigest()
        try {
            assertThrows(PartitionOwnershipUncertain::class.java) {worker {digest.read(spec,DataSource.Factory {actual}) {}}}
            assertSame(actual,SavedAudioDigest::class.java.getDeclaredField("source").apply {isAccessible=true}.get(digest))
            assertEquals(1,closes)
            assertThrows(IOException::class.java) {worker {digest.read(spec,DataSource.Factory {actual}) {}}}
            assertEquals(1,closes)
        } finally {raw.close()}
    }
    @Test fun actualVerifiedDigestStagesIncludeKnownCloseAndBothBindingChecks() {
        val bytes=ByteArray(3*SAVED_DIGEST_READ_BYTES+17) {it.toByte()}
        val raw=ByteArrayDataSource(bytes)
        var now=0L;var reads=0;var closes=0;var guards=0;var checkpoints=0
        val events=mutableListOf<SavedDigestTiming.Event>()
        val digest=SavedAudioDigest {SavedDigestTiming.begin(true,java.util.concurrent.atomic.AtomicInteger(1),{now},events::add)}
        val factory=DataSource.Factory {
            now+=7
            object:DataSource by raw {
                override fun open(spec:DataSpec):Long {now+=5;return raw.open(spec)}
                override fun read(buffer:ByteArray,offset:Int,length:Int):Int {reads++;now+=13;return raw.read(buffer,offset,length)}
                override fun close() {closes++;now+=17;raw.close()}
            }
        }
        val hash=worker {digest.readVerified(spec.buildUpon().setLength(bytes.size.toLong()).build(),factory,
            {checkpoints++;now+=3},{guards++;now+=11})}
        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)},hash)
        assertEquals(2,guards);assertEquals(1,closes);assertEquals(5,reads)
        val e=events.single();assertTrue(e.successful)
        assertEquals(22L,e.bindingMs);assertEquals(checkpoints*3L,e.checkpointMs)
        assertEquals(7L,e.createMs);assertEquals(5L,e.openMs);assertEquals(reads*13L,e.readMs)
        assertEquals(0L,e.hashMs);assertEquals(17L,e.closeMs)
        assertEquals(e.bindingMs+e.checkpointMs+e.createMs+e.openMs+e.readMs+e.hashMs+e.closeMs,e.totalMs)
    }
    @Test fun actualDigestDiagnosticsCannotPublishAfterChangedBindingOrUnknownClose() {
        for(unknownClose in listOf(false,true)) {
            val events=mutableListOf<SavedDigestTiming.Event>()
            var guards=0;var closes=0
            val raw=ByteArrayDataSource(payload);val actual=IOException("Actual failure")
            val child=object:DataSource by raw {
                override fun close() {closes++;raw.close();if(unknownClose) throw actual}
            }
            val digest=SavedAudioDigest {SavedDigestTiming.begin(true,java.util.concurrent.atomic.AtomicInteger(1),{0L}) {
                events+=it;throw IOException("Diagnostic sink failed")
            }}
            val failure=assertThrows(Exception::class.java) {worker {
                digest.readVerified(spec,DataSource.Factory {child},{},{if(++guards==2) throw actual})
            }}
            assertEquals(1,closes);assertFalse(events.single().successful)
            if(unknownClose) {
                assertTrue(failure is PartitionOwnershipUncertain)
                assertSame(child,SavedAudioDigest::class.java.getDeclaredField("source").apply {isAccessible=true}.get(digest))
                assertEquals(1,guards)
            } else {assertSame(actual,failure);assertEquals(2,guards)}
        }
        // A failing first or later diagnostic clock cannot change real bytes or known closure.
        for(failAt in listOf(1,2,7)) {
            var clocks=0;var closes=0;var guards=0;val raw=ByteArrayDataSource(payload)
            val digest=SavedAudioDigest {SavedDigestTiming.begin(true,java.util.concurrent.atomic.AtomicInteger(1),{
                if(++clocks==failAt) throw IOException("Clock failed");0L
            },{error("Invalidated observer must not emit")})}
            val factory=DataSource.Factory {object:DataSource by raw {override fun close() {closes++;raw.close()}}}
            val actual=worker {digest.readVerified(spec,factory,{},{guards++})}
            assertEquals(MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") {"%02x".format(it)},actual)
            assertEquals(1,closes);assertEquals(2,guards)
        }
    }

    @Test fun realFileDigestUsesBoundedBlocksAndCancellationClosesBeforeAnotherRead() {
        val directory=java.nio.file.Files.createTempDirectory("digest-block").toFile()
        val file=java.io.File(directory,"disposable-audio")
        val bytes=ByteArray(3*SAVED_DIGEST_READ_BYTES+17) {(it*31).toByte()}
        file.writeBytes(bytes)
        fun source(requests:MutableList<Int>,closes:IntArray)=DataSource.Factory {
            val raw=androidx.media3.datasource.FileDataSource()
            object:DataSource by raw {
                override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                    requests+=length;return raw.read(buffer,offset,length)
                }
                override fun close() {closes[0]++;raw.close()}
            }
        }
        try {
            val full=spec.buildUpon().setUri(android.net.Uri.fromFile(file)).setLength(bytes.size.toLong()).build()
            val requests=mutableListOf<Int>();val closes=IntArray(1)
            val actual=worker {SavedAudioDigest().readVerified(full,source(requests,closes),{},{})}
            assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)},actual)
            assertEquals(listOf(SAVED_DIGEST_READ_BYTES,SAVED_DIGEST_READ_BYTES,SAVED_DIGEST_READ_BYTES,17,1),requests)
            assertEquals(1,closes[0])
            val cancelled=mutableListOf<Int>();val cancelledCloses=IntArray(1)
            val failure=java.io.InterruptedIOException("Cancel after first real block")
            assertSame(failure,assertThrows(java.io.InterruptedIOException::class.java) {worker {
                SavedAudioDigest().readVerified(full,source(cancelled,cancelledCloses),{
                    if(cancelled.isNotEmpty()) throw failure
                },{})
            }})
            assertEquals(listOf(SAVED_DIGEST_READ_BYTES),cancelled);assertEquals(1,cancelledCloses[0])
        } finally {assertTrue(file.delete());assertTrue(directory.delete())}
    }

}
