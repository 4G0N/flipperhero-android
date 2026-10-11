package com.flipperhero.agent

import com.flipperhero.flipper.FlipperApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class ToolResult(val content: String, val kind: Kind, val images: List<ByteArray> = emptyList()) {
    enum class Kind { OK, ERROR, DENIED, BLOCKED }

    val isError: Boolean get() = kind != Kind.OK

    companion object {
        fun ok(text: String, images: List<ByteArray> = emptyList()) = ToolResult(text, Kind.OK, images)
        fun error(text: String, kind: Kind = Kind.ERROR) = ToolResult(text, kind)
    }
}

/**
 * Runs model tool calls: validate, assess risk, ask the user, execute, audit. Never throws; every
 * failure becomes an error result the model can read. Mirrors Swift `ToolExecutor` (an actor — here
 * a [Mutex] serializes execution, matching the single-threaded RPC session).
 */
class ToolExecutor(
    private val flipper: FlipperControlling,
    private val gate: ApprovalGate,
    private val audit: AuditSink,
    private val forge: PayloadForge? = null,
    private val profileStore: DeviceProfileStore? = null,
    private val fapHub: FapHubClient? = null,
    private val gitHub: GitHubClient? = null,
    private val appControls: AppControls? = null,
    private val firmwareChecker: FirmwareUpdateChecker? = null,
    private val settings: () -> ApprovalSettings = { ApprovalSettings() },
    /** How an approval is recorded: APPROVED in the app, SHORTCUT for Siri/Shortcuts analogues. */
    private val approvedAs: AuditRecord.Decision = AuditRecord.Decision.APPROVED,
    val nonce: String = UUID.randomUUID().toString().take(8).lowercase(),
) {
    private val lock = Mutex()

    @Volatile
    private var taintedFlag = false
    val isTainted: Boolean get() = taintedFlag

    /** Call when the user sends a new message; their words re-establish trust. */
    fun newUserTurn() { taintedFlag = false }

    /** Set by the session so a refresh can push the new inventory into the system prompt. */
    @Volatile
    var onProfileRefreshed: ((DeviceProfile) -> Unit)? = null

    /** Content prepared during approval, reused at execution so the user approves exactly what is written. */
    private val forged = HashMap<String, String>()
    private val downloaded = HashMap<String, ByteArray>()
    private var pendingImages = ArrayList<ByteArray>()

    suspend fun execute(call: ToolCall): ToolResult = lock.withLock {
        val invocation: ToolInvocation = try {
            ToolInvocation.parse(call.name, call.arguments)
        } catch (e: Exception) {
            audit.record(AuditRecord(call.name, "invalid call", null, AuditRecord.Decision.INVALID, false, "$e"))
            return@withLock ToolResult.error("$e")
        }

        val assessment = RiskAssessor.assess(invocation)
        if (assessment.level == RiskLevel.BLOCKED) {
            audit.record(AuditRecord(invocation.toolName, invocation.summary, RiskLevel.BLOCKED,
                AuditRecord.Decision.BLOCKED, false, assessment.reasons.joinToString("; ")))
            return@withLock ToolResult.error(
                "Blocked by safety policy: ${assessment.reasons.joinToString("; ")}. Do not retry or look for a workaround; tell the user.",
                ToolResult.Kind.BLOCKED,
            )
        }

        val current = settings()
        val capability = invocation.armedCapability
        if (capability != null && !current.engagement.armed(capability)) {
            audit.record(AuditRecord(invocation.toolName, invocation.summary, assessment.level,
                AuditRecord.Decision.BLOCKED, false, "capability not armed"))
            return@withLock ToolResult.error(
                "'${invocation.toolName}' is not available: engagement mode is not armed for this capability. " +
                    "Tell the operator to arm it (Settings > Engagement mode, or approve set_engagement_mode) and stop retrying until then.",
            )
        }

        var decision = AuditRecord.Decision.AUTO
        val consent = invocation.requiresExplicitConsent
        if (consent || ApprovalPolicy.requiresApproval(assessment, current, taintedFlag)) {
            val request = ApprovalRequest(
                tool = invocation.toolName, summary = invocation.summary, risk = assessment.level,
                reasons = assessment.reasons, diff = previewDiff(invocation),
                afterUntrustedContent = taintedFlag, isPermissionChange = consent,
            )
            if (!gate.decide(request)) {
                audit.record(AuditRecord(invocation.toolName, invocation.summary, assessment.level,
                    AuditRecord.Decision.DENIED, false, "denied by user"))
                return@withLock ToolResult.error("The user denied this action. Do not repeat it; ask what they want instead.", ToolResult.Kind.DENIED)
            }
            decision = approvedAs
        } else if (current.engagement.active && current.engagement.profile.autoApprovals && assessment.level >= RiskLevel.MEDIUM) {
            decision = AuditRecord.Decision.ENGAGED
        } else if (current.yolo && assessment.level >= RiskLevel.MEDIUM) {
            decision = AuditRecord.Decision.YOLO
        }

        return@withLock try {
            pendingImages = ArrayList()
            val output = run(invocation)
            audit.record(AuditRecord(invocation.toolName, invocation.summary, assessment.level, decision, true, ""))
            val images = pendingImages.toList()
            pendingImages = ArrayList()
            ToolResult.ok(output, images)
        } catch (e: Exception) {
            audit.record(AuditRecord(invocation.toolName, invocation.summary, assessment.level, decision, false, "$e"))
            pendingImages = ArrayList()
            ToolResult.error("$e")
        }
    }

    // MARK: - Execution

    private suspend fun attachScreen(): String {
        val frame = flipper.captureScreen(5.seconds)
        taintedFlag = true
        val png = ScreenRenderer.png(frame) ?: return "Captured the screen, but could not render it."
        pendingImages.add(png)
        val size = frame.displaySize
        return "Screen captured (${size.width}x${size.height} px), attached as an image."
    }

    private suspend fun deviceAPIVersion(): String {
        val info = flipper.deviceInfo()
        return "${info["firmware_api_major"] ?: "0"}.${info["firmware_api_minor"] ?: "0"}"
    }

    private suspend fun findApp(query: String): Triple<CatalogApp, FapHubClient, String> {
        val hub = fapHub ?: throw CatalogError.NotFound(query)
        val api = deviceAPIVersion()
        val results = hub.search(query, api)
        val match = results.firstOrNull { it.alias.equals(query, ignoreCase = true) }
            ?: results.firstOrNull { it.name.equals(query, ignoreCase = true) }
            ?: results.firstOrNull()
            ?: throw CatalogError.NotFound(query)
        return Triple(match, hub, api)
    }

    private suspend fun run(invocation: ToolInvocation): String = when (invocation) {
        is ToolInvocation.GetDeviceInfo -> {
            val info = flipper.deviceInfo()
            val keys = listOf("hardware_name", "hardware_model", "hardware_region_provisioned", "firmware_origin_fork",
                "firmware_version", "firmware_commit", "firmware_branch", "firmware_build_date",
                "protobuf_version_major", "protobuf_version_minor")
            val lines = ArrayList(keys.mapNotNull { key -> info[key]?.let { "$key: ${Untrusted.sanitizeName(it)}" } })
            val pending = runCatching { flipper.customDeviceName() }.getOrNull()
            if (pending != null && pending != info["hardware_name"]) {
                lines.add("pending_name: ${Untrusted.sanitizeName(pending)} (shown after the next restart)")
            }
            lines.joinToString("\n")
        }

        is ToolInvocation.GetPowerInfo -> {
            val info = flipper.powerInfo()
            val keys = listOf("charge_level", "charge_state", "battery_health", "battery_voltage",
                "battery_current", "battery_temp", "capacity_remain", "capacity_full",
                "charge.level", "charge.state", "battery.health")
            val lines = ArrayList(keys.mapNotNull { key -> info[key]?.let { "$key: ${Untrusted.sanitizeName(it)}" } })
            val level = info["charge_level"] ?: info["charge.level"]
            if (level != null) {
                val state = info["charge_state"] ?: info["charge.state"] ?: "unknown"
                lines.add(0, "summary: battery ${Untrusted.sanitizeName(level)}%, ${Untrusted.sanitizeName(state)}")
            }
            if (lines.isEmpty()) {
                info.entries.sortedBy { it.key }.take(12).joinToString("\n") { "${it.key}: ${Untrusted.sanitizeName(it.value)}" }
            } else {
                lines.joinToString("\n")
            }
        }

        is ToolInvocation.GetStorageInfo -> {
            val info = flipper.storageInfo(invocation.path)
            "total: ${info.totalSpace} bytes\nfree: ${info.freeSpace} bytes"
        }

        is ToolInvocation.ListDirectory -> {
            val entries = flipper.list(invocation.path)
            taintedFlag = true
            val lines = entries.take(200).map { e ->
                if (e.isDirectory) "[dir] ${Untrusted.sanitizeName(e.name)}" else "[file] ${Untrusted.sanitizeName(e.name)} (${e.size} bytes)"
            }
            var text = if (lines.isEmpty()) "(empty)" else lines.joinToString("\n")
            if (entries.size > 200) text += "\n… ${entries.size - 200} more entries"
            Untrusted.wrap(text, source = invocation.path, nonce = nonce)
        }

        is ToolInvocation.ReadFile -> {
            val data = flipper.read(invocation.path, MAX_READ_BYTES)
            taintedFlag = true
            val text = if (data.none { it == 0.toByte() }) runCatching { decodeUtf8Strict(data) }.getOrNull() else null
            if (text == null) {
                val head = data.take(32).joinToString(" ") { "%02x".format(it) }
                Untrusted.wrap("binary file, ${data.size} bytes, first bytes: $head", source = invocation.path, nonce = nonce)
            } else {
                Untrusted.wrap(text, source = invocation.path, nonce = nonce)
            }
        }

        is ToolInvocation.CreateDirectory -> { flipper.makeDirectory(invocation.path); "Created folder ${invocation.path}" }
        is ToolInvocation.WriteFile -> {
            flipper.write(invocation.path, invocation.content.toByteArray(Charsets.UTF_8))
            "Wrote ${invocation.content.toByteArray(Charsets.UTF_8).size} bytes to ${invocation.path}"
        }
        is ToolInvocation.Rename -> { flipper.rename(invocation.from, invocation.to); "Moved ${invocation.from} to ${invocation.to}" }
        is ToolInvocation.LaunchApp -> { flipper.startApp(invocation.name, invocation.args); "Started app ${invocation.name}" }
        is ToolInvocation.Delete -> { flipper.delete(invocation.path, invocation.recursive); "Deleted ${invocation.path}" }

        is ToolInvocation.TransmitSubGhz -> {
            flipper.transmitOnce(FlipperApp.SUB_GHZ, invocation.path, "", 400.milliseconds)
            "Transmitted ${invocation.path} once"
        }
        is ToolInvocation.TransmitInfrared -> {
            flipper.transmitOnce(FlipperApp.INFRARED, invocation.path, invocation.button, 250.milliseconds)
            "Sent ${if (invocation.button.isEmpty()) "the remote" else invocation.button} from ${invocation.path}"
        }
        is ToolInvocation.EmulateNfc -> { flipper.emulate(FlipperApp.NFC, invocation.path); "Emulating ${invocation.path}. It keeps running until you call stop_app." }
        is ToolInvocation.EmulateRfid -> { flipper.emulate(FlipperApp.RFID, invocation.path); "Emulating ${invocation.path}. It keeps running until you call stop_app." }
        is ToolInvocation.EmulateIButton -> { flipper.emulate(FlipperApp.IBUTTON, invocation.path); "Emulating ${invocation.path}. It keeps running until you call stop_app." }
        is ToolInvocation.LoadBadKeyboardScript -> {
            flipper.loadBadKeyboardScript(invocation.path)
            "Bad KB is open with ${invocation.path} loaded. Press Run on the Flipper to execute it."
        }
        is ToolInvocation.StopApp -> { flipper.exitApp(); "Closed the app on the Flipper" }

        is ToolInvocation.ForgePayload -> {
            val content = forged.remove(invocation.path) ?: (forge ?: throw ForgeError.Unavailable).forge(invocation.kind, invocation.description)
            flipper.write(invocation.path, content.toByteArray(Charsets.UTF_8))
            "Wrote ${content.toByteArray(Charsets.UTF_8).size} bytes to ${invocation.path}"
        }

        is ToolInvocation.SearchFapHub -> {
            val hub = fapHub ?: throw CatalogError.NotFound(invocation.query)
            val api = deviceAPIVersion()
            val results = hub.search(invocation.query, api)
            if (results.isEmpty()) "No apps found for '${invocation.query}' (firmware SDK $api)"
            else results.joinToString("\n") {
                "${Untrusted.sanitizeName(it.name)} [${it.alias}] v${it.version} - ${Untrusted.sanitizeName(it.summary)}"
            }
        }

        is ToolInvocation.InstallFapHubApp -> {
            val (app, client, api) = findApp(invocation.query)
            val data = downloaded.remove(app.versionID) ?: client.downloadBuild(app.versionID, api)
            val categories = runCatching { client.categories(api) }.getOrDefault(emptyMap())
            val folder = categories[app.categoryID] ?: "Tools"
            val appName = app.alias.ifEmpty { app.name }
            val target = "/ext/apps/$folder/$appName.fap"
            runCatching { flipper.makeDirectory("/ext/apps/$folder") }
            flipper.write(target, data)
            "Installed ${app.name} v${app.version} (${data.size} bytes) to $target"
        }

        is ToolInvocation.SearchGitHub -> {
            val gh = gitHub ?: throw CatalogError.Malformed
            val hits = gh.searchCode(invocation.query, invocation.fileExtension.ifEmpty { null })
            taintedFlag = true
            if (hits.isEmpty()) "Nothing found on GitHub for '${invocation.query}'"
            else Untrusted.wrap(
                hits.joinToString("\n") { "${Untrusted.sanitizeName(it.repository)}: ${Untrusted.sanitizeName(it.path)}\n  ${it.rawURL}" },
                source = "github search", nonce = nonce,
            )
        }

        is ToolInvocation.DownloadResource -> {
            val gh = gitHub ?: throw CatalogError.Malformed
            val data = downloaded.remove(invocation.url) ?: gh.fetchRaw(invocation.url)
            flipper.write(invocation.path, data)
            "Wrote ${data.size} bytes from ${invocation.url} to ${invocation.path}"
        }

        is ToolInvocation.RefreshDeviceKnowledge -> {
            val store = profileStore ?: throw CatalogError.Malformed
            val profile = store.build(flipper)
            onProfileRefreshed?.invoke(profile)
            taintedFlag = true
            profile.promptSummary(nonce)
        }

        is ToolInvocation.CheckFirmware -> {
            val checker = firmwareChecker ?: throw CatalogError.Malformed
            val info = flipper.deviceInfo()
            val status = checker.status(info["firmware_origin_fork"], info["firmware_version"])
            if (status == null) {
                "Unknown firmware distribution (fork ${Untrusted.sanitizeName(info["firmware_origin_fork"] ?: "?")}, version ${Untrusted.sanitizeName(info["firmware_version"] ?: "?")})."
            } else {
                "${status.summary}\nReleases: ${status.distribution.releasesURL}\n${status.distribution.notes}"
            }
        }

        is ToolInvocation.RestartDevice -> {
            (appControls ?: throw CatalogError.Malformed).restartDevice()
            "The Flipper is restarting. The app reconnects on its own; further device actions fail until then."
        }

        is ToolInvocation.GetAuditLog -> {
            val entries = audit.recent(invocation.limit)
            if (entries.isEmpty()) "The audit log is empty."
            else {
                taintedFlag = true
                val text = entries.joinToString("\n") { record ->
                    val time = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US).format(java.util.Date(record.date))
                    "$time ${record.tool} [${record.decision.raw}, ${if (record.succeeded) "ok" else "failed"}] ${Untrusted.sanitizeName(record.summary)}"
                }
                Untrusted.wrap(text, source = "audit log", nonce = nonce)
            }
        }

        is ToolInvocation.GetAppSettings -> {
            val s = (appControls ?: throw CatalogError.Malformed).settings()
            """
            yolo_mode: ${s.yolo}
            yolo_asks_after_reading_device_content: ${s.yoloAsksAfterReading}
            auto_approve_medium: ${s.autoApproveMedium}
            engagement_mode: ${if (s.engagementActive) "armed (${s.engagementSummary})" else "off"}
            read_replies_aloud: ${s.readAloud}
            auto_connect_on_launch: ${s.autoConnect}
            model: ${s.model}
            ai_provider: ${s.provider}
            api_base_url: ${s.apiBaseURL}
            api_key_stored: ${s.apiKeyStored}
            """.trimIndent()
        }

        is ToolInvocation.SetReadAloud -> {
            (appControls ?: throw CatalogError.Malformed).setReadAloud(invocation.enabled)
            if (invocation.enabled) "Replies are read aloud now." else "Replies are no longer read aloud."
        }
        is ToolInvocation.SetAutoConnect -> {
            (appControls ?: throw CatalogError.Malformed).setAutoConnect(invocation.enabled)
            if (invocation.enabled) "The app connects to the last Flipper on launch." else "Auto-connect is off."
        }
        is ToolInvocation.SetYoloMode -> {
            (appControls ?: throw CatalogError.Malformed).setYolo(invocation.enabled)
            if (invocation.enabled) "YOLO mode is on for this session." else "YOLO mode is off; risky actions ask again."
        }
        is ToolInvocation.SetYoloAsksAfterReading -> {
            (appControls ?: throw CatalogError.Malformed).setYoloAsksAfterReading(invocation.enabled)
            if (invocation.enabled) "YOLO mode asks again after device content was read." else "YOLO mode no longer asks after device content was read."
        }
        is ToolInvocation.SetAutoApproveMedium -> {
            (appControls ?: throw CatalogError.Malformed).setAutoApproveMedium(invocation.enabled)
            if (invocation.enabled) "Medium-risk actions no longer ask." else "Medium-risk actions ask again."
        }
        is ToolInvocation.SetModel -> {
            (appControls ?: throw CatalogError.Malformed).setModel(invocation.model)
            "The next chat uses ${invocation.model}."
        }
        is ToolInvocation.SetProvider -> {
            (appControls ?: throw CatalogError.Malformed).setProvider(invocation.provider, invocation.baseURL)
            "The next chat uses ${invocation.provider.displayName}. Previous conversation is not forwarded."
        }
        is ToolInvocation.ListModels -> {
            val models = (appControls ?: throw CatalogError.Malformed).listModels()
            taintedFlag = true
            Untrusted.wrap(models.joinToString("\n") { "${it.id}: ${it.name}" }, source = "provider model catalog", nonce = nonce)
        }
        is ToolInvocation.TestConnection -> {
            (appControls ?: throw CatalogError.Malformed).testConnection()
            "The selected model replied to the test prompt."
        }

        is ToolInvocation.InstallFirmwareUpdate -> (appControls ?: throw CatalogError.Malformed).startFirmwareUpdate()
        is ToolInvocation.FirmwareUpdateStatus -> (appControls ?: throw CatalogError.Malformed).firmwareUpdateStatus()
        is ToolInvocation.CancelFirmwareUpdate -> (appControls ?: throw CatalogError.Malformed).cancelFirmwareUpdate()

        is ToolInvocation.LookAtScreen -> attachScreen()

        is ToolInvocation.PressButtons -> {
            for (step in invocation.keys) {
                flipper.press(step.key, step.long)
                delay(120.milliseconds)
            }
            val pressed = invocation.keys.joinToString(", ") { if (it.long) "long ${it.key.key}" else it.key.key }
            if (!invocation.lookAfter) {
                "Pressed $pressed."
            } else {
                delay(250.milliseconds)
                "Pressed $pressed. " + attachScreen()
            }
        }

        is ToolInvocation.SetDeviceName -> {
            flipper.setDeviceName(invocation.name)
            "Renamed to ${invocation.name}. The Flipper shows the new name after a restart."
        }
        is ToolInvocation.AlertDevice -> { flipper.playAlert(); "The Flipper is beeping and blinking" }

        is ToolInvocation.BadUsbExecute -> {
            // The Bad KB app lands on its work view when opened with a file; the OK input event is the
            // firmware's own start/stop control (bad_usb_scene_work_on_event).
            flipper.loadBadKeyboardScript(invocation.path)
            delay(700.milliseconds)
            flipper.press(com.flipperhero.flipper.FlipperKey.OK, false)
            delay(400.milliseconds)
            val state = attachScreen()
            "Sent Run to Bad KB for ${invocation.path}. $state Check the capture: if the script is not running, use press_buttons to select Run."
        }

        is ToolInvocation.GpioConfigure -> {
            flipper.gpioSetMode(invocation.pin, invocation.output, invocation.pullUp)
            if (invocation.output) {
                "GPIO ${invocation.pin.key} is now an output."
            } else {
                val pull = invocation.pullUp?.let { if (it) "pull-up" else "pull-down" } ?: "no pull"
                "GPIO ${invocation.pin.key} is now an input with $pull."
            }
        }
        is ToolInvocation.GpioRead -> {
            val level = flipper.gpioRead(invocation.pin)
            "GPIO ${invocation.pin.key} reads ${if (level) "high" else "low"}."
        }
        is ToolInvocation.GpioWrite -> {
            flipper.gpioWrite(invocation.pin, invocation.level)
            "GPIO ${invocation.pin.key} is driven ${if (invocation.level) "high" else "low"}."
        }

        is ToolInvocation.RawRpc -> {
            val response = flipper.rawRpc(invocation.request)
            taintedFlag = true
            Untrusted.wrap(response, source = "raw rpc", nonce = nonce)
        }

        is ToolInvocation.SetEngagementMode -> {
            val controls = appControls ?: throw CatalogError.Malformed
            val state = EngagementState(invocation.enabled, invocation.profile, if (invocation.enabled) System.currentTimeMillis() else null)
            controls.setEngagement(state)
            if (invocation.enabled) {
                "Engagement mode is armed (${invocation.profile.summary}). " +
                    (if (invocation.profile.note.isEmpty()) "" else "Scope: ${invocation.profile.note}. ") +
                    "The banner shows it and everything is audited."
            } else {
                "Engagement mode is off. Normal approval rules apply again."
            }
        }

        is ToolInvocation.GenerateEngagementReport -> {
            val records = audit.recent(invocation.limit)
            taintedFlag = true
            val report = EngagementReport.markdown(records, settings().engagement)
            Untrusted.wrap(report, source = "engagement report", nonce = nonce)
        }
    }

    private suspend fun previewDiff(invocation: ToolInvocation): String? = when (invocation) {
        is ToolInvocation.WriteFile -> {
            val info = runCatching { flipper.stat(invocation.path) }.getOrNull()
            val old = if (info != null && !info.isDirectory && info.size <= MAX_READ_BYTES) {
                runCatching { flipper.read(invocation.path, MAX_READ_BYTES) }.getOrNull()
                    ?.let { runCatching { decodeUtf8Strict(it) }.getOrNull() }
            } else null
            SimpleDiff.render(old, invocation.content)
        }
        is ToolInvocation.InstallFapHubApp -> {
            val found = runCatching { findApp(invocation.query) }.getOrNull() ?: return null
            val (app, client, api) = found
            val data = runCatching { client.downloadBuild(app.versionID, api) }.getOrNull() ?: return null
            downloaded[app.versionID] = data
            "${app.name} v${app.version} by the official catalog\n${app.summary}\n${data.size} bytes"
        }
        is ToolInvocation.DownloadResource -> {
            val gh = gitHub ?: return null
            val data = runCatching { gh.fetchRaw(invocation.url) }.getOrNull() ?: return null
            downloaded[invocation.url] = data
            val text = if (data.none { it == 0.toByte() }) runCatching { decodeUtf8Strict(data) }.getOrNull() else null
            if (text != null) SimpleDiff.render(null, text, maxLines = 40) else "Binary file, ${data.size} bytes"
        }
        is ToolInvocation.ForgePayload -> {
            val f = forge ?: return null
            val content = runCatching { f.forge(invocation.kind, invocation.description) }.getOrNull() ?: return null
            forged[invocation.path] = content
            val existing = runCatching { flipper.read(invocation.path, MAX_READ_BYTES) }.getOrNull()
                ?.let { runCatching { decodeUtf8Strict(it) }.getOrNull() }
            SimpleDiff.render(existing, content, maxLines = 60)
        }
        is ToolInvocation.Delete -> {
            val info = runCatching { flipper.stat(invocation.path) }.getOrNull() ?: return null
            if (info.isDirectory) "Folder ${Untrusted.sanitizeName(info.name)}"
            else "File ${Untrusted.sanitizeName(info.name)}, ${info.size} bytes"
        }
        else -> null
    }

    companion object {
        const val MAX_READ_BYTES = 16 * 1024

        /** Decodes strictly as UTF-8, throwing on malformed input (so binary files fall through). */
        private fun decodeUtf8Strict(data: ByteArray): String {
            val decoder = Charsets.UTF_8.newDecoder()
            return decoder.decode(java.nio.ByteBuffer.wrap(data)).toString()
        }
    }
}
