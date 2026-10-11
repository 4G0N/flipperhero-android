package com.flipperhero.agent

import com.flipperhero.flipper.FlipperGpioPin
import com.flipperhero.flipper.FlipperKey

sealed class ToolError(message: String) : Exception(message) {
    data class UnknownTool(val name: String) : ToolError("Unknown tool '$name'")
    data class BadArguments(val why: String) : ToolError("Invalid arguments: $why")
}

/** One button press for `press_buttons`, parsed from "ok" or "long_back". Mirrors Swift `ButtonStep`. */
data class ButtonStep(val key: FlipperKey, val long: Boolean = false) {
    companion object {
        const val MAX_PER_CALL = 12
        fun parse(text: String): ButtonStep? {
            var name = text.lowercase().trim()
            val long = name.startsWith("long_") || name.startsWith("long ")
            if (long) name = name.drop(5)
            val key = FlipperKey.fromKey(name) ?: return null
            return ButtonStep(key, long)
        }
    }
}

/** A validated, typed request coming from the model. Mirrors Swift `ToolInvocation`. */
sealed class ToolInvocation {
    data class ListDirectory(val path: String) : ToolInvocation()
    data class ReadFile(val path: String) : ToolInvocation()
    object GetDeviceInfo : ToolInvocation()
    object GetPowerInfo : ToolInvocation()
    data class GetStorageInfo(val path: String) : ToolInvocation()
    data class CreateDirectory(val path: String) : ToolInvocation()
    data class WriteFile(val path: String, val content: String) : ToolInvocation()
    data class Rename(val from: String, val to: String) : ToolInvocation()
    data class LaunchApp(val name: String, val args: String) : ToolInvocation()
    data class Delete(val path: String, val recursive: Boolean) : ToolInvocation()
    data class TransmitSubGhz(val path: String) : ToolInvocation()
    data class TransmitInfrared(val path: String, val button: String) : ToolInvocation()
    data class EmulateNfc(val path: String) : ToolInvocation()
    data class EmulateRfid(val path: String) : ToolInvocation()
    data class EmulateIButton(val path: String) : ToolInvocation()
    data class LoadBadKeyboardScript(val path: String) : ToolInvocation()
    object StopApp : ToolInvocation()
    object AlertDevice : ToolInvocation()
    data class ForgePayload(val kind: PayloadKind, val description: String, val path: String) : ToolInvocation()
    data class SearchFapHub(val query: String) : ToolInvocation()
    data class InstallFapHubApp(val query: String) : ToolInvocation()
    data class SearchGitHub(val query: String, val fileExtension: String) : ToolInvocation()
    data class DownloadResource(val url: String, val path: String) : ToolInvocation()
    object RefreshDeviceKnowledge : ToolInvocation()
    data class SetDeviceName(val name: String) : ToolInvocation()
    object CheckFirmware : ToolInvocation()
    object RestartDevice : ToolInvocation()
    data class GetAuditLog(val limit: Int) : ToolInvocation()
    object GetAppSettings : ToolInvocation()
    data class SetReadAloud(val enabled: Boolean) : ToolInvocation()
    data class SetAutoConnect(val enabled: Boolean) : ToolInvocation()
    data class SetYoloMode(val enabled: Boolean) : ToolInvocation()
    data class SetYoloAsksAfterReading(val enabled: Boolean) : ToolInvocation()
    data class SetAutoApproveMedium(val enabled: Boolean) : ToolInvocation()
    data class SetModel(val model: String) : ToolInvocation()
    data class SetProvider(val provider: AIProvider, val baseURL: String?) : ToolInvocation()
    object ListModels : ToolInvocation()
    object TestConnection : ToolInvocation()
    object LookAtScreen : ToolInvocation()
    object InstallFirmwareUpdate : ToolInvocation()
    object FirmwareUpdateStatus : ToolInvocation()
    object CancelFirmwareUpdate : ToolInvocation()
    data class PressButtons(val keys: List<ButtonStep>, val lookAfter: Boolean) : ToolInvocation()
    data class BadUsbExecute(val path: String) : ToolInvocation()
    data class GpioConfigure(val pin: FlipperGpioPin, val output: Boolean, val pullUp: Boolean?) : ToolInvocation()
    data class GpioRead(val pin: FlipperGpioPin) : ToolInvocation()
    data class GpioWrite(val pin: FlipperGpioPin, val level: Boolean) : ToolInvocation()
    data class RawRpc(val request: String) : ToolInvocation()
    data class SetEngagementMode(val enabled: Boolean, val profile: EngagementProfile) : ToolInvocation()
    data class GenerateEngagementReport(val limit: Int) : ToolInvocation()

    val toolName: String
        get() = when (this) {
            is ListDirectory -> "list_directory"
            is ReadFile -> "read_file"
            is GetDeviceInfo -> "get_device_info"
            is GetPowerInfo -> "get_power_info"
            is GetStorageInfo -> "get_storage_info"
            is CreateDirectory -> "create_directory"
            is WriteFile -> "write_file"
            is Rename -> "rename"
            is LaunchApp -> "launch_app"
            is Delete -> "delete"
            is TransmitSubGhz -> "transmit_subghz"
            is TransmitInfrared -> "transmit_infrared"
            is EmulateNfc -> "emulate_nfc"
            is EmulateRfid -> "emulate_rfid"
            is EmulateIButton -> "emulate_ibutton"
            is LoadBadKeyboardScript -> "load_badusb_script"
            is StopApp -> "stop_app"
            is AlertDevice -> "alert_device"
            is ForgePayload -> "forge_payload"
            is SearchFapHub -> "search_faphub"
            is InstallFapHubApp -> "install_faphub_app"
            is SearchGitHub -> "github_search"
            is DownloadResource -> "download_resource"
            is RefreshDeviceKnowledge -> "refresh_device_knowledge"
            is SetDeviceName -> "set_device_name"
            is CheckFirmware -> "check_firmware"
            is RestartDevice -> "restart_device"
            is GetAuditLog -> "get_audit_log"
            is GetAppSettings -> "get_app_settings"
            is SetReadAloud -> "set_read_aloud"
            is SetAutoConnect -> "set_auto_connect"
            is SetYoloMode -> "set_yolo_mode"
            is SetYoloAsksAfterReading -> "set_yolo_asks_after_reading"
            is SetAutoApproveMedium -> "set_auto_approve_medium"
            is SetModel -> "set_model"
            is SetProvider -> "set_ai_provider"
            is ListModels -> "list_models"
            is TestConnection -> "test_model_connection"
            is LookAtScreen -> "look_at_screen"
            is InstallFirmwareUpdate -> "install_firmware_update"
            is FirmwareUpdateStatus -> "firmware_update_status"
            is CancelFirmwareUpdate -> "cancel_firmware_update"
            is PressButtons -> "press_buttons"
            is BadUsbExecute -> "badusb_execute"
            is GpioConfigure, is GpioRead, is GpioWrite -> "gpio"
            is RawRpc -> "rpc_raw"
            is SetEngagementMode -> "set_engagement_mode"
            is GenerateEngagementReport -> "generate_engagement_report"
        }

    /** Engagement capability this tool needs armed, if any. Checked by the executor. */
    val armedCapability: EngagementCapability?
        get() = when (this) {
            is BadUsbExecute -> EngagementCapability.AUTO_BAD_KB
            is RawRpc -> EngagementCapability.RAW_RPC
            else -> null
        }

    /** Loosening the agent's own permissions always shows a permission dialog. */
    val requiresExplicitConsent: Boolean
        get() = when (this) {
            is SetYoloMode -> enabled
            is SetAutoApproveMedium -> enabled
            is SetYoloAsksAfterReading -> !enabled
            is SetEngagementMode -> enabled
            else -> false
        }

    /** One-line, human-readable description used for approval prompts and the audit log. */
    val summary: String
        get() = when (this) {
            is ListDirectory -> L("List $path")
            is ReadFile -> L("Read $path")
            is GetDeviceInfo -> L("Read device info")
            is GetPowerInfo -> L("Read battery info")
            is GetStorageInfo -> L("Read storage info for $path")
            is CreateDirectory -> L("Create folder $path")
            is WriteFile -> L("Write ${content.toByteArray(Charsets.UTF_8).size} bytes to $path")
            is Rename -> L("Move $from to $to")
            is LaunchApp -> if (args.isEmpty()) L("Launch app $name") else L("Launch app $name with argument $args")
            is Delete -> if (recursive) L("Delete $path and everything inside") else L("Delete $path")
            is TransmitSubGhz -> L("Transmit the radio signal $path")
            is TransmitInfrared -> if (button.isEmpty()) L("Send the infrared remote $path") else L("Send infrared button '$button' from $path")
            is EmulateNfc -> L("Emulate the NFC card $path")
            is EmulateRfid -> L("Emulate the 125 kHz tag $path")
            is EmulateIButton -> L("Emulate the iButton key $path")
            is LoadBadKeyboardScript -> L("Open Bad KB with the script $path loaded (not started)")
            is StopApp -> L("Close the app running on the Flipper")
            is AlertDevice -> L("Make the Flipper beep and blink")
            is ForgePayload -> L("Generate a ${kind.id} file from \"$description\" and write it to $path")
            is SearchFapHub -> L("Search the Flipper app catalog for \"$query\"")
            is InstallFapHubApp -> L("Download and install the app \"$query\" from the official catalog")
            is SearchGitHub -> if (fileExtension.isEmpty()) L("Search GitHub for \"$query\"") else L("Search GitHub for \"$query\" (.$fileExtension files)")
            is DownloadResource -> L("Download $url to $path")
            is RefreshDeviceKnowledge -> L("Re-read what is installed and saved on the Flipper")
            is SetDeviceName -> L("Rename the Flipper to \"$name\" (applies after a restart)")
            is CheckFirmware -> L("Check the firmware for updates")
            is RestartDevice -> L("Restart the Flipper")
            is GetAuditLog -> L("Show the last $limit actions from the audit log")
            is GetAppSettings -> L("Read the app settings")
            is SetReadAloud -> if (enabled) L("Read replies aloud") else L("Stop reading replies aloud")
            is SetAutoConnect -> if (enabled) L("Connect to the last Flipper on launch") else L("Stop connecting automatically on launch")
            is SetYoloMode -> if (enabled) L("Turn on YOLO mode: changes, deletes and transmissions run without asking") else L("Turn off YOLO mode")
            is SetYoloAsksAfterReading -> if (enabled) L("Ask again after reading Flipper content, even in YOLO mode") else L("Stop asking after reading Flipper content in YOLO mode")
            is SetAutoApproveMedium -> if (enabled) L("Skip prompts for medium-risk actions") else L("Ask again for medium-risk actions")
            is SetModel -> L("Switch the model to $model")
            is SetProvider -> L("Switch the AI provider to ${provider.displayName}")
            is ListModels -> L("Choose model")
            is TestConnection -> L("Test connection")
            is LookAtScreen -> L("Look at the Flipper's screen")
            is InstallFirmwareUpdate -> L("Download and install the newest firmware release on the Flipper")
            is FirmwareUpdateStatus -> L("Check how the firmware update is going")
            is CancelFirmwareUpdate -> L("Cancel the running firmware update")
            is PressButtons -> pressSummary(keys)
            is BadUsbExecute -> L("Start the Bad KB script $path on the Flipper now")
            is GpioConfigure -> if (output) L("Set GPIO pin ${pin.key} to output") else L("Set GPIO pin ${pin.key} to input")
            is GpioRead -> L("Read the level of GPIO pin ${pin.key}")
            is GpioWrite -> L("Drive GPIO pin ${pin.key} ${if (level) "high" else "low"}")
            is RawRpc -> L("Send the raw device command ${rawCommandName(request)}")
            is SetEngagementMode ->
                if (enabled) L("Arm engagement mode (${profile.summary})${if (profile.note.isEmpty()) "" else ": ${profile.note}"}")
                else L("End engagement mode")
            is GenerateEngagementReport -> L("Compile the last $limit audit entries into an engagement report")
        }

    companion object {
        const val MAX_WRITE_BYTES = 32 * 1024

        private fun pressSummary(keys: List<ButtonStep>): String {
            val list = keys.joinToString(", ") { if (it.long) L("long ${it.key.key}") else it.key.key }
            return L("Press $list on the Flipper")
        }

        /** First protobuf message name inside a raw request's content, for the audit line. */
        private fun rawCommandName(request: String): String {
            val json = runCatching { JsonValue.parse(request) }.getOrNull() ?: return "request"
            val content = json["content"] as? JsonValue.Obj ?: return "request"
            return content.value.keys.sorted().firstOrNull() ?: "request"
        }

        operator fun invoke(name: String, arguments: String): ToolInvocation = parse(name, arguments)

        fun parse(name: String, arguments: String): ToolInvocation {
            val args = runCatching { JsonValue.parse(if (arguments.isEmpty()) "{}" else arguments) }.getOrNull()
            if (args !is JsonValue.Obj) throw ToolError.BadArguments("arguments are not a JSON object")

            fun bool(key: String): Boolean = args[key]?.boolValue ?: throw ToolError.BadArguments("missing boolean '$key'")
            fun string(key: String, optional: Boolean = false): String {
                args[key]?.stringValue?.let { return it }
                if (optional) return ""
                throw ToolError.BadArguments("missing string '$key'")
            }
            fun intOrNull(key: String): Int? = args[key]?.numberValue?.toInt()

            return when (name) {
                "list_directory" -> ListDirectory(string("path"))
                "read_file" -> ReadFile(string("path"))
                "get_device_info" -> GetDeviceInfo
                "get_power_info" -> GetPowerInfo
                "get_storage_info" -> string("path", optional = true).let { GetStorageInfo(if (it.isEmpty()) "/ext" else it) }
                "create_directory" -> CreateDirectory(string("path"))
                "write_file" -> {
                    val content = string("content")
                    if (content.toByteArray(Charsets.UTF_8).size > MAX_WRITE_BYTES) {
                        throw ToolError.BadArguments("content larger than $MAX_WRITE_BYTES bytes")
                    }
                    WriteFile(string("path"), content)
                }
                "rename", "move" -> Rename(string("from"), string("to"))
                "launch_app" -> {
                    val appName = string("name")
                    if (appName.isEmpty() || appName.length > 128 || appName.any { it.code < 0x20 || it.code == 0x7F }) {
                        throw ToolError.BadArguments("invalid app name")
                    }
                    LaunchApp(appName, string("args", optional = true))
                }
                "delete" -> Delete(string("path"), args["recursive"]?.boolValue ?: false)
                "transmit_subghz" -> TransmitSubGhz(string("path"))
                "transmit_infrared" -> TransmitInfrared(string("path"), string("button", optional = true))
                "emulate_nfc" -> EmulateNfc(string("path"))
                "emulate_rfid" -> EmulateRfid(string("path"))
                "emulate_ibutton" -> EmulateIButton(string("path"))
                "load_badusb_script" -> LoadBadKeyboardScript(string("path"))
                "stop_app" -> StopApp
                "alert_device" -> AlertDevice
                "search_faphub" -> SearchFapHub(string("query"))
                "install_faphub_app" -> InstallFapHubApp(string("query"))
                "github_search" -> SearchGitHub(string("query"), string("extension", optional = true))
                "refresh_device_knowledge" -> RefreshDeviceKnowledge
                "set_device_name" -> SetDeviceName(string("name"))
                "check_firmware" -> CheckFirmware
                "look_at_screen" -> LookAtScreen
                "install_firmware_update" -> InstallFirmwareUpdate
                "firmware_update_status" -> FirmwareUpdateStatus
                "cancel_firmware_update" -> CancelFirmwareUpdate
                "press_buttons" -> {
                    val raw = (args["keys"] as? JsonValue.Arr)?.value
                        ?: throw ToolError.BadArguments("keys must be a non-empty array")
                    if (raw.isEmpty()) throw ToolError.BadArguments("keys must be a non-empty array")
                    if (raw.size > ButtonStep.MAX_PER_CALL) throw ToolError.BadArguments("at most ${ButtonStep.MAX_PER_CALL} presses per call")
                    val steps = raw.map { value ->
                        val text = value.stringValue
                        val step = text?.let { ButtonStep.parse(it) }
                        step ?: throw ToolError.BadArguments("unknown key '${text ?: "?"}'")
                    }
                    PressButtons(steps, args["look_after"]?.boolValue ?: true)
                }
                "restart_device" -> RestartDevice
                "get_audit_log" -> GetAuditLog((intOrNull("limit") ?: 10).coerceIn(1, 50))
                "get_app_settings" -> GetAppSettings
                "set_read_aloud" -> SetReadAloud(bool("enabled"))
                "set_auto_connect" -> SetAutoConnect(bool("enabled"))
                "set_yolo_mode" -> SetYoloMode(bool("enabled"))
                "set_yolo_asks_after_reading" -> SetYoloAsksAfterReading(bool("enabled"))
                "set_auto_approve_medium" -> SetAutoApproveMedium(bool("enabled"))
                "set_model" -> {
                    val model = string("model").trim()
                    if (model.isEmpty() || model.length > 120 ||
                        !model.all { it.isLetterOrDigit() || it in "/-_.:" }
                    ) {
                        throw ToolError.BadArguments("invalid model id")
                    }
                    SetModel(model)
                }
                "set_ai_provider" -> {
                    val provider = AIProvider.fromId(string("provider")) ?: throw ToolError.BadArguments("unknown AI provider")
                    val baseURL = args["base_url"]?.stringValue
                    if (baseURL != null) provider.validatedBaseURL(baseURL)
                    SetProvider(provider, baseURL)
                }
                "list_models" -> ListModels
                "test_model_connection" -> TestConnection
                "download_resource" -> DownloadResource(string("url"), string("path"))
                "forge_payload" -> {
                    val raw = string("type")
                    val kind = PayloadKind.fromId(raw.lowercase()) ?: throw ToolError.BadArguments("unknown payload type '$raw'")
                    val desc = string("description")
                    var target = string("path", optional = true)
                    if (target.isEmpty()) throw ToolError.BadArguments("missing target 'path'")
                    if (!target.lowercase().endsWith("." + kind.fileExtension)) target += "." + kind.fileExtension
                    ForgePayload(kind, desc, target)
                }
                "badusb_execute" -> BadUsbExecute(string("path"))
                "gpio" -> {
                    fun pin(): FlipperGpioPin {
                        val raw = string("pin").lowercase()
                        return FlipperGpioPin.fromKey(raw)
                            ?: throw ToolError.BadArguments("unknown pin '$raw' (one of: ${FlipperGpioPin.entries.joinToString(", ") { it.key }})")
                    }
                    when (string("action").lowercase()) {
                        "configure" -> {
                            val output = (args["direction"]?.stringValue ?: "output").lowercase() != "input"
                            val pull = args["pull"]?.stringValue?.lowercase()
                            GpioConfigure(pin(), output, if (pull == "up") true else if (pull == "down") false else null)
                        }
                        "read" -> GpioRead(pin())
                        "write" -> {
                            val raw = args["value"]?.stringValue
                            val level = when {
                                raw != null -> raw == "1" || raw.lowercase() == "high"
                                else -> args["value"]?.boolValue ?: throw ToolError.BadArguments("write needs a 'value' of 0/1 or low/high")
                            }
                            GpioWrite(pin(), level)
                        }
                        else -> throw ToolError.BadArguments("action must be configure, read or write")
                    }
                }
                "rpc_raw" -> {
                    val request = string("request")
                    if (request.toByteArray(Charsets.UTF_8).size > 16 * 1024) throw ToolError.BadArguments("request larger than 16 KB")
                    RawRpc(request)
                }
                "set_engagement_mode" -> {
                    val enabled = bool("enabled")
                    val profile = EngagementProfile(
                        autoApprovals = args["auto_approvals"]?.boolValue ?: true,
                        rawRPC = args["raw_rpc"]?.boolValue ?: false,
                        autoBadKB = args["auto_badusb"]?.boolValue ?: false,
                        note = string("note", optional = true),
                    )
                    SetEngagementMode(enabled, profile)
                }
                "generate_engagement_report" -> GenerateEngagementReport((intOrNull("limit") ?: 200).coerceIn(1, 400))
                else -> throw ToolError.UnknownTool(name)
            }
        }
    }
}

data class ToolSpec(val name: String, val description: String, val parameters: JsonValue)

object ToolCatalog {
    /** Payload types forge_payload offers (OSS edition includes Bad KB). */
    private const val FORGE_PAYLOAD_TYPES = "badusb, subghz, infrared, nfc, rfid, ibutton"

    private fun obj(props: Map<String, Pair<String, String>>, required: List<String>): JsonValue {
        val properties = LinkedHashMap<String, JsonValue>()
        for ((key, pair) in props) {
            properties[key] = JsonValue.obj("type" to JsonValue.str(pair.first), "description" to JsonValue.str(pair.second))
        }
        return JsonValue.obj(
            "type" to JsonValue.str("object"),
            "properties" to JsonValue.Obj(properties),
            "required" to JsonValue.arr(required.map { JsonValue.str(it) }),
            "additionalProperties" to JsonValue.bool(false),
        )
    }

    val specs: List<ToolSpec> = buildList {
        add(ToolSpec("list_directory", "List files and folders at an absolute Flipper path such as /ext/nfc.",
            obj(mapOf("path" to ("string" to "Absolute path under /ext")), listOf("path"))))
        add(ToolSpec("read_file", "Read a small text file (max 16 KB). Binary files return only a summary.",
            obj(mapOf("path" to ("string" to "Absolute file path")), listOf("path"))))
        add(ToolSpec("get_device_info", "Hardware name, firmware version and commit of the connected Flipper.",
            obj(emptyMap(), emptyList())))
        add(ToolSpec("get_power_info", "Battery charge and charging state.", obj(emptyMap(), emptyList())))
        add(ToolSpec("get_storage_info", "Total and free space of the SD card (/ext).",
            obj(mapOf("path" to ("string" to "Storage root, default /ext")), emptyList())))
        add(ToolSpec("create_directory", "Create a folder. Requires user approval.",
            obj(mapOf("path" to ("string" to "Absolute folder path")), listOf("path"))))
        add(ToolSpec("write_file", "Create or overwrite a text file (max 32 KB). Requires user approval and shows a diff.",
            obj(mapOf("path" to ("string" to "Absolute file path"), "content" to ("string" to "Full new file content")), listOf("path", "content"))))
        add(ToolSpec("rename", "Move or rename a file or folder. Requires user approval.",
            obj(mapOf("from" to ("string" to "Existing absolute path"), "to" to ("string" to "New absolute path")), listOf("from", "to"))))
        add(ToolSpec("launch_app", "Start an app on the Flipper by its name. Passing an argument always needs explicit user approval.",
            obj(mapOf("name" to ("string" to "App name, e.g. Sub-GHz"), "args" to ("string" to "Optional app argument")), listOf("name"))))
        add(ToolSpec("delete", "Delete a file or folder. Always requires explicit user approval.",
            obj(mapOf("path" to ("string" to "Absolute path"), "recursive" to ("boolean" to "Delete folder contents too")), listOf("path"))))
        add(ToolSpec("transmit_subghz", "Transmit a saved .sub radio signal once. Real-world effect, always needs user approval.",
            obj(mapOf("path" to ("string" to "Absolute path to a .sub file")), listOf("path"))))
        add(ToolSpec("transmit_infrared", "Send a saved .ir remote command once. Real-world effect, always needs user approval.",
            obj(mapOf("path" to ("string" to "Absolute path to an .ir file"), "button" to ("string" to "Button name inside the remote, e.g. Power")), listOf("path"))))
        add(ToolSpec("emulate_nfc", "Emulate a saved .nfc card until stopped. Real-world effect, always needs user approval.",
            obj(mapOf("path" to ("string" to "Absolute path to a .nfc file")), listOf("path"))))
        add(ToolSpec("emulate_rfid", "Emulate a saved .rfid tag until stopped. Real-world effect, always needs user approval.",
            obj(mapOf("path" to ("string" to "Absolute path to a .rfid file")), listOf("path"))))
        add(ToolSpec("emulate_ibutton", "Emulate a saved .ibtn key until stopped. Real-world effect, always needs user approval.",
            obj(mapOf("path" to ("string" to "Absolute path to an .ibtn file")), listOf("path"))))
        add(ToolSpec("load_badusb_script", "Open Bad KB with a script loaded. It is NOT started; the user still presses Run on the Flipper itself.",
            obj(mapOf("path" to ("string" to "Absolute path to a .txt script")), listOf("path"))))
        add(ToolSpec("stop_app", "Close whatever app is currently running on the Flipper, which also stops emulation.",
            obj(emptyMap(), emptyList())))
        add(ToolSpec("alert_device", "Make the Flipper beep, blink and vibrate so the user can find it.", obj(emptyMap(), emptyList())))
        add(ToolSpec("forge_payload", "Generate a Flipper file ($FORGE_PAYLOAD_TYPES) from a description and write it to the Flipper. The full generated content is shown to the user for approval before anything is written.",
            obj(mapOf(
                "type" to ("string" to "One of: badusb, subghz, infrared, nfc, rfid, ibutton"),
                "description" to ("string" to "What the file should do, in plain language"),
                "path" to ("string" to "Absolute target path, e.g. /ext/badusb/demo.txt"),
            ), listOf("type", "description", "path"))))
        add(ToolSpec("search_faphub", "Search the official Flipper app catalog. Only returns apps built for the firmware the connected device is running.",
            obj(mapOf("query" to ("string" to "What to search for")), listOf("query"))))
        add(ToolSpec("install_faphub_app", "Download an app from the official catalog and install it on the Flipper. Installs executable code, so it always needs user approval.",
            obj(mapOf("query" to ("string" to "App name or alias, as returned by search_faphub")), listOf("query"))))
        add(ToolSpec("github_search", "Search GitHub for Flipper-compatible files, for example .sub or .ir remotes. Returns repository, path and a raw URL.",
            obj(mapOf("query" to ("string" to "Search terms"), "extension" to ("string" to "Restrict to a file extension, e.g. ir or sub")), listOf("query"))))
        add(ToolSpec("download_resource", "Download a raw file from GitHub and write it to the Flipper. The content is shown to the user before anything is written.",
            obj(mapOf("url" to ("string" to "Raw GitHub URL from github_search"), "path" to ("string" to "Absolute target path on the Flipper")), listOf("url", "path"))))
        add(ToolSpec("refresh_device_knowledge", "Re-read the Flipper's firmware, installed apps and saved files. Use when the inventory looks stale or something you expected is missing.",
            obj(emptyMap(), emptyList())))
        add(ToolSpec("set_device_name", "Rename the Flipper. At most 8 characters, letters, digits, hyphen and underscore. Takes effect after the device restarts.",
            obj(mapOf("name" to ("string" to "New device name, max 8 characters")), listOf("name"))))
        add(ToolSpec("check_firmware", "Identify the installed firmware distribution and look up its newest release.", obj(emptyMap(), emptyList())))
        add(ToolSpec("restart_device", "Restart the Flipper, for example to apply a new name. The connection drops and the app reconnects.", obj(emptyMap(), emptyList())))
        add(ToolSpec("get_audit_log", "List the most recent actions taken in this app, with the approval decision for each.",
            obj(mapOf("limit" to ("integer" to "How many entries, 1 to 50, default 10")), emptyList())))
        add(ToolSpec("get_app_settings", "Read the app settings: YOLO mode, approval switches, read-aloud, auto-connect, model, and whether an API key is stored.",
            obj(emptyMap(), emptyList())))
        add(ToolSpec("set_read_aloud", "Turn reading replies aloud on or off.",
            obj(mapOf("enabled" to ("boolean" to "true to read replies aloud")), listOf("enabled"))))
        add(ToolSpec("set_auto_connect", "Turn connecting to the last Flipper on app launch on or off.",
            obj(mapOf("enabled" to ("boolean" to "true to auto-connect")), listOf("enabled"))))
        add(ToolSpec("set_yolo_mode", "Turn YOLO mode on or off. Turning it on always shows the user a permission dialog; you cannot enable it yourself. Only offer it when the user asks about it or about fewer prompts.",
            obj(mapOf("enabled" to ("boolean" to "true to request YOLO mode")), listOf("enabled"))))
        add(ToolSpec("set_yolo_asks_after_reading", "Whether YOLO mode still asks after you read content from the Flipper. Turning this off always needs the user's permission.",
            obj(mapOf("enabled" to ("boolean" to "true to keep asking after reading device content")), listOf("enabled"))))
        add(ToolSpec("set_auto_approve_medium", "Skip prompts for medium-risk actions. Turning it on always needs the user's permission.",
            obj(mapOf("enabled" to ("boolean" to "true to skip medium-risk prompts")), listOf("enabled"))))
        add(ToolSpec("set_model", "Switch the model used for the next chat. Use an ID from list_models or provider documentation.",
            obj(mapOf("model" to ("string" to "Provider model ID")), listOf("model"))))
        add(ToolSpec("set_ai_provider", "Select the AI provider for the next chat: openrouter, ai2342, blackbit, orcarouter, zai, kimi, qwen, minimax. Restores its saved model and key. Optional base_url must be an HTTPS API base on that provider's own domain. Never reads or writes keys.",
            obj(mapOf("provider" to ("string" to "Provider ID"), "base_url" to ("string" to "Optional regional API base URL")), listOf("provider"))))
        add(ToolSpec("list_models", "Fetch the selected provider's model catalog using its stored key. Credentials are never returned.", obj(emptyMap(), emptyList())))
        add(ToolSpec("test_model_connection", "Send a tiny fixed OK prompt to the selected model using the stored key. May incur provider charges. Sends no conversation or device data.", obj(emptyMap(), emptyList())))
        add(ToolSpec("install_firmware_update", "Download the newest release of the installed firmware distribution and install it over Bluetooth. Runs in the background for 15 to 25 minutes; the Flipper restarts at the end. Check check_firmware first and tell the user what will be installed.", obj(emptyMap(), emptyList())))
        add(ToolSpec("firmware_update_status", "Progress of a running firmware update, or the result of the last one.", obj(emptyMap(), emptyList())))
        add(ToolSpec("cancel_firmware_update", "Cancel a running firmware update. Works while downloading or uploading; once the Flipper restarts into its updater it finishes on its own.", obj(emptyMap(), emptyList())))
        add(ToolSpec("look_at_screen", "Capture the Flipper's 128x64 screen and look at it as an image. Use it to see which app or menu is open before pressing buttons.", obj(emptyMap(), emptyList())))
        add(ToolSpec("press_buttons",
            "Press buttons on the Flipper like a finger would, to operate any app or menu. Keys: up, down, left, right, ok, back; prefix with long_ for a long press (long_back exits apps). Returns a screen capture afterwards unless look_after is false.",
            JsonValue.obj(
                "type" to JsonValue.str("object"),
                "properties" to JsonValue.obj(
                    "keys" to JsonValue.obj("type" to JsonValue.str("array"), "items" to JsonValue.obj("type" to JsonValue.str("string")),
                        "description" to JsonValue.str("Sequence, e.g. [\"down\", \"down\", \"ok\"], at most 12")),
                    "look_after" to JsonValue.obj("type" to JsonValue.str("boolean"), "description" to JsonValue.str("Capture the screen afterwards, default true")),
                ),
                "required" to JsonValue.arr(listOf(JsonValue.str("keys"))),
                "additionalProperties" to JsonValue.bool(false),
            )))
        // OSS edition: red-team capabilities.
        add(ToolSpec("badusb_execute", "Start a Bad KB script immediately, without anyone pressing Run on the Flipper. Only available after the operator armed engagement mode with auto_badusb; ask them to arm it first. Keystrokes go to whatever machine the Flipper is plugged into.",
            obj(mapOf("path" to ("string" to "Absolute path to a .txt script, e.g. from forge_payload or write_file")), listOf("path"))))
        add(ToolSpec("gpio", "Use the GPIO pins on the Flipper's expansion header. Actions: configure (direction input/output, optional pull up/down for inputs), read (level of an input pin), write (drive an output pin high/low). Real-world effect on whatever is wired to the pin.",
            obj(mapOf(
                "action" to ("string" to "configure, read or write"),
                "pin" to ("string" to "One of: pc0, pc1, pc3, pb2, pb3, pa4, pa6, pa7"),
                "direction" to ("string" to "For configure: output (default) or input"),
                "pull" to ("string" to "For configure with input: up, down or none (default)"),
                "value" to ("string" to "For write: 1/high or 0/low"),
            ), listOf("action", "pin"))))
        add(ToolSpec("rpc_raw", "Send any command from the device's protobuf surface and get the JSON responses back. Request body is a PB_Main JSON with a content field, e.g. {\"content\":{\"systemPingRequest\":{}}}. Only available after the operator armed engagement mode with raw_rpc; ask them to arm it first. It bypasses the path and file protections of the normal tools.",
            obj(mapOf("request" to ("string" to "PB_Main JSON with exactly one content entry, at most 16 KB")), listOf("request"))))
        add(ToolSpec("set_engagement_mode", "Arm or end engagement mode. Arming always shows the operator a permission dialog; you cannot arm it yourself, only request it. Capabilities: auto_approvals (actions run without per-action prompts), raw_rpc (unlocks rpc_raw), auto_badusb (unlocks badusb_execute). The note is the operator's scope note and is shown on the banner.",
            obj(mapOf(
                "enabled" to ("boolean" to "true to request arming, false to disarm (disarming needs no dialog)"),
                "auto_approvals" to ("boolean" to "Skip per-action prompts, default true"),
                "raw_rpc" to ("boolean" to "Unlock raw device commands, default false"),
                "auto_badusb" to ("boolean" to "Unlock starting Bad KB scripts, default false"),
                "note" to ("string" to "Short scope note for the banner, e.g. the engagement name"),
            ), listOf("enabled"))))
        add(ToolSpec("generate_engagement_report", "Compile recent audit log entries into a markdown engagement report: every action with time, tool, risk, decision and outcome. Use it at the end of an engagement, or when the operator asks for a timeline or report.",
            obj(mapOf("limit" to ("integer" to "How many entries, 1 to 400, default 200")), emptyList())))
    }
}
