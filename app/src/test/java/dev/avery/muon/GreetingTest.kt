package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GreetingTest {
    @Test fun theGreetingFollowsTheTimeOfDay() {
        assertEquals("Good\nmorning.", greetingFor(hour = 5, offline = false))
        assertEquals("Good\nmorning.", greetingFor(hour = 11, offline = false))
        assertEquals("Good\nafternoon.", greetingFor(hour = 12, offline = false))
        assertEquals("Good\nevening.", greetingFor(hour = 17, offline = false))
        assertEquals("Late-night\nlistening.", greetingFor(hour = 22, offline = false))
        assertEquals("Late-night\nlistening.", greetingFor(hour = 0, offline = false))
        assertEquals("Late-night\nlistening.", greetingFor(hour = 4, offline = false))
    }

    @Test fun offlineItSaysTheMusicCameAlongWhateverTheHour() {
        for (hour in 0..23) assertEquals("Your music,\nwith you.", greetingFor(hour, offline = true))
    }

    /** One height all day: every greeting is exactly two lines. */
    @Test fun everyGreetingIsTwoLines() {
        for (hour in 0..23) for (offline in listOf(false, true))
            assertTrue(greetingFor(hour, offline).lines().size == 2)
    }
}
