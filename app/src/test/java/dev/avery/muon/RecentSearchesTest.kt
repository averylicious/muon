package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentSearchesTest {
    @Test fun newestFirstWithoutDuplicates() {
        assertEquals(listOf("zedd", "aurora"), recentSearchesAfter(listOf("aurora"), "  zedd "))
        assertEquals(listOf("Aurora", "zedd"), recentSearchesAfter(listOf("zedd", "aurora"), "Aurora"))
    }

    @Test fun blankAndShortSearchesAreNotKept() {
        assertEquals(listOf("zedd"), recentSearchesAfter(listOf("zedd"), "   "))
        assertEquals(listOf("zedd"), recentSearchesAfter(listOf("zedd"), "a"))
    }

    @Test fun keepsAtMostTheLimitAndTidiesSpaces() {
        val full = (1..RECENT_SEARCHES_KEPT).map { "song $it" }
        val after = recentSearchesAfter(full, "new   one")
        assertEquals(RECENT_SEARCHES_KEPT, after.size)
        assertEquals("new one", after.first())
        assertEquals("song ${RECENT_SEARCHES_KEPT - 1}", after.last())
    }

    @Test fun removingOneIgnoresCase() {
        assertEquals(listOf("zedd"), recentSearchesWithout(listOf("Aurora", "zedd"), "aurora"))
    }

    @Test fun storedListRoundTrips() {
        val list = listOf("zedd", "the middle", "ncs")
        assertEquals(list, decodeRecentSearches(encodeRecentSearches(list)))
        assertEquals(emptyList<String>(), decodeRecentSearches(null))
        assertEquals(listOf("a b", "c d"), decodeRecentSearches("a b\n\n c d \nA B"))
    }
}
