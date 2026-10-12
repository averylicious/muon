package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test

class DownloadMoveOwnershipTest {
    @Test fun removalInvalidatesAllOlderBatchesForOnlyThatSong() {
        val ownership = DownloadMoveOwnership()
        val first = ownership.begin()
        val second = ownership.begin()
        ownership.remove(listOf("removed"))
        var commands = 0
        ownership.publish(first, "removed") { commands++ }
        ownership.publish(second, "removed") { commands++ }
        assertEquals(0, commands)
        ownership.publish(first, "other") { commands++ }
        assertEquals(1, commands)
    }

    @Test fun explicitMoveAfterRemoveAllGetsFreshOwnership() {
        val ownership = DownloadMoveOwnership()
        val older = ownership.begin()
        ownership.removeAll()
        val newer = ownership.begin()
        var commands = 0
        ownership.publish(older, "song") { commands++ }
        ownership.publish(newer, "song") { commands++ }
        assertEquals(1, commands)
    }

    @Test fun finishedBatchCannotPublishEvenAfterAnotherMoveStarts() {
        val ownership = DownloadMoveOwnership()
        val older = ownership.begin()
        ownership.finish(older)
        val newer = ownership.begin()
        var commands = 0
        ownership.publish(older, "song") { commands++ }
        ownership.publish(newer, "song") { commands++ }
        assertEquals(1, commands)
    }
}
