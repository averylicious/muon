package dev.avery.muon

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WavySeekMotionTest {
    private class FrameClock : MonotonicFrameClock {
        val frames = Channel<Long>(Channel.RENDEZVOUS)
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(frames.receive())
    }

    @Test fun systemScaleStopsFramesAndResumesAtTheSamePhaseAtTheRequestedSpeed() {
        val clock = FrameClock()
        runTest(clock) {
            val factor = mutableFloatStateOf(0f)
            val scale = object : MotionDurationScale { override val scaleFactor get() = factor.floatValue }
            val phase = mutableFloatStateOf(0f)
            val rolling = launch { rollSeekWave(phase, scale) }
            runCurrent()
            assertFalse(clock.frames.trySend(0).isSuccess) // No frame subscriber with animations off.
            factor.floatValue = 1f; Snapshot.sendApplyNotifications(); runCurrent()
            assertTrue(clock.frames.trySend(0).isSuccess); runCurrent()
            assertTrue(clock.frames.trySend(800_000_000).isSuccess); runCurrent()
            assertEquals(0.5f, phase.floatValue, 0.0001f)
            factor.floatValue = 0f; Snapshot.sendApplyNotifications(); runCurrent()
            assertFalse(clock.frames.trySend(900_000_000).isSuccess)
            assertEquals(0.5f, phase.floatValue, 0.0001f)
            factor.floatValue = 2f; Snapshot.sendApplyNotifications(); runCurrent()
            assertTrue(clock.frames.trySend(1_000_000_000).isSuccess); runCurrent()
            assertEquals(0.5f, phase.floatValue, 0.0001f) // No jump for time spent paused.
            assertTrue(clock.frames.trySend(1_800_000_000).isSuccess); runCurrent()
            assertEquals(0.75f, phase.floatValue, 0.0001f) // Twice the duration, half the speed.
            rolling.cancelAndJoin()
            assertFalse(clock.frames.trySend(2_000_000_000).isSuccess)
        }
    }
}
