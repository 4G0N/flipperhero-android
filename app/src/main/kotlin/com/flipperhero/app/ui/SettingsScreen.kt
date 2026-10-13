package com.flipperhero.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.flipperhero.agent.AIProvider
import com.flipperhero.agent.EngagementProfile

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s = vm.settings
    var yolo by remember { mutableStateOf(s.yolo) }
    var autoMed by remember { mutableStateOf(s.autoApproveMedium) }
    var asksAfter by remember { mutableStateOf(s.yoloAsksAfterReading) }
    var readAloud by remember { mutableStateOf(s.readAloud) }
    var autoConnect by remember { mutableStateOf(s.autoConnect) }
    var provider by remember { mutableStateOf(s.provider) }
    var model by remember { mutableStateOf(s.model) }
    var baseUrl by remember { mutableStateOf(s.apiBaseURL) }
    var apiKey by remember { mutableStateOf("") }
    var keyStored by remember { mutableStateOf(vm.secrets.hasApiKey(s.provider)) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Approvals", style = MaterialTheme.typography.titleMedium)
        SettingSwitch("YOLO mode (run risky actions without asking)", yolo) { yolo = it; s.yolo = it }
        SettingSwitch("Still ask after reading Flipper content", asksAfter) { asksAfter = it; s.yoloAsksAfterReading = it }
        SettingSwitch("Auto-approve medium-risk actions", autoMed) { autoMed = it; s.autoApproveMedium = it }

        Divider()
        Text("App", style = MaterialTheme.typography.titleMedium)
        SettingSwitch("Read replies aloud (if a TTS engine exists)", readAloud) { readAloud = it; s.readAloud = it }
        SettingSwitch("Auto-connect to last Flipper on launch", autoConnect) { autoConnect = it; s.autoConnect = it }

        Divider()
        Text("AI provider", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            provider,
            { typed ->
                val id = typed.trim().lowercase()
                provider = id; s.provider = id
                // A known provider brings its own endpoint and default model; a stale pair sends the key nowhere useful.
                AIProvider.fromId(id)?.let { known ->
                    baseUrl = known.baseURL; s.apiBaseURL = known.baseURL
                    model = known.defaultModel; s.model = known.defaultModel
                    keyStored = vm.secrets.hasApiKey(id)
                }
            },
            label = { Text("Provider id (${AIProvider.entries.joinToString { it.id }})") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(model, { model = it; s.model = it }, label = { Text("Model") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(baseUrl, { baseUrl = it; s.apiBaseURL = it }, label = { Text("API base URL") }, modifier = Modifier.fillMaxWidth())
        Text(if (keyStored) "API key: stored" else "API key: none", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = apiKey, onValueChange = { apiKey = it },
            label = { Text("Set API key (write-only)") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "API key, write only" },
        )
        Button(
            onClick = { vm.secrets.setApiKey(provider, apiKey.trim().ifEmpty { null }); keyStored = vm.secrets.hasApiKey(provider); apiKey = "" },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save key") }

        Divider()
        Text("Engagement mode (authorized red-team sessions)", style = MaterialTheme.typography.titleMedium)
        EngagementControls(vm)

        Divider()
        Text("Platform", style = MaterialTheme.typography.titleMedium)
        Text(
            if (vm.speechAvailable) "Voice input: a speech recognizer is available."
            else "Voice input: no speech recognizer on this device — the app stays text-only.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun EngagementControls(vm: AppViewModel) {
    var rawRpc by remember { mutableStateOf(false) }
    var autoBadKb by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    val engagement by vm.settings.engagement.collectAsState()
    if (engagement.active) {
        Text("Armed: ${engagement.profile.summary}", color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = { vm.disarmEngagement() }, modifier = Modifier.fillMaxWidth()) { Text("Disarm") }
    } else {
        SettingSwitch("Unlock rpc_raw", rawRpc) { rawRpc = it }
        SettingSwitch("Unlock badusb_execute (auto-run)", autoBadKb) { autoBadKb = it }
        OutlinedTextField(note, { note = it }, label = { Text("Scope note") }, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = { vm.armEngagement(EngagementProfile(autoApprovals = true, rawRPC = rawRpc, autoBadKB = autoBadKb, note = note)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Arm engagement mode") }
    }
}
