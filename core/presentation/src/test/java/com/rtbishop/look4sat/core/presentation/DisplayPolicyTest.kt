package com.rtbishop.look4sat.core.presentation

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.*
import org.junit.Test

class DisplayPolicyTest {
    @Test fun refreshPreferenceUsesHighestRateAtCurrentResolution() {
        val modes = listOf(RefreshRateMode(1080, 2400, 60f), RefreshRateMode(1080, 2400, 144f),
            RefreshRateMode(720, 1600, 165f), RefreshRateMode(1080, 2400, Float.NaN))
        assertEquals(144f, preferredUiRefreshRate(1080, 2400, modes, false))
        assertEquals(0f, preferredUiRefreshRate(1080, 2400, modes, true))
        assertEquals(0f, preferredUiRefreshRate(1440, 3200, modes, false))
    }

    @Test fun scalingChangesDpPixelsAndPreservesNonlinearFontConversion() {
        val base = object : Density {
            override val density = 3f
            override val fontScale = 1.8f
            override fun TextUnit.toDp(): Dp = (value * 1.5f).dp
            override fun Dp.toSp(): TextUnit = (value / 1.5f).sp
        }
        assertSame(base, scaledAppDensity(base, 1f))
        with(scaledAppDensity(base, .8f)) {
            assertEquals(24f, 10.dp.toPx(), .001f)
            assertEquals(1.8f, fontScale)
            assertEquals(15.dp, 10.sp.toDp())
            assertEquals(36f, 10.sp.toPx(), .001f)
        }
    }
}
