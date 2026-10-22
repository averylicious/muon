package dev.avery.muon

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Duration

/**
 * Real production composables -> Compose's Android AccessibilityNodeProvider. A semantics-property
 * assertion alone misses labels lost while exporting merged nodes to native accessibility.
 * These fixtures do not start playback, contact Tauon or use a real AudioManager volume change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w480dp-h1000dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SliderNativeAccessibilityTest {
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var host: ComposeView
    private val exported = mutableListOf<AccessibilityNodeInfo>()

    @Before fun enableAccessibility() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(AccessibilityManager::class.java)
        shadowOf(manager).apply {
            setEnabledAccessibilityServiceList(listOf(AccessibilityServiceInfo()))
            setEnabled(true)
            setTouchExplorationEnabled(true)
        }
    }

    @After @Suppress("DEPRECATION") fun dispose() {
        if (::host.isInitialized) host.disposeComposition()
        if (::activity.isInitialized) activity.pause().stop().destroy()
        exported.forEach { it.recycle() }
    }

    @Test fun playbackPurposeRangeAndVisibilitySurviveBufferingWithPlaybackDetached() {
        val item = MediaItem.Builder().setMediaId("accessibility-fixture")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Fixture song")
                .setArtist("Fixture artist").build()).build()
        val buffering = mutableStateOf(false)
        show {
            NowPlayingOverlay(PlaybackUi(item = item, duration = 120_000L, seekable = true,
                buffering = buffering.value),
                position = { 30_000L }, revision = { 0 }, player = null, dismiss = null,
                collapse = {}, queue = {}, lyrics = {})
        }
        val seek = nativeSlider("Playback position")
        assertTrue(seek.info.isVisibleToUser)
        assertEquals("0:30 of 2:00", seek.info.stateDescription?.toString())
        assertRange(seek.info, 0f, 120_000f, 30_000f)
        assertFalse(seek.info.isEnabled)
        assertFalse(seek.info.actionList.any { it.id == SET_PROGRESS })

        buffering.value = true
        // Beyond the delayed buffering indicator and fade: visual opacity must not remove the
        // native SeekBar from accessibility. Query fresh provider nodes after recomposition.
        advanceFrames(20)
        val waiting = nativeSlider("Playback position")
        assertTrue(waiting.info.isVisibleToUser)
        assertEquals("0:30 of 2:00", waiting.info.stateDescription?.toString())
        assertRange(waiting.info, 0f, 120_000f, 30_000f)
        assertFalse(waiting.info.isEnabled)
        assertFalse(waiting.info.actionList.any { it.id == SET_PROGRESS })
    }

    @Test fun mediaVolumePurposeAndProgressActionBelongToTheSameNativeSeekBar() {
        var changed: Int? = null
        show { MediaVolumeSlider(MediaVolumeState(2, 12, 7, false), { changed = it }) }
        val volume = nativeSlider("Media volume")
        assertEquals("50 percent", volume.info.stateDescription?.toString())
        assertRange(volume.info, 2f, 12f, 7f)
        assertTrue(volume.info.isEnabled)
        assertTrue(volume.info.actionList.any { it.id == SET_PROGRESS })
        assertTrue(volume.info.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD })
        assertTrue(volume.info.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD })
        val arguments = Bundle().apply {
            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 9f)
        }
        assertTrue(volume.provider.performAction(volume.id, SET_PROGRESS, arguments))
        assertEquals(9, changed)
    }

    private fun show(content: @Composable () -> Unit) {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        host = ComposeView(activity.get())
        activity.get().setContentView(host)
        host.setContent { MaterialTheme { content() } }
        host.createComposition()
        advanceFrames(5)
    }

    private fun advanceFrames(count: Int) {
        // Bounded frames, not run-to-end: Now Playing deliberately polls volume every 500 ms.
        repeat(count) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            host.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, 480, 1000)
        }
    }

    private data class NativeNode(val id: Int, val info: AccessibilityNodeInfo,
        val provider: AccessibilityNodeProvider)

    private fun nativeSlider(label: String): NativeNode {
        val view = requireNotNull(findProvider(host)) { "Compose did not attach a native accessibility provider" }
        val provider = requireNotNull(view.accessibilityNodeProvider)
        val pending = ArrayDeque<Int>().apply { add(View.NO_ID) }
        val seen = mutableSetOf<Int>()
        val nodes = mutableListOf<NativeNode>()
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (!seen.add(id)) continue
            val info = requireNotNull(provider.createAccessibilityNodeInfo(id)) {
                "Native provider omitted exported virtual node $id"
            }
            exported += info
            nodes += NativeNode(id, info, provider)
            repeat(info.childCount) { index ->
                // Native child IDs, not Compose's test/merged semantics tree. Android stores the
                // virtual descendant ID in the high 32 bits of each AccessibilityNodeInfo child ID.
                val child: Long = ReflectionHelpers.callInstanceMethod(info, "getChildId",
                    ClassParameter.from(Int::class.javaPrimitiveType!!, index))
                pending.add((child shr 32).toInt())
            }
        }
        val sliders = nodes.filter { it.info.className?.toString() == "android.widget.SeekBar" }
        val matches = sliders.filter { it.info.text?.toString() == label }
        assertEquals("Expected one native SeekBar named $label; exported: " +
            sliders.map { "text=${it.info.text}, description=${it.info.contentDescription}" }, 1, matches.size)
        return matches.single()
    }

    private fun findProvider(view: View): View? {
        if (view.accessibilityNodeProvider != null) return view
        if (view is ViewGroup) repeat(view.childCount) { index ->
            findProvider(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun assertRange(info: AccessibilityNodeInfo, minimum: Float, maximum: Float, current: Float) {
        val range = requireNotNull(info.rangeInfo) { "Native SeekBar lost its range" }
        assertEquals(minimum, range.min, 0f)
        assertEquals(maximum, range.max, 0f)
        assertEquals(current, range.current, 0f)
    }

    private companion object {
        val SET_PROGRESS: Int = AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id
    }
}
