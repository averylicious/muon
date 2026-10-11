package dev.avery.muon

import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

/** Real OkHttp response/source and actual disposable output files; not a phone/network test. */
class SavedCoverLifetimeTest {
    private class Worker:Executor {
        val tasks=ArrayDeque<Runnable>()
        override fun execute(command:Runnable) { tasks.add(command) }
        fun drain()=tasks.removeFirst().run()
    }
    private fun client(source:BufferedSource)=OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(object:ResponseBody() {
                override fun contentType():MediaType?=null
                override fun contentLength()=-1L
                override fun source()=source
            }).build()
    }.build()
    private fun source(bytes:ByteArray,closes:AtomicInteger,fail:Boolean=false)=
        object:ForwardingSource(Buffer().write(bytes)) {
            override fun close() { closes.incrementAndGet(); if(fail) throw IOException("Controlled unknown source close"); super.close() }
        }.buffer()
    private val url="http://127.0.0.1:1/fixture-no-network"
    private fun entry(dir:File,id:String)=File(dir,"entry-"+java.security.MessageDigest.getInstance("SHA-256")
        .digest("muon-saved-entry\u0000$id".toByteArray()).joinToString("") { "%02x".format(it) })

    @Test fun actualPublicationAndCancellationCleanupHoldOnePermitThroughBothCloses() {
        val dir=Files.createTempDirectory("cover-known").toFile(); val gate=SavedStorageBarrier(1)
        val worker=Worker(); val closes=AtomicInteger(); var outputs=0
        lateinit var work:DownloadArtworkWork
        val art=DownloadArt(dir,client(source(byteArrayOf(1,2,3),closes)),{ file ->
            object:FilterOutputStream(FileOutputStream(file)) {
                override fun close() {
                    outputs++; assertEquals(1,gate.active)
                    assertThrows(IOException::class.java) { gate.exclusive() }
                    work.remove("active") // Fallback cannot get a second permit, final cleanup uses this one.
                    super.close()
                }
            }
        })
        work=DownloadArtworkWork(worker,art::fetchEntry,art::removeEntry,maxJobs=1,barrier=gate)
        try {
            assertTrue(work.fetch("active",url)); worker.drain()
            assertEquals(1,closes.get()); assertEquals(1,outputs)
            assertFalse(art.hasEntry("active")); assertTrue(gate.quiescent); assertFalse(work.isUncertain)
            gate.exclusive().close()
        } finally { dir.deleteRecursively() }
    }
    @Test fun healthyActualCoverPublishesOnlyAfterKnownClosesAndRemainsReadable() {
        val dir=Files.createTempDirectory("cover-published").toFile(); val gate=SavedStorageBarrier()
        val worker=Worker(); val closes=AtomicInteger()
        val art=DownloadArt(dir,client(source(byteArrayOf(4,3,2),closes)))
        val work=DownloadArtworkWork(worker,art::fetchEntry,art::removeEntry,barrier=gate)
        try {
            assertTrue(work.fetch("active",url)); worker.drain()
            assertEquals(1,closes.get()); assertArrayEquals(byteArrayOf(4,3,2),art.forEntry("active"))
            assertFalse(File(dir,entry(dir,"active").name+".part").exists())
            assertTrue(gate.quiescent); assertFalse(work.isUncertain)
        } finally { dir.deleteRecursively() }
    }
    @Test fun overCapActualResponseClosesWithoutCreatingOutputAndReleasesTheGate() {
        val dir=Files.createTempDirectory("cover-over-cap").toFile(); val gate=SavedStorageBarrier()
        val worker=Worker(); val closes=AtomicInteger(); var outputs=0
        val art=DownloadArt(dir,client(source(ByteArray(4*1024*1024+1),closes)),{ outputs++; FileOutputStream(it) })
        val work=DownloadArtworkWork(worker,art::fetchEntry,art::removeEntry,barrier=gate)
        try {
            assertTrue(work.fetch("active",url)); worker.drain()
            assertEquals(1,closes.get()); assertEquals(0,outputs); assertFalse(art.hasEntry("active"))
            assertTrue(gate.quiescent); assertFalse(work.isUncertain)
        } finally { dir.deleteRecursively() }
    }
    @Test fun quietResponseCloseIsNotEvidenceAndActualUnknownSourceStopsCleanupAndRetry() {
        val dir=Files.createTempDirectory("cover-source-unknown").toFile(); val gate=SavedStorageBarrier()
        val oldCloses=AtomicInteger()
        client(source(byteArrayOf(1),oldCloses,true)).newCall(okhttp3.Request.Builder().url(url).build()).execute().close()
        assertEquals(1,oldCloses.get()) // Pinned ResponseBody hides this checked close exception.
        val closes=AtomicInteger(); val worker=Worker()
        val art=DownloadArt(dir,client(source(byteArrayOf(1,2,3),closes,true)))
        val work=DownloadArtworkWork(worker,art::fetchEntry,art::removeEntry,barrier=gate)
        val other=entry(dir,"other").apply { writeBytes(byteArrayOf(9)) }
        try {
            assertTrue(work.fetch("active",url)); work.remove("other"); worker.drain()
            assertTrue(work.isUncertain); assertEquals(1,gate.active)
            assertThrows(IOException::class.java) { gate.exclusive() }
            assertEquals(1,closes.get()); assertFalse(art.hasEntry("active"))
            assertArrayEquals(byteArrayOf(1,2,3),File(dir,entry(dir,"active").name+".part").readBytes())
            assertArrayEquals(byteArrayOf(9),other.readBytes())
            work.remove("other"); assertFalse(work.fetch("retry",url)); assertTrue(worker.tasks.isEmpty())
            assertEquals(1,closes.get())
        } finally { dir.deleteRecursively() }
    }
    @Test fun unknownActualOutputKeepsExactHandleAndDoesNotRetryCloseOrPublish() {
        val dir=Files.createTempDirectory("cover-file-unknown").toFile(); val closes=AtomicInteger()
        var raw:FileOutputStream?=null; var wrapper:FilterOutputStream?=null; var attempts=0
        val art=DownloadArt(dir,client(source(byteArrayOf(3,4,5),closes)),{ file ->
            raw=FileOutputStream(file)
            object:FilterOutputStream(raw!!) {
                override fun close() { attempts++; throw IOException("Controlled unknown real output close") }
            }.also { wrapper=it }
        })
        try {
            val failure=assertThrows(SavedCoverCloseUncertain::class.java) { art.fetchEntry("active",url) }
            assertSame(wrapper,failure.owner.output)
            assertEquals(1,attempts); assertEquals(1,closes.get())
            assertThrows(SavedCoverCloseUncertain::class.java) { failure.owner.finish() }
            assertEquals(1,attempts); assertEquals(1,closes.get()); assertFalse(art.hasEntry("active"))
            assertArrayEquals(byteArrayOf(3,4,5),File(dir,entry(dir,"active").name+".part").readBytes())
        } finally { raw?.close(); dir.deleteRecursively() } // Fixture-only actual descriptor cleanup.
    }
    @Test fun exclusiveGateRefusesFetchAndCallerCleanupBeforeAnyMutationThenCanRetry() {
        val gate=SavedStorageBarrier(); val worker=Worker(); var mutations=0
        val work=DownloadArtworkWork(worker,{_,_->mutations++},{mutations++},maxJobs=1,barrier=gate)
        val exclusive=gate.exclusive()
        assertTrue(work.fetch("active",url)); work.remove("fallback"); worker.drain()
        assertEquals(0,mutations); exclusive.close()
        assertTrue(work.fetch("active",url)); worker.drain()
        assertEquals(1,mutations); assertTrue(gate.quiescent)
    }
    @Test fun unconfirmedExecutorAcceptanceCannotRunLaterOrRetryDeletion() {
        val gate=SavedStorageBarrier(); val tasks=ArrayDeque<Runnable>(); var mutations=0
        val executor=Executor { command -> tasks.add(command); throw IllegalStateException("Accepted, result unknown") }
        val work=DownloadArtworkWork(executor,{_,_->mutations++},{mutations++},barrier=gate)
        assertFalse(work.fetch("active",url)); assertTrue(work.isUncertain)
        work.remove("active"); assertFalse(work.fetch("retry",url))
        tasks.removeFirst().run(); assertEquals(0,mutations)
        assertThrows(IOException::class.java) { gate.exclusive() }
    }
    @Test fun actualDisplayReaderHoldsPermitUntilItsRealFileDescriptorCloses() {
        val dir=Files.createTempDirectory("cover-display-known").toFile(); val gate=SavedStorageBarrier(1)
        var closes=0
        val art=DownloadArt(dir,barrier=gate,inputs={ file ->
            object:java.io.FilterInputStream(java.io.FileInputStream(file)) {
                override fun read(bytes:ByteArray,offset:Int,length:Int):Int {
                    assertEquals(1,gate.active); assertThrows(IOException::class.java) { gate.exclusive() }
                    return super.read(bytes,offset,length)
                }
                override fun close() { closes++; assertEquals(1,gate.active); super.close() }
            }
        })
        try {
            entry(dir,"active").writeBytes(byteArrayOf(6,7,8))
            assertArrayEquals(byteArrayOf(6,7,8),art.forEntry("active"))
            assertEquals(1,closes); assertTrue(gate.quiescent)
            val exclusive=gate.exclusive(); assertNull(art.forEntry("active")); exclusive.close()
            assertEquals(1,closes) // Refusal did not open another descriptor.
        } finally { dir.deleteRecursively() }
    }
    @Test fun unknownActualDisplayCloseRetainsDescriptorAndRefusesRemovalWithoutRetry() {
        val dir=Files.createTempDirectory("cover-display-unknown").toFile(); val gate=SavedStorageBarrier()
        var raw:java.io.FileInputStream?=null; var attempts=0
        val art=DownloadArt(dir,barrier=gate,inputs={ file ->
            raw=java.io.FileInputStream(file)
            object:java.io.FilterInputStream(raw!!) {
                override fun close() { attempts++; throw IOException("Controlled actual display close unknown") }
            }
        })
        val file=entry(dir,"active").apply { writeBytes(byteArrayOf(2,3,4)) }
        try {
            assertNull(art.forEntry("active")); assertEquals(1,attempts); assertEquals(1,gate.active)
            assertNull(art.forEntry("active")); assertEquals(1,attempts)
            assertThrows(IOException::class.java) { art.removeEntry("active") }
            assertThrows(IOException::class.java) { gate.exclusive() }
            assertArrayEquals(byteArrayOf(2,3,4),file.readBytes())
        } finally { raw?.close(); dir.deleteRecursively() } // Only the disposable fixture closes raw FD.
    }
    @Test fun fileSizeCheckCannotAdmitAnUnboundedGrowingDisplaySource() {
        val dir=Files.createTempDirectory("cover-display-cap").toFile(); val gate=SavedStorageBarrier()
        var read=0L; var closes=0
        val art=DownloadArt(dir,barrier=gate,inputs={
            object:java.io.InputStream() {
                override fun read()=1
                override fun read(bytes:ByteArray,offset:Int,length:Int):Int { read+=length; bytes.fill(1,offset,offset+length); return length }
                override fun close() { closes++ }
            }
        })
        try {
            entry(dir,"active").writeBytes(byteArrayOf(1))
            assertNull(art.forEntry("active")); assertTrue(read<=4L*1024*1024+8192)
            assertEquals(1,closes); assertTrue(gate.quiescent)
        } finally { dir.deleteRecursively() }
    }
    @Test fun closeReentryCannotEraseTheExactOutputEvenIfCallbackSuppressesFailure() {
        val owner=SavedCoverIo(); var attempts=0
        val output=object:java.io.OutputStream() {
            override fun write(value:Int) { }
            override fun close() { attempts++; runCatching { owner.finish() } }
        }
        owner.output=output
        val failure=assertThrows(SavedCoverCloseUncertain::class.java) { owner.finish() }
        assertSame(owner,failure.owner); assertSame(output,owner.output)
        assertThrows(SavedCoverCloseUncertain::class.java) { owner.finish() }; assertEquals(1,attempts)
    }
}
