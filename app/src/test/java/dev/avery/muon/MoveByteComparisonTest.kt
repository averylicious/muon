@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
class MoveByteComparisonTest {
    @get:Rule val folder=TemporaryFolder()
    private val payload=ByteArray(100_000) { (it%127).toByte() }
    private fun spec()=DataSpec.Builder().setUri("muon-saved:compare").setLength(payload.size.toLong()).build()
    private fun files(bytes:ByteArray,unknown:Boolean=false,actual:MutableList<DataSource> = mutableListOf(),closed:()->Unit={}):DataSource.Factory {
        val file=folder.newFile().apply { writeBytes(bytes) }
        return DataSource.Factory {
            val child=FileDataSource.Factory().createDataSource().also(actual::add)
            object:DataSource by child {
                override fun open(spec:DataSpec)=child.open(spec.buildUpon().setUri(Uri.fromFile(file)).build())
                override fun close() { closed(); if(unknown) throw IOException("Injected real-file unknown close"); child.close() }
            }
        }
    }
    @Test fun actualEqualAndDifferentFilesCloseBeforeSuccessAndDrainKnownSlots() {
        val owner=MoveByteComparison(); var closes=0
        assertTrue(owner.compare(spec(),files(payload,closed={closes++}),files(payload,closed={closes++}),{true},{fail("Known close flagged uncertain")}))
        assertEquals(2,closes); assertEquals(0,owner.active)
        assertFalse(owner.compare(spec(),files(payload),files(payload.copyOf().also { it[99]=(-1).toByte() }),{true},{}))
        assertEquals(0,owner.active)
    }
    @Test fun truncatedFileOrLostAvailabilityDoesNotMatchAndStillClosesBoth() {
        val owner=MoveByteComparison(); var closes=0
        assertFalse(owner.compare(spec(),files(payload,closed={closes++}),files(payload.copyOf(3),closed={closes++}),{true},{}))
        assertEquals(2,closes); assertEquals(0,owner.active)
        var checks=0
        assertFalse(owner.compare(spec(),files(payload),files(payload),{++checks<3},{})); assertEquals(0,owner.active)
    }
    @Test fun targetFactoryFailureClosesAlreadyCreatedSourceAndReleasesKnownOwnership() {
        val owner=MoveByteComparison(); var closes=0
        assertThrows(IOException::class.java) {
            owner.compare(spec(),files(payload,closed={closes++}),DataSource.Factory { throw IOException("Creation refused") },{true},{})
        }
        assertEquals(1,closes); assertEquals(0,owner.active)
    }
    @Test fun bothUnknownRealFileClosesRetainOneSlotAndBlockRetryWithoutReclosingEither() {
        val owner=MoveByteComparison(); val actual=mutableListOf<DataSource>(); var closes=0; var notices=0
        try {
            val failure=assertThrows(IOException::class.java) {
                owner.compare(spec(),files(payload,true,actual,{closes++}),files(payload,true,actual,{closes++}),{true},{notices++})
            }
            assertEquals(1,failure.suppressed.size); assertEquals(2,closes); assertEquals(1,notices); assertEquals(1,owner.active)
            var created=0
            assertThrows(IOException::class.java) {
                owner.compare(spec(),DataSource.Factory { created++; throw AssertionError("Unknown reader replaced") },files(payload),{true},{})
            }
            assertEquals(0,created); assertEquals(2,closes)
        } finally { actual.forEach { it.close() } } // Disposable actual handles only, never wrapper retry.
    }
    @Test fun fullBudgetRefusesNestedComparisonBeforeAnyNewReaderCreation() {
        val owner=MoveByteComparison(1); var nested=false
        assertTrue(owner.compare(spec(),files(payload),files(payload),{
            if(!nested) {
                nested=true; var created=0
                val factory=DataSource.Factory { created++; throw AssertionError("Budget bypass") }
                assertThrows(IOException::class.java) { owner.compare(spec(),factory,factory,{true},{}) }
                assertEquals(0,created)
            }
            true
        },{})); assertEquals(0,owner.active)
    }
}
