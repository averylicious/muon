package dev.avery.muon

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerMorphTest {
    @Test fun theEdgeTravelsFromTheMiniPlayersTopToWhereAnOpenPlayersEdgeRests() {
        // Closed, the edge is drawn at travel plus the inset the content gives back: the mini player's top.
        val travel = morphTravel(miniTop = 2000f, topInset = 130f)!!
        assertEquals(1870f, travel, 0f)
        assertEquals(2000f, playerSheetEdgeDrop(1f * travel, 130f), 0f)
        assertEquals(0f, playerSheetEdgeDrop(0f * travel, 130f), 0f)
    }

    @Test fun aMiniPlayerThatIsNotBelowTheInsetCannotBeGrownFrom() {
        assertNull(morphTravel(miniTop = 100f, topInset = 130f))
        assertNull(morphTravel(miniTop = 130f, topInset = 130f))
    }

    @Test fun thePanelStartsAsTheMiniPlayerAndTurnsIntoNowPlaying() {
        // The colour starts exactly at the mini player's, eases, and has arrived a third of the way open.
        assertEquals(0f, morphColorProgress(0f), 0f)
        assertEquals(0.5f, morphColorProgress(0.175f), 1e-6f)
        assertEquals(1f, morphColorProgress(0.35f), 1e-6f)
        assertEquals(1f, morphColorProgress(1f), 0f)
        // The mini player's own content is fully there at the start and gone a quarter of the way open.
        assertEquals(1f, morphFaceAlpha(0f), 0f)
        assertEquals(0.5f, morphFaceAlpha(0.125f), 1e-6f)
        assertEquals(0f, morphFaceAlpha(0.25f), 1e-6f)
        assertEquals(0f, morphFaceAlpha(1f), 0f)
        assertEquals(0f, morphContentAlpha(0.2f), 0f)
        assertEquals(0.5f, morphContentAlpha(0.45f), 1e-6f)
        assertEquals(1f, morphContentAlpha(0.7f), 1e-6f)
        assertEquals(1f, morphContentAlpha(1f), 0f)
    }

    @Test fun theCoverLeavesPromptlyAndLandsExactly() {
        assertEquals(0f, morphFlight(0f), 0f)
        assertEquals(1f, morphFlight(1f), 0f)
        assertEquals(0.75f, morphFlight(0.5f), 1e-6f)
        assertEquals(1f, morphFlight(1.5f), 0f)
    }

    @Test fun theCoverRisesWithThePanelFromTheThumbnailToNowPlaying() {
        val thumbnail = Rect(30f, 26f, 156f, 152f)        // from the mini player's top
        val nowPlaying = Rect(42f, 300f, 1038f, 1296f)    // from the player's top
        // Closed, it is the thumbnail where the mini player is; open, Now Playing's cover.
        assertEquals(thumbnail.translate(0f, 2000f), morphCoverRect(thumbnail, nowPlaying, edge = 2000f, open = 0f))
        assertEquals(nowPlaying, morphCoverRect(thumbnail, nowPlaying, edge = 0f, open = 1f))
        // A little way up a drag it has risen with the panel, not sunk towards Now Playing's place.
        val early = morphCoverRect(thumbnail, nowPlaying, edge = 1900f, open = 0.05f)
        assertTrue(early.top < thumbnail.top + 2000f)
    }

    // The rectangle rather than the Outline: an Outline with rounded corners builds an Android Path,
    // which JVM unit tests cannot create.
    @Test fun theGrowingPlayerShowsOnlyItsTopPartWithRoundedTopCorners() {
        val rect = topRoundedRect(Size(1080f, 2400f), corner = 40f, height = 180f)
        assertEquals(180f, rect.bottom, 0f)
        assertEquals(1080f, rect.right, 0f)
        assertEquals(40f, rect.topLeftCornerRadius.x, 0f)
        assertEquals(40f, rect.topRightCornerRadius.x, 0f)
        assertEquals(0f, rect.bottomLeftCornerRadius.x, 0f)
    }

    @Test fun theOutlineStaysInsideTheLayerAndItsCornersFitIt() {
        assertEquals(2400f, topRoundedRect(Size(1080f, 2400f), corner = 40f, height = 5000f).bottom, 0f)
        assertEquals(15f, topRoundedRect(Size(1080f, 2400f), corner = 40f, height = 30f).topLeftCornerRadius.x, 0f)
        assertEquals(0f, topRoundedRect(Size(1080f, 2400f), corner = 40f, height = -10f).bottom, 0f)
    }

    /** A 1080 px screen with a 66 px-tall capsule 36 px in from each side, its top 2000 px down. */
    private val capsule = Rect(36f, 2000f, 1044f, 2066f)
    private val screen = Size(1080f, 2400f)

    @Test fun aClosedPanelIsTheFloatingCapsule() {
        val rect = capsuleGrowRect(screen, capsule, open = 0f, height = 66f, sheetCorner = 84f, dropped = 2000f)
        assertEquals(36f, rect.left, 0f)
        assertEquals(1044f, rect.right, 0f)
        assertEquals(66f, rect.bottom, 0f)
        assertEquals(33f, rect.topLeftCornerRadius.x, 0f)
        assertEquals(33f, rect.bottomRightCornerRadius.x, 0f)
    }

    @Test fun anOpenPanelFillsTheWidthWithSquareBottomCorners() {
        val rect = capsuleGrowRect(screen, capsule, open = 1f, height = 2400f, sheetCorner = 84f, dropped = 2000f)
        assertEquals(0f, rect.left, 0f)
        assertEquals(1080f, rect.right, 0f)
        assertEquals(84f, rect.topLeftCornerRadius.x, 0f)
        assertEquals(0f, rect.bottomLeftCornerRadius.x, 0f)
    }

    @Test fun theTopCornersFlattenAsTheEdgeReachesTheTop() {
        val rect = capsuleGrowRect(screen, capsule, open = 1f, height = 2400f, sheetCorner = 84f, dropped = 10f)
        assertEquals(10f, rect.topRightCornerRadius.x, 0f)
    }

    @Test fun halfwayTheCapsuleIsHalfwayToTheEdges() {
        val rect = capsuleGrowRect(screen, capsule, open = 0.5f, height = 1000f, sheetCorner = 84f, dropped = 1000f)
        assertEquals(18f, rect.left, 0f)
        assertEquals(1062f, rect.right, 0f)
        assertEquals(16.5f, rect.bottomLeftCornerRadius.x, 0f)
    }
}
