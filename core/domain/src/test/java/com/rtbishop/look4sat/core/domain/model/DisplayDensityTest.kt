package com.rtbishop.look4sat.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayDensityTest {
    @Test fun clampsFiniteValuesAndResetsInvalidValues() {
        assertEquals(.75f, DisplayDensity.normalize(.1f))
        assertEquals(1.3f, DisplayDensity.normalize(2f))
        assertEquals(1.15f, DisplayDensity.normalize(1.15f))
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            assertEquals(1f, DisplayDensity.normalize(it))
        }
    }
}
