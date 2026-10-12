package com.flipperhero.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private data class Tab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("Chat", Icons.Filled.Chat),
    Tab("Device", Icons.Filled.Memory),
    Tab("Files", Icons.Filled.Folder),
    Tab("Remote", Icons.Filled.Gamepad),
    Tab("Settings", Icons.Filled.Settings),
)

@Composable
fun FlipperHeroApp(vm: AppViewModel) {
    val connection by vm.connection.collectAsState()
    val pending by vm.pendingApproval.collectAsState()

    Box(Modifier.fillMaxSize()) {
        when (connection) {
            is ConnectionState.Connected -> MainScaffold(vm)
            is ConnectionState.Connecting -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Text(
                    "Connecting… confirm the pairing PIN on the Flipper if asked.",
                    Modifier.padding(16.dp),
                )
            }
            else -> ConnectScreen(vm)
        }

        pending?.let { ApprovalSheet(it, vm::resolveApproval) }
    }
}

@Composable
private fun MainScaffold(vm: AppViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { index, t ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> ChatScreen(vm)
                1 -> DeviceScreen(vm)
                2 -> FilesScreen(vm)
                3 -> RemoteScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }
}
