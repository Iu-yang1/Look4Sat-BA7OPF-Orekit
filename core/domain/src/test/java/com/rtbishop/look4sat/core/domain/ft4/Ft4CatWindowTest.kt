package com.rtbishop.look4sat.core.domain.ft4

import org.junit.Test
import org.junit.Assert.assertEquals

class Ft4CatWindowTest {
    @Test
    fun gapUsesFollowingBurstAndReservesSettlingTime() {
        val window = Ft4CatWindow.following(6_000L)
        assertEquals(7_500L, window.nextSlotUtcMillis)
        assertEquals(10_520L, window.midpointUtcMillis)
        assertEquals(0L, window.remainingMillis(5_999L))
        assertEquals(1_340L, window.remainingMillis(6_000L))
        assertEquals(1L, window.remainingMillis(7_339L))
        assertEquals(0L, window.remainingMillis(7_340L))
        assertEquals(15_000L, Ft4CatWindow.following(7_500L).nextSlotUtcMillis)
    }
}
