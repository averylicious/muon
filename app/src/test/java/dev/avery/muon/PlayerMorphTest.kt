package dev.avery.muon

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun thePlayerFadesInOverTheMiniPlayerEarlyAndItsControlsFollow() {
        assertEquals(0f, morphSheetAlpha(0f), 0f)
        assertEquals(1f, morphSheetAlpha(0.18f), 1e-6f)
        assertEquals(1f, morphSheetAlpha(1f), 0f)
        assertEquals(0f, morphContentAlpha(0.2f), 0f)
        assertEquals(0.5f, morphContentAlpha(0.45f), 1e-6f)
        assertEquals(1f, morphContentAlpha(0.7f), 1e-6f)
        assertEquals(1f, morphContentAlpha(1f), 0f)
    }

    @Test fun theGrowingPlayerShowsOnlyItsTopPartWithRoundedTopCorners() {
        val outline = TopRoundedClip(corner = 40f, height = 180f)
            .createOutline(Size(1080f, 2400f), LayoutDirection.Ltr, Density(1f)) as Outline.Rounded
        assertEquals(180f, outline.roundRect.bottom, 0f)
        assertEquals(1080f, outline.roundRect.right, 0f)
        assertEquals(40f, outline.roundRect.topLeftCornerRadius.x, 0f)
        assertEquals(0f, outline.roundRect.bottomLeftCornerRadius.x, 0f)
    }

    @Test fun theOutlineStaysInsideTheLayerAndItsCornersFitIt() {
        val tall = TopRoundedClip(corner = 40f, height = 5000f)
            .createOutline(Size(1080f, 2400f), LayoutDirection.Ltr, Density(1f)) as Outline.Rounded
        assertEquals(2400f, tall.roundRect.bottom, 0f)
        val short = TopRoundedClip(corner = 40f, height = 30f)
            .createOutline(Size(1080f, 2400f), LayoutDirection.Ltr, Density(1f)) as Outline.Rounded
        assertEquals(15f, short.roundRect.topLeftCornerRadius.x, 0f)
    }
}
