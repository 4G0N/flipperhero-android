package com.flipperhero.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.flipperhero.app.ui.AppViewModel
import com.flipperhero.app.ui.FlipperHeroApp
import com.flipperhero.app.ui.theme.FlipperHeroTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels {
        val container = (application as FlipperApplication).container
        viewModelFactory { initializer { AppViewModel(container, applicationContext) } }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Denial is a normal state: scanning/connecting surface a clear message and the UI degrades.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent system bars with icon colors that adapt to light/dark; the Compose layer then
        // insets its own content so nothing sits under the status bar or the camera cutout.
        enableEdgeToEdge()
        setContent {
            FlipperHeroTheme { FlipperHeroApp(viewModel) }
        }
        requestBlePermissions()
    }

    private fun requestBlePermissions() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }
}
