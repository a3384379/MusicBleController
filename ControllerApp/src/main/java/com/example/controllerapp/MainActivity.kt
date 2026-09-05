package com.example.controllerapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.controllerapp.service.ControllerConnectionService
import com.example.controllerapp.ui.ControllerApp
import com.example.controllerapp.ui.ControllerTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ControllerViewModel by viewModels()
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasBluetoothPermission()) startConnectionService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ask for/restore the connected-device service before Compose performs its first frame.
        // This keeps the user-initiated foreground-start allowance alive on aggressive vendor
        // Android builds, including launches while the keyguard is showing.
        ensurePermissionsAndStart()
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> viewModel.setUiVisible(true)
                    Lifecycle.Event.ON_STOP -> viewModel.setUiVisible(false)
                    else -> Unit
                }
            }
        )
        setContent {
            ControllerTheme {
                ControllerApp(viewModel)
            }
        }
    }

    private fun ensurePermissionsAndStart() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startConnectionService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hasBluetoothPermission(): Boolean =
        requiredBluetoothPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun startConnectionService() {
        val intent = Intent(this, ControllerConnectionService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun requiredPermissions(): List<String> =
        buildList {
            addAll(requiredBluetoothPermissions())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

    private fun requiredBluetoothPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
}
