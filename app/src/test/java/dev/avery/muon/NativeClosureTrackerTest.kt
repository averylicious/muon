@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
class NativeClosureTrackerTest {
    @Test fun swallowedReentrantCloseStillRemainsUnknownWithoutASecondNativeClose() {
        val tracker=ClosureTracker(); var closes=0; lateinit var tracked:DataSource
        val raw=ByteArrayDataSource(byteArrayOf(1))
        tracked=tracker.source(DataSource.Factory { object:DataSource by raw {
            override fun close() { closes++; try { tracked.close() } catch(_:IOException) {}; raw.close() }
        } })
        tracked.open(DataSpec(Uri.parse("http://127.0.0.1:1/fixture")))
        assertThrows(IOException::class.java) { tracked.close() }; assertFalse(tracker.clean())
        assertThrows(IOException::class.java) { tracked.close() }; assertEquals(1,closes)
    }
    @Test fun failedConstructionRetainsItsExactFactoryInABoundedSlot() {
        val tracker=ClosureTracker(); val factory=DataSource.Factory { throw IOException("Unreturned owner") }
        assertThrows(IOException::class.java) { tracker.source(factory) }; assertFalse(tracker.clean())
        val controls=ClosureTracker::class.java.getDeclaredField("controls").apply { isAccessible=true }.get(tracker) as Array<*>
        val owner=controls.filterNotNull().single()
        assertSame(factory,owner.javaClass.getDeclaredField("owner").apply { isAccessible=true }.get(owner))
    }
}
