package com.rtbishop.look4sat.feature.settings

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import com.rtbishop.look4sat.core.presentation.AppDisplayDensity
import com.rtbishop.look4sat.core.presentation.MainTheme
import com.rtbishop.look4sat.core.presentation.SharedDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w360dp-h800dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DisplayDensityUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sheetInheritsAppScaleAndAvailableWidthWithoutChangingSystemFontScale() {
        var base = 0f
        var baseFont = 0f
        var baseWidth = 0
        var sheetDensity = 0f
        var sheetFont = 0f
        var sheetWidth = 0
        compose.setContent {
            base = LocalDensity.current.density
            baseFont = LocalDensity.current.fontScale
            baseWidth = LocalConfiguration.current.screenWidthDp
            AppDisplayDensity(.8f) { MainTheme {
                SharedDialog(title = "Display test", onDismissRequest = {}) {
                    val density = LocalDensity.current
                    val configuration = LocalConfiguration.current
                    SideEffect {
                        sheetDensity = density.density
                        sheetFont = density.fontScale
                        sheetWidth = configuration.screenWidthDp
                    }
                }
            } }
        }
        compose.runOnIdle {
            assertEquals(base * .8f, sheetDensity, .001f)
            assertEquals(baseFont, sheetFont)
            assertEquals((baseWidth / .8f).toInt(), sheetWidth)
        }
    }
}
