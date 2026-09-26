package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test

class StackPoseTest {
    @Test fun atRestTheTopCardIsFlatAndNothingPeeks() {
        val pose = stackPose(0f)
        assertEquals(1f, pose.cardScale, 0f)
        assertEquals(0f, pose.cardTilt, 0f)
        assertEquals(0f, pose.underAlpha, 0f)
    }

    @Test fun theTopCardTiltsTheWayItIsGoing() {
        assertTrue(stackPose(0.5f).cardTilt > 0f)
        assertTrue(stackPose(-0.5f).cardTilt < 0f)
        assertEquals(stackPose(0.5f).cardScale, stackPose(-0.5f).cardScale, 0f)
    }

    @Test fun theCardBeneathGrowsInAndIsFullyShownWellBeforeTheEnd() {
        assertTrue(stackPose(0.1f).underScale < stackPose(0.6f).underScale)
        assertEquals(1f, stackPose(0.4f).underAlpha, 0f)
        assertEquals(1f, stackPose(-1f).underScale, 1e-6f)
    }

    @Test fun beyondAWidthThePoseHolds() {
        assertEquals(stackPose(1f), stackPose(3f))
        assertEquals(STACK_TILT, stackPose(2f).cardTilt, 0f)
    }
}
