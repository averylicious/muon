package dev.avery.muon.baselineprofile

import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** "Muon Benchmark": the app's benchmark build, in a package of its own. */
private const val TARGET = "dev.avery.muon.benchmark"

/** Android 17's local network permission, as the app names it in LocalNetwork.kt. */
private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

/** How long a page, list or button has to appear, or to go once left. */
private const val WAIT = 10_000L

/** How long a song has to start: it streams from Tauon. */
private const val PLAYBACK = 30_000L

/**
 * The journeys Muon's profile is recorded from: a cold start, scrolling the library, opening an album
 * and an artist, starting a song and opening Now Playing and its Queue. Each is what a user does most,
 * so the code behind it is compiled ahead of time instead of running interpreted on first use.
 *
 * Every required step waits for its destination and fails if it does not appear. A failed
 * recording must not be accepted as a complete journey; older exported files may still exist.
 *
 * Muon Benchmark must already be connected to a running Tauon on the same network (see
 * docs/baseline-profile.md). Media volume should be muted first, since a song plays.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test fun generate() {
        check(!BuildConfig.BENCHMARK_MINIFIED) { "Use recording-mode tools to generate source profile rules" }
        val app = InstrumentationRegistry.getInstrumentation().context.packageManager
            .getApplicationInfo(TARGET, PackageManager.GET_META_DATA)
        check(app.metaData?.getString("dev.avery.muon.benchmark.mode") == "recording") {
            "Install the matching unminified recording target before generating a profile"
        }
        grantLocalNetwork()
        rule.collect(packageName = TARGET, includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait()
            check(device.wait(Until.hasObject(By.pkg(TARGET).text("Songs")), 60_000)) {
                "The library did not open within 60 s: is Muon Benchmark connected, and Tauon reachable?"
            }
            try {
                journeys()
            } catch (failure: Throwable) {
                // Leave the benchmark app quiet, but report what actually went wrong.
                runCatching { pause() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }
    }

    private fun journeys() {
        // Songs, Albums and Artists: each list flung down and back, and one album and one artist opened.
        scroll()
        visit("Albums", "Back to albums", "The album page")
        visit("Artists", "Back to artists", "The artist page")
        // A song started from Songs, then Now Playing opened from the mini player, and its Queue.
        tap("Songs"); openFirst("song")
        need(By.desc("Pause"), "Pause, after starting a song,", PLAYBACK)
        // Its title, clear of the controls at the end.
        val mini = need(By.descStartsWith("Mini-player"), "The mini-player").visibleBounds
        device.click(mini.left + mini.width() / 3, mini.centerY())
        need(By.desc("Collapse the player"), "Now Playing")
        // An icon beside Collapse on a phone on its side, a text button under the controls upright.
        (device.findObject(By.pkg(TARGET).desc("Queue")) ?: need(By.text("Queue"), "Now Playing's Queue button")).click()
        need(By.desc("Back to Now Playing"), "The Queue")
        device.pressBack()
        gone(By.desc("Back to Now Playing"), "The Queue")
        need(By.desc("Collapse the player"), "Now Playing, back from the Queue,")
        check(pause()) { "Now Playing had no Pause button" }
        gone(By.desc("Pause"), "Pause, after pausing,")
        device.pressBack()
        gone(By.desc("Collapse the player"), "Now Playing")
    }

    /** Android 17 asks for local network access before Muon can reach Tauon; earlier versions have no such permission. */
    private fun grantLocalNetwork() {
        if (Build.VERSION.SDK_INT < 37) return
        device.executeShellCommand("pm grant --user current $TARGET $LOCAL_NETWORK")
        // Shell stderr is not returned here. Check this instrumentation user's actual grant rather
        // than matching a dumpsys entry that could belong to another installed user.
        val packages = InstrumentationRegistry.getInstrumentation().context.packageManager
        check(packages.checkPermission(LOCAL_NETWORK, TARGET) == PackageManager.PERMISSION_GRANTED) {
            "$TARGET was not granted $LOCAL_NETWORK: is Muon Benchmark installed?"
        }
    }

    /** Switches to [view], flings it, opens its first entry, waits for the page named by [back], and returns. */
    private fun visit(view: String, back: String, page: String) {
        tap(view); scroll(); openFirst(view)
        need(By.desc(back), page)
        device.pressBack()
        gone(By.desc(back), page)
        need(By.text(view), "The library, back from ${page.lowercase()},")
    }

    /**
     * Opens the topmost whole row or tile in the main list, skipping its order button. Which one is
     * found by position, not by a name, so each caller checks the page it lands on.
     */
    private fun openFirst(what: String) {
        val minimumHeight = 40 * InstrumentationRegistry.getInstrumentation().context.resources.displayMetrics.density
        val rows = list().findObjects(By.clickable(true).enabled(true))
            .filter { it.visibleBounds.height() >= minimumHeight }
            .filterNot { it.contentDescription?.startsWith("Order:") == true }
        checkNotNull(rows.minByOrNull { it.visibleBounds.top }) { "No $what to open in the list" }.click()
        device.waitForIdle()
    }

    private fun tap(text: String) {
        need(By.text(text), "The $text button").click()
        device.waitForIdle()
    }

    private fun scroll() {
        val list = list()
        list.setGestureMargin(device.displayWidth / 5)
        repeat(2) { list.fling(Direction.DOWN) }
        list.fling(Direction.UP)
        device.waitForIdle()
    }

    /** The view's own list or grid: the largest scrollable, rather than the view chips' row. */
    private fun list(): UiObject2 = checkNotNull(
        device.wait(Until.findObjects(By.pkg(TARGET).scrollable(true)), WAIT)?.maxByOrNull { it.visibleBounds.area() }) {
        "No list appeared within ${WAIT / 1000} s"
    }

    /** Pauses with the largest Pause on screen, Now Playing's own when it is open; false if there is none. */
    private fun pause(): Boolean {
        val button = device.findObjects(By.pkg(TARGET).desc("Pause")).maxByOrNull { it.visibleBounds.area() }
            ?: return false
        button.click()
        device.waitForIdle()
        return true
    }

    private fun need(selector: BySelector, what: String, timeout: Long = WAIT): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector.pkg(TARGET)), timeout)) { "$what did not appear within ${timeout / 1000} s" }

    private fun gone(selector: BySelector, what: String) =
        check(device.wait(Until.gone(selector.pkg(TARGET)), WAIT)) { "$what was still showing after ${WAIT / 1000} s" }

    private fun Rect.area() = width() * height()
}
