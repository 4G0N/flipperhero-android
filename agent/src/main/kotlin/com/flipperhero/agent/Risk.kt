package com.flipperhero.agent

import com.flipperhero.flipper.FlipperException
import com.flipperhero.flipper.FlipperPath

enum class RiskLevel { LOW, MEDIUM, HIGH, BLOCKED }

data class RiskAssessment(val level: RiskLevel, val reasons: List<String>)

/** Deterministic, model-independent risk classification. Mirrors Swift `RiskAssessor`. */
object RiskAssessor {
    /** Internal flash holds pairing keys and system state: never touched by the agent. */
    private val blockedRoots = listOf("/int")
    private val sensitiveExtensions = setOf("key", "pem", "priv", "secret", "p12")

    /** Places where a written file can later execute or reconfigure the device. */
    private val executableRoots = listOf("/ext/apps", "/ext/update", "/ext/badusb", "/ext/apps_data/js_app")
    private val executableExtensions = setOf("fap", "js", "dfu", "bin", "elf", "tgz", "fuf")

    fun assess(invocation: ToolInvocation): RiskAssessment = when (invocation) {
        is ToolInvocation.GetDeviceInfo, is ToolInvocation.GetPowerInfo ->
            RiskAssessment(RiskLevel.LOW, listOf(L("read-only")))
        is ToolInvocation.GetStorageInfo -> pathRead(invocation.path)
        is ToolInvocation.ListDirectory -> pathRead(invocation.path)
        is ToolInvocation.ReadFile -> {
            val base = pathRead(invocation.path)
            if (base.level != RiskLevel.BLOCKED && sensitiveExtensions.contains(ext(invocation.path))) {
                RiskAssessment(RiskLevel.BLOCKED, listOf(L("reading key or secret files is not allowed")))
            } else {
                base
            }
        }
        is ToolInvocation.CreateDirectory -> pathWrite(invocation.path, RiskLevel.MEDIUM, L("creates a folder"))
        is ToolInvocation.WriteFile -> pathWrite(invocation.path, RiskLevel.MEDIUM, L("writes a file"))
        is ToolInvocation.Rename -> combine(
            pathWrite(invocation.from, RiskLevel.MEDIUM, L("moves a file")),
            pathWrite(invocation.to, RiskLevel.MEDIUM, L("moves a file")),
        )
        is ToolInvocation.LaunchApp ->
            if (invocation.args.isNotEmpty()) {
                RiskAssessment(RiskLevel.HIGH, listOf(L("app '${invocation.name}' receives an argument (it may run or transmit a file)")))
            } else {
                RiskAssessment(RiskLevel.MEDIUM, listOf(L("starts an app on the device")))
            }
        is ToolInvocation.TransmitSubGhz -> physical(invocation.path, L("transmits a radio signal"))
        is ToolInvocation.TransmitInfrared -> physical(invocation.path, L("sends an infrared command"))
        is ToolInvocation.EmulateNfc -> physical(invocation.path, L("emulates this card to any nearby reader"))
        is ToolInvocation.EmulateRfid -> physical(invocation.path, L("emulates this 125 kHz tag to any nearby reader"))
        is ToolInvocation.EmulateIButton -> physical(invocation.path, L("emulates this iButton key"))
        is ToolInvocation.LoadBadKeyboardScript ->
            physical(invocation.path, L("loads a keystroke-injection script (you still start it on the Flipper)"))
        is ToolInvocation.StopApp -> RiskAssessment(RiskLevel.LOW, listOf(L("closes the app running on the Flipper")))
        is ToolInvocation.InstallFirmwareUpdate ->
            RiskAssessment(RiskLevel.HIGH, listOf(L("replaces the firmware; takes 15 to 25 minutes and the Flipper restarts")))
        is ToolInvocation.FirmwareUpdateStatus -> RiskAssessment(RiskLevel.LOW, listOf(L("read-only")))
        is ToolInvocation.CancelFirmwareUpdate ->
            RiskAssessment(RiskLevel.LOW, listOf(L("stops an update before anything is installed")))
        is ToolInvocation.LookAtScreen -> RiskAssessment(RiskLevel.LOW, listOf(L("takes a screenshot of the Flipper")))
        is ToolInvocation.PressButtons ->
            RiskAssessment(RiskLevel.HIGH, listOf(L("presses buttons in whatever app is open, which can start transmissions or delete data")))
        is ToolInvocation.CheckFirmware, is ToolInvocation.GetAuditLog, is ToolInvocation.GetAppSettings, is ToolInvocation.ListModels ->
            RiskAssessment(RiskLevel.LOW, listOf(L("read-only")))
        is ToolInvocation.SetReadAloud, is ToolInvocation.SetAutoConnect ->
            RiskAssessment(RiskLevel.LOW, listOf(L("changes a harmless app preference")))
        is ToolInvocation.RestartDevice ->
            RiskAssessment(RiskLevel.MEDIUM, listOf(L("restarts the Flipper; whatever runs on it stops and the connection drops briefly")))
        is ToolInvocation.SetModel, is ToolInvocation.SetProvider ->
            RiskAssessment(RiskLevel.MEDIUM, listOf(L("changes which model provider receives your conversation and file contents")))
        is ToolInvocation.TestConnection ->
            RiskAssessment(RiskLevel.MEDIUM, listOf(L("sends a paid test message to your model provider")))
        is ToolInvocation.SetYoloMode ->
            if (invocation.enabled) RiskAssessment(RiskLevel.HIGH, listOf(L("the agent could then change, delete and transmit without asking you")))
            else RiskAssessment(RiskLevel.LOW, listOf(L("makes the agent ask again")))
        is ToolInvocation.SetAutoApproveMedium ->
            if (invocation.enabled) RiskAssessment(RiskLevel.HIGH, listOf(L("medium-risk changes would no longer ask you")))
            else RiskAssessment(RiskLevel.LOW, listOf(L("makes the agent ask again")))
        is ToolInvocation.SetYoloAsksAfterReading ->
            if (invocation.enabled) RiskAssessment(RiskLevel.LOW, listOf(L("makes the agent ask again after reading device content")))
            else RiskAssessment(RiskLevel.HIGH, listOf(L("removes the protection against instructions planted in files on the Flipper")))
        is ToolInvocation.SetDeviceName ->
            RiskAssessment(RiskLevel.MEDIUM, listOf(L("changes the device name and its Bluetooth identity")))
        is ToolInvocation.RefreshDeviceKnowledge -> RiskAssessment(RiskLevel.LOW, listOf(L("reads the device inventory")))
        is ToolInvocation.SearchFapHub, is ToolInvocation.SearchGitHub ->
            RiskAssessment(RiskLevel.LOW, listOf(L("searches the internet, changes nothing")))
        is ToolInvocation.InstallFapHubApp ->
            RiskAssessment(RiskLevel.HIGH, listOf(L("installs an app '${invocation.query}' that can run code on the device")))
        is ToolInvocation.DownloadResource ->
            pathWrite(invocation.path, RiskLevel.MEDIUM, L("writes a file downloaded from the internet"))
        is ToolInvocation.ForgePayload -> pathWrite(invocation.path, RiskLevel.MEDIUM, L("writes a generated file"))
        is ToolInvocation.AlertDevice -> RiskAssessment(RiskLevel.LOW, listOf(L("beeps and blinks so you can find the device")))
        is ToolInvocation.Delete -> {
            val n = tryNormalize(invocation.path) ?: return RiskAssessment(RiskLevel.BLOCKED, listOf(L("invalid path")))
            if (isBlocked(n)) {
                RiskAssessment(RiskLevel.BLOCKED, listOf(L("protected path")))
            } else {
                val depth = n.split("/").count { it.isNotEmpty() }
                if (invocation.recursive && depth <= 2) {
                    RiskAssessment(RiskLevel.BLOCKED, listOf(L("refusing recursive delete of a top-level folder")))
                } else {
                    RiskAssessment(RiskLevel.HIGH, listOf(if (invocation.recursive) L("deletes a folder and its contents") else L("deletes data")))
                }
            }
        }
        is ToolInvocation.BadUsbExecute ->
            physical(invocation.path, L("types a keystroke-injection script into the connected machine on its own"))
        is ToolInvocation.GpioConfigure ->
            RiskAssessment(RiskLevel.HIGH, listOf(
                if (invocation.output) L("drives GPIO pin ${invocation.pin.key} from the expansion header")
                else L("reconfigures GPIO pin ${invocation.pin.key} on the expansion header"),
            ))
        is ToolInvocation.GpioRead -> RiskAssessment(RiskLevel.LOW, listOf(L("reads GPIO pin ${invocation.pin.key}, changes nothing")))
        is ToolInvocation.GpioWrite ->
            RiskAssessment(RiskLevel.HIGH, listOf(L("puts ${if (invocation.level) "3.3 V" else "0 V"} on GPIO pin ${invocation.pin.key}")))
        is ToolInvocation.RawRpc ->
            RiskAssessment(RiskLevel.HIGH, listOf(L("sends a raw device command that bypasses the normal tool checks")))
        is ToolInvocation.SetEngagementMode -> {
            if (!invocation.enabled) {
                RiskAssessment(RiskLevel.LOW, listOf(L("ends engagement mode; the agent asks again")))
            } else {
                val reasons = buildList {
                    add(L("the agent runs actions without asking for the rest of the session"))
                    if (invocation.profile.rawRPC) add(L("raw device commands can reach anything the firmware exposes"))
                    if (invocation.profile.autoBadKB) add(L("Bad KB scripts start without anyone pressing Run"))
                }
                RiskAssessment(RiskLevel.HIGH, reasons)
            }
        }
        is ToolInvocation.GenerateEngagementReport -> RiskAssessment(RiskLevel.LOW, listOf(L("reads the audit log")))
    }

    // helpers

    private fun ext(path: String): String = (path.substringAfterLast('.', "")).lowercase()

    private fun tryNormalize(path: String): String? = try {
        FlipperPath.normalize(path)
    } catch (e: FlipperException) {
        null
    }

    private fun isBlocked(normalized: String): Boolean =
        blockedRoots.any { normalized == it || normalized.startsWith("$it/") }

    private fun under(normalized: String, roots: List<String>): Boolean =
        roots.any { normalized == it || normalized.startsWith("$it/") }

    private fun invalid(): RiskAssessment = RiskAssessment(RiskLevel.BLOCKED, listOf(L("invalid path")))

    private fun pathRead(path: String): RiskAssessment {
        val n = tryNormalize(path) ?: return invalid()
        if (isBlocked(n)) return RiskAssessment(RiskLevel.BLOCKED, listOf(L("protected path (internal storage)")))
        return RiskAssessment(RiskLevel.LOW, listOf(L("read-only")))
    }

    private fun pathWrite(path: String, base: RiskLevel, why: String): RiskAssessment {
        val n = tryNormalize(path) ?: return invalid()
        if (isBlocked(n)) return RiskAssessment(RiskLevel.BLOCKED, listOf(L("protected path (internal storage)")))
        var level = base
        val reasons = ArrayList<String>()
        reasons.add(why)
        if (under(n, executableRoots) || executableExtensions.contains(ext(n))) {
            level = RiskLevel.HIGH
            reasons.add(L("target can execute code or reconfigure the device"))
        }
        val comps = n.split("/").filter { it.isNotEmpty() }
        if (comps.size == 2 && comps[1].startsWith(".")) {
            level = RiskLevel.HIGH
            reasons.add(L("hidden configuration file at the SD card root"))
        }
        return RiskAssessment(level, reasons)
    }

    private fun physical(path: String, why: String): RiskAssessment {
        val n = tryNormalize(path) ?: return invalid()
        if (isBlocked(n)) return RiskAssessment(RiskLevel.BLOCKED, listOf(L("protected path (internal storage)")))
        return RiskAssessment(RiskLevel.HIGH, listOf(why))
    }

    private fun combine(a: RiskAssessment, b: RiskAssessment): RiskAssessment =
        RiskAssessment(maxOf(a.level, b.level), (a.reasons + b.reasons).toSortedSet().toList())
}
