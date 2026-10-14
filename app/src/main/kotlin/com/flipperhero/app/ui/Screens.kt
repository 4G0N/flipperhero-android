package com.flipperhero.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.flipperhero.agent.ChatRole
import com.flipperhero.flipper.FlipperKey
import com.flipperhero.flipper.FlipperPath
import com.flipperhero.flipper.FlipperScreenFrame

@Composable
fun ConnectScreen(vm: AppViewModel) {
    val discovered by vm.discovered.collectAsState()
    val connection by vm.connection.collectAsState()
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Connect a Flipper", style = MaterialTheme.typography.headlineSmall)
        (connection as? ConnectionState.Failed)?.let {
            Text(it.message, color = MaterialTheme.colorScheme.error)
        }
        Button(onClick = { vm.startScan() }, modifier = Modifier.fillMaxWidth()) { Text("Scan") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(discovered) { d ->
                OutlinedButton(
                    onClick = { vm.connect(d.address, d.name) },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Connect to ${d.name}, signal ${d.rssi}" },
                ) { Text("${d.name}   (${d.rssi} dBm)") }
            }
        }
    }
}

@Composable
fun ChatScreen(vm: AppViewModel) {
    val messages by vm.chat.collectAsState()
    val busy by vm.busy.collectAsState()
    val activity by vm.activity.collectAsState()
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { m ->
                val who = if (m.role == ChatRole.USER) "You" else "Agent"
                Column {
                    Text(who, style = MaterialTheme.typography.labelSmall)
                    Text(
                        m.text,
                        color = if (m.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(8.dp))
                Text(activity ?: "Working…", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).semantics { contentDescription = "Message to the agent" },
                label = { Text("Ask the agent") },
            )
            Button(
                onClick = { vm.sendChat(input); input = "" },
                enabled = !busy && input.isNotBlank(),
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Send") }
        }
    }
}

@Composable
fun DeviceScreen(vm: AppViewModel) {
    val info by vm.deviceInfo.collectAsState()
    val power by vm.power.collectAsState()
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { vm.refreshDevice() }, modifier = Modifier.fillMaxWidth()) { Text("Read device info") }
        (power["charge_level"] ?: power["charge.level"])?.let { Text("Battery: $it%", style = MaterialTheme.typography.titleMedium) }
        Divider()
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(info.entries.toList()) { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodyMedium) }
        }
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("New device name (max 8)") },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "New Flipper device name" },
        )
        Button(onClick = { vm.rename("", name) }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Rename (applies after restart)")
        }
    }
}

@Composable
fun FilesScreen(vm: AppViewModel) {
    val entries by vm.files.collectAsState()
    val path by vm.path.collectAsState()
    val fileView by vm.fileView.collectAsState()
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(path, style = MaterialTheme.typography.titleSmall)
        Row {
            if (path != "/ext") {
                TextButton(onClick = { vm.browse(FlipperPath.parent(path).ifEmpty { "/ext" }) }) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "Up one folder"); Text("Up")
                }
            }
            TextButton(onClick = { vm.browse(path) }) { Text("Refresh") }
        }
        Divider()
        LazyColumn(Modifier.weight(1f)) {
            items(entries) { e ->
                val target = FlipperPath.join(path, e.name)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp).semantics {
                        contentDescription = if (e.isDirectory) "Folder ${e.name}" else "File ${e.name}, ${e.size} bytes"
                    }.let { m ->
                        m.combinedClickableCompat { if (e.isDirectory) vm.browse(target) else vm.openFile(target) }
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (e.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile, contentDescription = null)
                    Text("  ${e.name}" + if (!e.isDirectory) "  (${e.size} B)" else "", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
    if (fileView != null) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { vm.closeFileView() }) {
            androidx.compose.material3.Card {
                Column(Modifier.padding(16.dp)) {
                    Text(fileView!!, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { vm.closeFileView() }) { Text("Close") }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RemoteScreen(vm: AppViewModel) {
    val frame by vm.screen.collectAsState()
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Button(onClick = { vm.startScreenMirror() }) { Text("Start screen") }
            OutlinedButton(onClick = { vm.stopScreenMirror() }, modifier = Modifier.padding(start = 8.dp)) { Text("Stop") }
        }
        frame?.let { FlipperScreenImage(it, Modifier.fillMaxWidth().aspectRatio(2f)) }

        Spacer(Modifier.height(8.dp))
        // D-pad laid out like the physical keys: Up on top, Left/OK/Right across the middle, Down
        // below, Back under the cross. Tap = short press, long-press = long press.
        DpadButton(FlipperKey.UP, "Up", Icons.Filled.KeyboardArrowUp, vm)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            DpadButton(FlipperKey.LEFT, "Left", Icons.Filled.KeyboardArrowLeft, vm)
            DpadButton(FlipperKey.OK, "OK", icon = null, vm = vm, filled = true)
            DpadButton(FlipperKey.RIGHT, "Right", Icons.Filled.KeyboardArrowRight, vm)
        }
        DpadButton(FlipperKey.DOWN, "Down", Icons.Filled.KeyboardArrowDown, vm)
        Spacer(Modifier.height(8.dp))
        DpadButton(FlipperKey.BACK, "Back", icon = null, vm = vm, wide = true)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DpadButton(
    key: FlipperKey,
    label: String,
    icon: ImageVector?,
    vm: AppViewModel,
    filled: Boolean = false,
    wide: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = if (wide) RoundedCornerShape(28.dp) else CircleShape,
        color = if (filled) scheme.primary else scheme.surface,
        contentColor = if (filled) scheme.onPrimary else scheme.primary,
        border = BorderStroke(2.dp, scheme.primary),
        modifier = Modifier
            .then(if (wide) Modifier.height(56.dp).widthIn(min = 180.dp) else Modifier.size(72.dp))
            .semantics { contentDescription = "$label: tap for a short press, long-press for a long press" }
            .combinedClickable(
                onClick = { vm.press(key, long = false) },
                onLongClick = { vm.press(key, long = true) },
            ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(34.dp))
            } else {
                Text(label, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
fun FlipperScreenImage(frame: FlipperScreenFrame, modifier: Modifier = Modifier) {
    val image = remember(frame) {
        val size = frame.displaySize
        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        val pixels = frame.pixels()
        val ink = 0xFF160E06.toInt()
        val backlight = 0xFFFF8200.toInt()
        val argb = IntArray(pixels.size) { if (pixels[it]) ink else backlight }
        bitmap.setPixels(argb, 0, size.width, 0, 0, size.width, size.height)
        bitmap.asImageBitmap()
    }
    Image(
        bitmap = image,
        contentDescription = "Live Flipper screen",
        modifier = modifier,
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None,
    )
}
