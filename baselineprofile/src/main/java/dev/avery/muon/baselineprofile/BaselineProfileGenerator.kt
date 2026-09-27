package dev.avery.muon.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** "Muon Benchmark": the app's benchmark build, in a package of its own. */
private const val TARGET = "dev.avery.muon.benchmark"

/**
 * The journeys Muon's profile is recorded from: a cold start, scrolling the library, opening an album
 * and an artist, starting a song and opening Now Playing. Each is what a user does most, so the code
 * behind it is compiled ahead of time instead of running interpreted on first use.
 *
 * The phone must be on the same network as a running Tauon: a fresh install scans for it and connects
 * on its own. Media volume should be muted first, since a song plays.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test fun generate() = rule.collect(packageName = TARGET, includeInStartupProfile = true) {
        pressHome()
        // Android 17 asks for local network access before Muon can reach Tauon.
        device.executeShellCommand("pm grant $TARGET android.permission.ACCESS_LOCAL_NETWORK")
        startActivityAndWait()
        check(device.wait(Until.hasObject(By.text("Songs")), 60_000)) { "The library did not open: is Tauon reachable?" }

        // Songs, Albums and Artists: each list flung down and back, and one album and one artist opened.
        scroll()
        tap("Albums"); scroll(); openFirst(); device.pressBack()
        tap("Artists"); scroll(); openFirst(); device.pressBack()
        // A song started from Songs, then Now Playing opened from the mini player, and its Queue.
        tap("Songs"); openFirst()
        val pause = device.wait(Until.findObject(By.desc("Pause")), 15_000) ?: return@collect
        device.click(device.displayWidth / 3, pause.visibleCenter.y)
        if (device.wait(Until.hasObject(By.desc("Collapse the player")), 5_000)) {
            tap("Queue"); device.pressBack()
            device.findObject(By.desc("Pause"))?.click()
            device.pressBack()
        }
    }

    /** Opens the first row or tile under the chips and sort bar. */
    private fun openFirst() {
        device.wait(Until.findObjects(By.clickable(true)), 5_000)
            ?.filter { it.visibleBounds.top > device.displayHeight * 2 / 5 }
            ?.minByOrNull { it.visibleBounds.top }?.click()
        device.waitForIdle()
    }

    private fun tap(text: String) {
        device.wait(Until.findObject(By.text(text)), 5_000)?.click()
        device.waitForIdle()
    }

    private fun scroll() {
        val list = device.wait(Until.findObject(By.scrollable(true)), 5_000) ?: return
        list.setGestureMargin(device.displayWidth / 5)
        repeat(2) { list.fling(Direction.DOWN) }
        list.fling(Direction.UP)
        device.waitForIdle()
    }
}
