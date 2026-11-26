package com.rtbishop.look4sat.feature.settings

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.rtbishop.look4sat.core.domain.rotator.*
import com.rtbishop.look4sat.core.presentation.MainTheme
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
class RotatorWorkbenchTest {
    @get:Rule val compose = createComposeRule()

    @Test fun connectionDraftAndSelectedPageSurviveRestoration() {
        val restoration = StateRestorationTester(compose)
        val saved = mutableListOf<RotatorSettings>()
        restoration.setContent { MainTheme {
            RotatorControlDialog(RotatorSettings(), RotatorTrackingState(), {}, saved::add, {}, {}, { _, _ -> }, {}, {})
        } }
        compose.onNodeWithText("127.0.0.1").performScrollTo().performTextReplacement("192.168.1.25")
        compose.onNode(hasText("Pointing") and hasClickAction()).performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasText("Pointing") and hasClickAction()).assertIsSelected()
        compose.onNode(hasText("Connection") and hasClickAction()).performClick()
        compose.onNodeWithText("192.168.1.25").assertExists()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals("192.168.1.25", saved.single().host) }
    }

    @Test fun stopRemainsAvailableAcrossAllPages() {
        var stops = 0
        compose.setContent { MainTheme {
            RotatorControlDialog(RotatorSettings(enabled = true),
                RotatorTrackingState(connectionState = RotatorConnectionState.CONNECTED),
                {}, {}, {}, {}, { _, _ -> }, {}, { stops++ })
        } }
        listOf("Connection", "Pointing", "Parking", "Control").forEach { page ->
            compose.onNode(hasText(page) and hasClickAction()).performClick()
            compose.onNodeWithText("STOP").assertIsDisplayed().performClick()
        }
        compose.runOnIdle { assertEquals(4, stops) }
    }
}
