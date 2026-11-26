package com.rtbishop.look4sat.feature.settings

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.model.RadioPttMethod
import com.rtbishop.look4sat.core.presentation.MainTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w360dp-h800dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RadioCatWorkbenchTest {
    @get:Rule val compose = createComposeRule()
    private val initial = RadioControlSettings(true, RadioControlSettings.MODEL_ICOM_IC705,
        "00:11:22:33:44:55", "", "IC-705", "", 19200, splitMode = true)

    @Test fun sectionHeadingKeepsSpaceBelowFixedTabs() {
        compose.setContent { MainTheme { RadioControlDialog(initial, {}, {}) } }
        val tab = compose.onNodeWithText("Radio", substring = false).getBoundsInRoot()
        val heading = compose.onNodeWithText("Radio Model").getBoundsInRoot()
        assertTrue("Section heading must not touch the tabs", heading.top.value - tab.bottom.value >= 16f)
        compose.onNodeWithText("Connection").performClick()
        compose.onNodeWithText("Radio", substring = false).assertIsDisplayed().performClick()
        compose.onNodeWithText("Radio Model").assertIsDisplayed()
    }


    @Test fun ic705PairingUsesSameSheetAndBackPreservesDraft() {
        val saved = mutableListOf<RadioControlSettings>()
        compose.setContent { MainTheme { RadioControlDialog(initial, {}, saved::add) } }
        compose.onNodeWithText("Connection").performClick()
        compose.onNodeWithText("Select Device").performScrollTo().performClick()
        compose.onNodeWithText("Bluetooth pairing").assertIsDisplayed()
        compose.onNodeWithText("Pairing Reception", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Allow Bluetooth discovery").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Connection").assertExists()
        compose.onNodeWithText("Accept").performClick()
        compose.runOnIdle { assertEquals(initial.txRadioAddress, saved.single().txRadioAddress) }
    }

    @Test fun pttDraftSurvivesSwitchingAllSections() {
        val saved = mutableListOf<RadioControlSettings>()
        compose.setContent { MainTheme { RadioControlDialog(initial, {}, saved::add) } }
        compose.onNodeWithText("PTT").performClick()
        compose.onNodeWithText("Audio VOX").performScrollTo().performClick()
        compose.onNodeWithText("Connection").assertIsDisplayed().performClick()
        compose.onNodeWithText("More parameters").assertIsDisplayed().performClick()
        compose.onNodeWithText("Radio", substring = false).assertIsDisplayed().performClick()
        compose.onNodeWithText("Accept").performClick()
        compose.runOnIdle { assertEquals(RadioPttMethod.VOX, saved.single().pttMethod) }
    }

    @Test fun saveAndConnectUsesTheDraftAndKeepsThePanelOpen() {
        val connections = mutableListOf<RadioControlSettings>()
        var closed = false
        compose.setContent { MainTheme { RadioControlDialog(initial, { closed = true }, {}, onConnect = connections::add) } }
        compose.onNodeWithText("Connection").performClick()
        compose.onNodeWithText("Save and connect").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(initial.txRadioAddress, connections.single().txRadioAddress)
            assertFalse(closed)
        }
        compose.onNodeWithText("Current connection").assertExists()
    }

    @Test fun classicDiscoveryRequestsThePermissionsRequiredByEachAndroidGeneration() {
        assertArrayEquals(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), radioPairingPermissions(30))
        assertEquals(setOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
            radioPairingPermissions(31).toSet())
        assertEquals(radioPairingPermissions(31).toList(), radioPairingPermissions(36).toList())
    }

    @Test fun discoveredSerialDeviceIsSelectedOnlyAfterBondConfirmation() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        Shadows.shadowOf(adapter).setEnabled(true)
        val device = adapter.getRemoteDevice("00:12:34:56:78:90")
        val shadow = Shadows.shadowOf(device)
        shadow.setName("Serial CAT")
        shadow.setType(BluetoothDevice.DEVICE_TYPE_CLASSIC)
        shadow.setBondState(BluetoothDevice.BOND_NONE)
        shadow.setCreatedBond(true)
        val selected = mutableListOf<String>()
        compose.setContent { MainTheme { RadioBluetoothPairing(RadioControlSettings.MODEL_YAESU_FTX1) { address, _ -> selected += address } } }
        compose.runOnIdle { app.sendBroadcast(Intent(BluetoothDevice.ACTION_FOUND).putExtra(BluetoothDevice.EXTRA_DEVICE, device)) }
        compose.onNodeWithText("Serial CAT").assertExists()
        compose.onNodeWithText("Pair device").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(selected.isEmpty()) }
        compose.onNodeWithText("Confirm the pairing request on the phone and radio.").assertExists()
        compose.runOnIdle {
            shadow.setBondState(BluetoothDevice.BOND_BONDED)
            app.sendBroadcast(Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED).putExtra(BluetoothDevice.EXTRA_DEVICE, device))
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(device.address), selected) }
    }

    @Test fun pairedDeviceCanBeUsedWithoutGrantingDiscoveryPermission() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_SCAN)
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        val device = adapter.getRemoteDevice("00:12:34:56:78:91")
        Shadows.shadowOf(adapter).setEnabled(true)
        Shadows.shadowOf(device).setName("Paired CAT")
        Shadows.shadowOf(device).setBondState(BluetoothDevice.BOND_BONDED)
        Shadows.shadowOf(adapter).setBondedDevices(setOf(device))
        val selected = mutableListOf<String>()
        compose.setContent { MainTheme(isDarkTheme = true) { RadioBluetoothPairing(RadioControlSettings.MODEL_ICOM_IC705) { address, _ -> selected += address } } }
        compose.onNodeWithText("Use device").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(device.address), selected) }
    }

    @Test fun recreationDoesNotLoseACompletedPairingResult() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        Shadows.shadowOf(adapter).setEnabled(true)
        val device = adapter.getRemoteDevice("00:12:34:56:78:92")
        val shadow = Shadows.shadowOf(device)
        shadow.setType(BluetoothDevice.DEVICE_TYPE_CLASSIC)
        shadow.setCreatedBond(true)
        val selected = mutableListOf<String>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MainTheme { RadioBluetoothPairing(RadioControlSettings.MODEL_ICOM_IC705) { address, _ -> selected += address } } }
        compose.runOnIdle { app.sendBroadcast(Intent(BluetoothDevice.ACTION_FOUND).putExtra(BluetoothDevice.EXTRA_DEVICE, device)) }
        compose.onNodeWithText("Pair device").performScrollTo().performClick()
        compose.runOnIdle {
            shadow.setBondState(BluetoothDevice.BOND_BONDED)
            Shadows.shadowOf(adapter).setBondedDevices(setOf(device))
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(listOf(device.address), selected) }
    }
}
