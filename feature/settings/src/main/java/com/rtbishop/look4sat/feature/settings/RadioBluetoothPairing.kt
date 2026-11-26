package com.rtbishop.look4sat.feature.settings

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.presentation.R
import kotlinx.coroutines.delay

internal fun radioPairingPermissions(sdk: Int): Array<String> = if (sdk >= 31) arrayOf(
    Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN
) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

internal data class PairingDevice(val address: String, val name: String, val bond: Int)

/** Pairing stays in the existing sheet; only Android owns PIN/passkey confirmation. */
@SuppressLint("MissingPermission")
@Composable
internal fun RadioBluetoothPairing(
    model: String,
    onSelected: (String, String) -> Unit
) {
    val context = LocalContext.current
    val adapter = remember(context) { context.getSystemService(BluetoothManager::class.java)?.adapter }
    var revision by remember { mutableIntStateOf(0) }
    var scanning by remember { mutableStateOf(false) }
    var requested by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    val found = remember { mutableStateMapOf<String, PairingDevice>() }
    val permissions = radioPairingPermissions(Build.VERSION.SDK_INT)
    val permitted = permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    val connectPermitted = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(context,
        Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        revision++
        permissionDenied = it.values.any { granted -> !granted }
        error = permissionDenied
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { revision++ }
    val currentSelection by rememberUpdatedState(onSelected)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun refresh() {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            adapter?.bondedDevices?.forEach { device ->
                found[device.address] = PairingDevice(device.address, device.name.orEmpty(), device.bondState)
            }
            // Bonding may have completed while the sheet was being recreated.
            found[requested]?.takeIf { it.bond == BluetoothDevice.BOND_BONDED }?.let { entry ->
                requested = ""
                currentSelection(entry.address, entry.name)
            }
        }.onFailure { error = true }
    }
    DisposableEffect(context, permitted, revision) {
        refresh()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> scanning = true
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanning = false
                    BluetoothAdapter.ACTION_STATE_CHANGED -> { revision++; refresh() }
                    BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        @Suppress("DEPRECATION")
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                        runCatching {
                            if (device.type == BluetoothDevice.DEVICE_TYPE_LE) return
                            val entry = PairingDevice(device.address, device.name.orEmpty(), device.bondState)
                            found[entry.address] = entry
                            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED && entry.address == requested) {
                                when (entry.bond) {
                                    BluetoothDevice.BOND_BONDED -> {
                                        requested = ""
                                        currentSelection(entry.address, entry.name)
                                    }
                                    BluetoothDevice.BOND_NONE -> { requested = ""; error = true }
                                }
                            }
                        }.onFailure { error = true }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        // Bluetooth broadcasts can originate from the privileged Bluetooth process, not the system UID.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose {
            context.unregisterReceiver(receiver)
            runCatching { adapter?.cancelDiscovery() }
        }
    }
    LaunchedEffect(requested) {
        if (requested.isNotEmpty()) {
            delay(90_000)
            requested = ""
            error = true
        }
    }
    val radioOn = runCatching { adapter?.isEnabled == true }.getOrDefault(false)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(model, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(when (model) {
            RadioControlSettings.MODEL_ICOM_IC705 -> R.string.cat_pair_ic705
            RadioControlSettings.MODEL_YAESU_FTX1 -> R.string.cat_pair_ftx1
            else -> R.string.cat_pair_generic
        }), style = MaterialTheme.typography.bodyMedium)
        if (!permitted) {
            Button(onClick = {
                if (permissionDenied) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)))
                else permissionLauncher.launch(permissions)
            }) { Text(stringResource(R.string.cat_pair_permission)) }
        } else if (!radioOn) {
            Button(onClick = { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }, enabled = adapter != null) {
                Text(stringResource(R.string.cat_pair_enable))
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    error = false
                    runCatching {
                        if (scanning) { adapter?.cancelDiscovery(); scanning = false }
                        else { refresh(); scanning = adapter?.startDiscovery() == true; error = !scanning }
                    }.onFailure { error = true }
                }, enabled = requested.isEmpty()) {
                    Text(stringResource(if (scanning) R.string.cat_pair_stop_search else R.string.cat_pair_search))
                }
            }
        }
        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) {
            Text(stringResource(R.string.cat_pair_system))
        }
        if (scanning || requested.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (requested.isNotEmpty()) Text(stringResource(R.string.cat_pair_confirm))
        if (error) Text(stringResource(R.string.cat_pair_failed), color = MaterialTheme.colorScheme.error)
        if (found.isEmpty()) Text(stringResource(R.string.cat_pair_empty))
        found.values.sortedWith(compareBy<PairingDevice> { it.bond != BluetoothDevice.BOND_BONDED }.thenBy { it.name })
            .forEach { entry ->
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.name.ifBlank { entry.address }, style = MaterialTheme.typography.titleSmall)
                        Text(entry.address, style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(when (entry.bond) {
                            BluetoothDevice.BOND_BONDED -> R.string.cat_pair_bonded
                            BluetoothDevice.BOND_BONDING -> R.string.cat_pair_bonding
                            else -> R.string.cat_pair_unpaired
                        }))
                        TextButton(enabled = connectPermitted && radioOn && requested.isEmpty(), onClick = {
                            error = false
                            runCatching {
                                runCatching { adapter?.cancelDiscovery() }
                                scanning = false
                                val device = checkNotNull(adapter).getRemoteDevice(entry.address)
                                if (device.bondState == BluetoothDevice.BOND_BONDED) currentSelection(entry.address, entry.name)
                                else {
                                    requested = entry.address
                                    if (!device.createBond()) { requested = ""; error = true }
                                }
                            }.onFailure { requested = ""; error = true }
                        }) {
                            Text(stringResource(if (entry.bond == BluetoothDevice.BOND_BONDED)
                                R.string.cat_pair_use else R.string.cat_pair_action))
                        }
                    }
                }
            }
        Spacer(Modifier.height(16.dp))
    }
}
