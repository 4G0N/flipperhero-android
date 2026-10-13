package com.flipperhero.app.ui

import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flipperhero.agent.AgentSession
import com.flipperhero.agent.ChatMessage
import com.flipperhero.agent.ChatRole
import com.flipperhero.agent.EngagementProfile
import com.flipperhero.agent.EngagementState
import com.flipperhero.agent.PayloadForge
import com.flipperhero.agent.ToolExecutor
import com.flipperhero.agent.asControlling
import com.flipperhero.app.approval.PendingApproval
import com.flipperhero.app.ble.AndroidBleTransport
import com.flipperhero.app.ble.DiscoveredFlipper
import com.flipperhero.app.di.AppContainer
import com.flipperhero.app.service.FlipperForegroundService
import com.flipperhero.flipper.FlipperDirEntry
import com.flipperhero.flipper.FlipperKey
import com.flipperhero.flipper.FlipperRpcClient
import com.flipperhero.flipper.FlipperScreenFrame
import com.flipperhero.flipper.customDeviceName
import com.flipperhero.flipper.deviceInfo
import com.flipperhero.flipper.list
import com.flipperhero.flipper.powerInfo
import com.flipperhero.flipper.press
import com.flipperhero.flipper.read
import com.flipperhero.flipper.reboot
import com.flipperhero.flipper.setDeviceName
import com.flipperhero.flipper.storageInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Chat bubble as shown in the UI (distinct from the agent's wire [ChatMessage]). */
data class ChatBubble(val role: ChatRole, val text: String, val isError: Boolean = false)

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    data class Connecting(val name: String) : ConnectionState()
    data class Connected(val name: String) : ConnectionState()
    data class Failed(val message: String) : ConnectionState()
}

class AppViewModel(private val container: AppContainer, private val appContext: Context) : ViewModel() {

    // Connection & scanning
    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()
    private val _discovered = MutableStateFlow<List<DiscoveredFlipper>>(emptyList())
    val discovered: StateFlow<List<DiscoveredFlipper>> = _discovered.asStateFlow()

    // Chat
    private val _chat = MutableStateFlow<List<ChatBubble>>(emptyList())
    val chat: StateFlow<List<ChatBubble>> = _chat.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _activity = MutableStateFlow<String?>(null)
    val activity: StateFlow<String?> = _activity.asStateFlow()

    // Device / files / remote
    private val _deviceInfo = MutableStateFlow<Map<String, String>>(emptyMap())
    val deviceInfo: StateFlow<Map<String, String>> = _deviceInfo.asStateFlow()
    private val _power = MutableStateFlow<Map<String, String>>(emptyMap())
    val power: StateFlow<Map<String, String>> = _power.asStateFlow()
    private val _files = MutableStateFlow<List<FlipperDirEntry>>(emptyList())
    val files: StateFlow<List<FlipperDirEntry>> = _files.asStateFlow()
    private val _path = MutableStateFlow("/ext")
    val path: StateFlow<String> = _path.asStateFlow()
    private val _fileView = MutableStateFlow<String?>(null)
    val fileView: StateFlow<String?> = _fileView.asStateFlow()
    private val _screen = MutableStateFlow<FlipperScreenFrame?>(null)
    val screen: StateFlow<FlipperScreenFrame?> = _screen.asStateFlow()

    // Approval (bridged from the agent gate)
    val pendingApproval: StateFlow<PendingApproval?> = container.approver.pending

    // Settings mirror
    val settings get() = container.settings
    val secrets get() = container.secrets
    val speechAvailable: Boolean get() = container.speech.isAvailable()

    private var client: FlipperRpcClient? = null
    private var session: AgentSession? = null
    private var screenJob: Job? = null

    /** The notification's Stop button: the service has stopped itself; drop the link too. */
    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (client != null) disconnect()
        }
    }

    init {
        ContextCompat.registerReceiver(
            appContext, stopReceiver,
            IntentFilter(FlipperForegroundService.ACTION_STOP),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        viewModelScope.launch {
            container.scanner.status // touch to surface adapter state lazily
        }
    }

    fun startScan() {
        viewModelScope.launch {
            runCatching {
                container.scanner.scan().collect { _discovered.value = it }
            }
        }
    }

    fun connect(address: String, name: String) {
        if (_connection.value is ConnectionState.Connecting) return
        _connection.value = ConnectionState.Connecting(name)
        viewModelScope.launch {
            try {
                val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val device = manager.adapter.getRemoteDevice(address)
                val transport = AndroidBleTransport(appContext, device).connect()
                val rpc = FlipperRpcClient(transport)
                rpc.start()
                client = rpc
                val controlling = rpc.asControlling()
                val llm = container.llm
                val executor = ToolExecutor(
                    flipper = controlling,
                    gate = container.approver,
                    audit = container.audit,
                    forge = PayloadForge(llm),
                    profileStore = container.profileStore,
                    fapHub = container.fapHub,
                    gitHub = container.gitHub,
                    appControls = container.appControls(onRestartDevice = { rpc.reboot() }),
                    firmwareChecker = container.firmwareChecker,
                    settings = { container.settings.approvalSettings() },
                )
                session = AgentSession(llm, executor)
                container.settings.lastDeviceAddress = address
                container.ongoing.start("Connected to $name")
                _connection.value = ConnectionState.Connected(name)
            } catch (e: Exception) {
                _connection.value = ConnectionState.Failed(e.message ?: "Connection failed")
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            screenJob?.cancel(); screenJob = null
            container.approver.cancelAll()
            val closing = client
            client = null; session = null
            runCatching { closing?.stop() }
            container.ongoing.stop()
            _connection.value = ConnectionState.Disconnected
        }
    }

    fun resolveApproval(approval: PendingApproval, approved: Boolean) =
        container.approver.resolve(approval, approved)

    // ---- Chat (goes through the agent + full guardrails) ----
    fun sendChat(text: String) {
        val agent = session ?: return
        if (text.isBlank()) return
        _chat.value = _chat.value + ChatBubble(ChatRole.USER, text)
        _busy.value = true
        viewModelScope.launch {
            try {
                val reply = agent.send(text) { event -> _activity.value = event.toString() }
                _chat.value = _chat.value + ChatBubble(ChatRole.ASSISTANT, reply)
                if (container.settings.readAloud && container.tts.isAvailable()) container.tts.speak(reply)
            } catch (e: Exception) {
                _chat.value = _chat.value + ChatBubble(ChatRole.ASSISTANT, e.message ?: "Error", isError = true)
            } finally {
                _busy.value = false
                _activity.value = null
            }
        }
    }

    // ---- Direct reads / live remote (the user is the authority, not the agent) ----
    fun refreshDevice() {
        val rpc = client ?: return
        viewModelScope.launch {
            runCatching { _deviceInfo.value = rpc.deviceInfo() }
            runCatching { _power.value = rpc.powerInfo() }
        }
    }

    fun browse(path: String) {
        val rpc = client ?: return
        _path.value = path
        viewModelScope.launch { runCatching { _files.value = rpc.list(path) }.onFailure { _files.value = emptyList() } }
    }

    fun openFile(path: String) {
        val rpc = client ?: return
        viewModelScope.launch {
            _fileView.value = runCatching {
                val data = rpc.read(path, maxBytes = 16 * 1024)
                if (data.any { it == 0.toByte() }) "(binary file, ${data.size} bytes)" else String(data, Charsets.UTF_8)
            }.getOrElse { "Could not read: ${it.message}" }
        }
    }

    fun closeFileView() { _fileView.value = null }

    fun rename(from: String, to: String) {
        val rpc = client ?: return
        viewModelScope.launch { runCatching { rpc.setDeviceName(to) }; }
    }

    fun startScreenMirror() {
        val rpc = client ?: return
        if (screenJob != null) return
        screenJob = viewModelScope.launch {
            runCatching { rpc.acquireScreenStream() }
            runCatching { rpc.screenFrames().collect { _screen.value = it } }
        }
    }

    fun stopScreenMirror() {
        val rpc = client
        screenJob?.cancel(); screenJob = null
        viewModelScope.launch { runCatching { rpc?.releaseScreenStream() } }
    }

    fun press(key: FlipperKey, long: Boolean) {
        val rpc = client ?: return
        viewModelScope.launch { runCatching { rpc.press(key, long) } }
    }

    // ---- Settings (direct; the human is acting) ----
    fun armEngagement(profile: EngagementProfile) {
        container.settings.setEngagement(EngagementState(active = true, profile = profile, startedAt = System.currentTimeMillis()))
        viewModelScope.launch { session?.updateEngagement(container.settings.engagement.value) }
        container.ongoing.start("Engagement armed: ${profile.summary}")
    }

    fun disarmEngagement() {
        container.settings.setEngagement(EngagementState.inactive)
        viewModelScope.launch { session?.updateEngagement(EngagementState.inactive) }
    }

    suspend fun auditLog() = container.audit.recent(50)

    override fun onCleared() {
        super.onCleared()
        runCatching { appContext.unregisterReceiver(stopReceiver) }
        // viewModelScope is already cancelled here, so the link has to be closed on its own scope.
        // Left open, the foreground service keeps the process (and the GATT connection) alive with
        // no UI attached, and a connected Flipper stops advertising — the next scan finds nothing.
        val closing = client
        client = null; session = null
        screenJob?.cancel(); screenJob = null
        container.approver.cancelAll()
        if (closing != null) {
            container.ongoing.stop()
            CoroutineScope(Dispatchers.IO).launch { runCatching { closing.stop() } }
        }
        container.tts.shutdown()
    }
}
