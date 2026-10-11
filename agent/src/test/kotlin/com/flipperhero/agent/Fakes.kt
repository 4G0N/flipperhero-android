package com.flipperhero.agent

import com.flipperhero.flipper.FlipperApp
import com.flipperhero.flipper.FlipperDirEntry
import com.flipperhero.flipper.FlipperException
import com.flipperhero.flipper.FlipperGpioPin
import com.flipperhero.flipper.FlipperKey
import com.flipperhero.flipper.FlipperPath
import com.flipperhero.flipper.FlipperScreenFrame
import com.flipperhero.flipper.FlipperStorageInfo
import kotlin.time.Duration

/** In-memory Flipper for agent tests. Mirrors Swift `FakeFlipper`. */
class FakeFlipper(files: Map<String, String> = emptyMap()) : FlipperControlling {
    private val lock = Any()
    private val filesMap = HashMap<String, ByteArray>()
    private val dirsSet = hashSetOf("/ext")
    private val _calls = ArrayList<String>()
    private val _presses = ArrayList<String>()
    private val _rawRequests = ArrayList<String>()
    private var pendingName: String? = null
    var screen = FlipperScreenFrame(ByteArray(1024))

    init {
        for ((path, content) in files) filesMap[path] = content.toByteArray(Charsets.UTF_8)
    }

    val files: Map<String, ByteArray> get() = synchronized(lock) { HashMap(filesMap) }
    val calls: List<String> get() = synchronized(lock) { _calls.toList() }
    val presses: List<String> get() = synchronized(lock) { _presses.toList() }
    val rawRequests: List<String> get() = synchronized(lock) { _rawRequests.toList() }

    fun seed(path: String, bytes: ByteArray) = synchronized(lock) { filesMap[path] = bytes }

    private fun log(entry: String) = synchronized(lock) { _calls.add(entry) }

    override suspend fun deviceInfo(): Map<String, String> {
        log("deviceInfo")
        return mapOf("hardware_name" to "Laisear", "firmware_commit" to "d3f89dfe", "unrelated" to "x")
    }

    override suspend fun powerInfo(): Map<String, String> { log("powerInfo"); return mapOf("charge.level" to "87") }

    override suspend fun storageInfo(path: String): FlipperStorageInfo { log("storageInfo"); return FlipperStorageInfo(100, 40) }

    override suspend fun list(path: String): List<FlipperDirEntry> {
        log("list $path")
        val prefix = if (path.endsWith("/")) path else "$path/"
        val entries = ArrayList<FlipperDirEntry>()
        val seenDirs = HashSet<String>()
        synchronized(lock) {
            for ((full, data) in filesMap) {
                if (!full.startsWith(prefix)) continue
                val rest = full.substring(prefix.length)
                val slash = rest.indexOf('/')
                if (slash >= 0) {
                    val dir = rest.substring(0, slash)
                    if (seenDirs.add(dir)) entries.add(FlipperDirEntry(dir, true, 0))
                } else {
                    entries.add(FlipperDirEntry(rest, false, data.size))
                }
            }
            for (dir in dirsSet) {
                if (!dir.startsWith(prefix)) continue
                val rest = dir.substring(prefix.length)
                if (rest.isNotEmpty() && !rest.contains("/") && seenDirs.add(rest)) {
                    entries.add(FlipperDirEntry(rest, true, 0))
                }
            }
        }
        return entries.sortedBy { it.name }
    }

    override suspend fun stat(path: String): FlipperDirEntry = synchronized(lock) {
        filesMap[path]?.let { return FlipperDirEntry(FlipperPath.lastComponent(path), false, it.size) }
        if (dirsSet.contains(path)) return FlipperDirEntry(FlipperPath.lastComponent(path), true, 0)
        throw FlipperException.Rpc("not exist")
    }

    override suspend fun read(path: String, maxBytes: Int): ByteArray {
        log("read $path")
        val data = synchronized(lock) { filesMap[path] } ?: throw FlipperException.Rpc("not exist")
        if (data.size > maxBytes) throw FlipperException.Rpc("too large")
        return data
    }

    override suspend fun write(path: String, data: ByteArray) { log("write $path"); synchronized(lock) { filesMap[path] = data } }
    override suspend fun makeDirectory(path: String) { log("mkdir $path"); synchronized(lock) { dirsSet.add(path) } }
    override suspend fun delete(path: String, recursive: Boolean) {
        log("delete $path")
        synchronized(lock) {
            val toRemove = filesMap.keys.filter { it == path || (recursive && it.startsWith("$path/")) }
            toRemove.forEach { filesMap.remove(it) }
        }
    }
    override suspend fun rename(from: String, to: String) {
        log("rename $from $to")
        synchronized(lock) { filesMap.remove(from)?.let { filesMap[to] = it } }
    }
    override suspend fun startApp(name: String, args: String) { log("startApp $name $args") }
    override suspend fun transmitOnce(app: FlipperApp, path: String, button: String, hold: Duration) {
        log("transmit ${app.appName} $path $button")
    }
    override suspend fun emulate(app: FlipperApp, path: String) { log("emulate ${app.appName} $path") }
    override suspend fun loadBadKeyboardScript(path: String) { log("badusb $path") }
    override suspend fun exitApp() { log("exitApp") }
    override suspend fun playAlert() { log("alert") }
    override suspend fun setDeviceName(name: String) { log("setName $name"); pendingName = name }
    override suspend fun customDeviceName(): String? = pendingName
    override suspend fun captureScreen(timeout: Duration): FlipperScreenFrame { log("capture"); return screen }
    override suspend fun press(key: FlipperKey, long: Boolean) {
        synchronized(lock) { _presses.add(if (long) "long_${key.key}" else key.key) }
    }
    override suspend fun gpioSetMode(pin: FlipperGpioPin, output: Boolean, pullUp: Boolean?) {
        log("gpio mode ${pin.key} ${if (output) "output" else "input"}")
    }
    override suspend fun gpioRead(pin: FlipperGpioPin): Boolean { log("gpio read ${pin.key}"); return true }
    override suspend fun gpioWrite(pin: FlipperGpioPin, level: Boolean) { log("gpio write ${pin.key} ${if (level) "high" else "low"}") }
    override suspend fun rawRpc(jsonRequest: String): String {
        log("rawRPC")
        synchronized(lock) { _rawRequests.add(jsonRequest) }
        return """{"commandStatus":"OK"}"""
    }
}

/** Returns a canned payload so forge tests do not need a network call. Mirrors Swift `StubLLM`. */
class StubLLM(private val content: String) : LLMClient {
    override suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): ChatMessage =
        ChatMessage(role = ChatRole.ASSISTANT, content = content)
}

/** Records approval requests and answers with a fixed verdict. Mirrors Swift `ScriptedGate`. */
class ScriptedGate(var answer: Boolean) : ApprovalGate {
    private val lock = Any()
    private val _requests = ArrayList<ApprovalRequest>()
    val requests: List<ApprovalRequest> get() = synchronized(lock) { _requests.toList() }
    override suspend fun decide(request: ApprovalRequest): Boolean {
        synchronized(lock) { _requests.add(request) }
        return answer
    }
}

/** Replays a script of assistant messages. Mirrors Swift `ScriptedLLM`. */
class ScriptedLLM(script: List<ChatMessage>) : LLMClient {
    private val lock = Any()
    private val script = ArrayDeque(script)
    private val _seen = ArrayList<List<ChatMessage>>()
    val seenMessages: List<List<ChatMessage>> get() = synchronized(lock) { _seen.toList() }
    override suspend fun complete(messages: List<ChatMessage>, tools: List<ToolSpec>): ChatMessage = synchronized(lock) {
        _seen.add(messages)
        if (script.isEmpty()) ChatMessage(role = ChatRole.ASSISTANT, content = "done") else script.removeFirst()
    }
}

fun toolCall(name: String, args: String = "{}", id: String = java.util.UUID.randomUUID().toString()): ChatMessage =
    ChatMessage(role = ChatRole.ASSISTANT, content = null, toolCalls = listOf(ToolCall(id, name, args)))

/** App-controls fake from Swift `PermissionTests`. */
class FakeAppControls : AppControls {
    var state = AppSettingsSnapshot(
        yolo = false, yoloAsksAfterReading = true, autoApproveMedium = false,
        readAloud = false, autoConnect = true, model = "m/x", apiKeyStored = true,
    )
    var restarts = 0; private set
    var connectionTests = 0; private set
    var engagement = EngagementState.inactive; private set
    var updatesStarted = 0; private set
    var updatesCancelled = 0; private set

    override suspend fun settings() = state
    override suspend fun setReadAloud(enabled: Boolean) { state = state.copy(readAloud = enabled) }
    override suspend fun setAutoConnect(enabled: Boolean) { state = state.copy(autoConnect = enabled) }
    override suspend fun setYolo(enabled: Boolean) { state = state.copy(yolo = enabled) }
    override suspend fun setYoloAsksAfterReading(enabled: Boolean) { state = state.copy(yoloAsksAfterReading = enabled) }
    override suspend fun setAutoApproveMedium(enabled: Boolean) { state = state.copy(autoApproveMedium = enabled) }
    override suspend fun setModel(model: String) { state = state.copy(model = model) }
    override suspend fun setProvider(provider: AIProvider, baseURL: String?) {
        state = state.copy(provider = provider.id, apiBaseURL = baseURL ?: provider.baseURL)
    }
    override suspend fun listModels(): List<AIModel> = listOf(AIModel("m/x", "Test model"))
    override suspend fun testConnection() { connectionTests++ }
    override suspend fun setEngagement(state: EngagementState) {
        engagement = state
        this.state = this.state.copy(engagementActive = state.active, engagementSummary = if (state.active) state.profile.summary else "off")
    }
    override suspend fun restartDevice() { restarts++ }
    override suspend fun startFirmwareUpdate(): String { updatesStarted++; return "Started installing mntm-012." }
    override suspend fun firmwareUpdateStatus(): String = if (updatesStarted > 0) "Uploading firmware.dfu, 40%" else "No update running."
    override suspend fun cancelFirmwareUpdate(): String { updatesCancelled++; return "Cancelled the update." }
}
