package com.flipperhero.flipper

import com.flipperdevices.protobuf.Main
import com.flipperdevices.protobuf.app.AppButtonPressRequest
import com.flipperdevices.protobuf.app.AppButtonReleaseRequest
import com.flipperdevices.protobuf.app.AppExitRequest
import com.flipperdevices.protobuf.app.AppLoadFileRequest
import com.flipperdevices.protobuf.app.GetErrorRequest
import com.flipperdevices.protobuf.app.StartRequest
import com.flipperdevices.protobuf.gpio.GpioInputPull
import com.flipperdevices.protobuf.gpio.GpioPinMode
import com.flipperdevices.protobuf.gpio.ReadPin
import com.flipperdevices.protobuf.gpio.SetInputPull
import com.flipperdevices.protobuf.gpio.SetPinMode
import com.flipperdevices.protobuf.gpio.WritePin
import com.flipperdevices.protobuf.screen.InputType
import com.flipperdevices.protobuf.screen.SendInputEventRequest
import com.flipperdevices.protobuf.storage.DeleteRequest
import com.flipperdevices.protobuf.storage.File
import com.flipperdevices.protobuf.storage.InfoRequest
import com.flipperdevices.protobuf.storage.ListRequest
import com.flipperdevices.protobuf.storage.MkdirRequest
import com.flipperdevices.protobuf.storage.ReadRequest
import com.flipperdevices.protobuf.storage.RenameRequest
import com.flipperdevices.protobuf.storage.StatRequest
import com.flipperdevices.protobuf.storage.WriteRequest
import com.flipperdevices.protobuf.system.DeviceInfoRequest
import com.flipperdevices.protobuf.system.PingRequest
import com.flipperdevices.protobuf.system.PlayAudiovisualAlertRequest
import com.flipperdevices.protobuf.system.PowerInfoRequest
import com.flipperdevices.protobuf.system.RebootRequest
import com.flipperdevices.protobuf.system.UpdateRequest
import com.flipperdevices.protobuf.system.UpdateResponse
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// MARK: - System / storage (FlipperAPI.swift)

suspend fun FlipperRpcClient.ping(payload: ByteArray = byteArrayOf(1, 2, 3, 4)) {
    val parts = call { setSystemPingRequest(PingRequest.newBuilder().setData(payload.toByteString())) }
    val first = parts.firstOrNull()
    if (first == null || first.contentCase != Main.ContentCase.SYSTEM_PING_RESPONSE ||
        !first.systemPingResponse.data.toByteArray().contentEquals(payload)
    ) {
        throw FlipperException.UnexpectedResponse
    }
}

suspend fun FlipperRpcClient.deviceInfo(): Map<String, String> {
    val parts = call { setSystemDeviceInfoRequest(DeviceInfoRequest.getDefaultInstance()) }
    val info = LinkedHashMap<String, String>()
    for (part in parts) {
        if (part.contentCase == Main.ContentCase.SYSTEM_DEVICE_INFO_RESPONSE) {
            info[part.systemDeviceInfoResponse.key] = part.systemDeviceInfoResponse.value
        }
    }
    return info
}

suspend fun FlipperRpcClient.powerInfo(): Map<String, String> {
    val parts = call { setSystemPowerInfoRequest(PowerInfoRequest.getDefaultInstance()) }
    val info = LinkedHashMap<String, String>()
    for (part in parts) {
        if (part.contentCase == Main.ContentCase.SYSTEM_POWER_INFO_RESPONSE) {
            info[part.systemPowerInfoResponse.key] = part.systemPowerInfoResponse.value
        }
    }
    return info
}

suspend fun FlipperRpcClient.storageInfo(path: String = "/ext"): FlipperStorageInfo {
    val normalized = FlipperPath.normalize(path)
    val parts = call { setStorageInfoRequest(InfoRequest.newBuilder().setPath(normalized)) }
    val response = parts.firstOrNull()?.takeIf { it.contentCase == Main.ContentCase.STORAGE_INFO_RESPONSE }
        ?.storageInfoResponse ?: throw FlipperException.UnexpectedResponse
    return FlipperStorageInfo(totalSpace = response.totalSpace, freeSpace = response.freeSpace)
}

suspend fun FlipperRpcClient.list(path: String): List<FlipperDirEntry> {
    val normalized = FlipperPath.normalize(path)
    val parts = call { setStorageListRequest(ListRequest.newBuilder().setPath(normalized)) }
    val entries = ArrayList<FlipperDirEntry>()
    for (part in parts) {
        if (part.contentCase == Main.ContentCase.STORAGE_LIST_RESPONSE) {
            for (file in part.storageListResponse.fileList) {
                entries.add(FlipperDirEntry(file.name, file.type == File.FileType.DIR, file.size))
            }
        }
    }
    return entries.sortedWith(compareByDescending<FlipperDirEntry> { it.isDirectory }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}

suspend fun FlipperRpcClient.stat(path: String): FlipperDirEntry {
    val normalized = FlipperPath.normalize(path)
    val parts = call { setStorageStatRequest(StatRequest.newBuilder().setPath(normalized)) }
    val response = parts.firstOrNull()?.takeIf { it.contentCase == Main.ContentCase.STORAGE_STAT_RESPONSE }
        ?.storageStatResponse ?: throw FlipperException.UnexpectedResponse
    return FlipperDirEntry(
        name = FlipperPath.lastComponent(normalized),
        isDirectory = response.file.type == File.FileType.DIR,
        size = response.file.size,
    )
}

/** Refuses files larger than [maxBytes] (checked via stat before any data is transferred). */
suspend fun FlipperRpcClient.read(path: String, maxBytes: Int = FlipperLimits.DEFAULT_MAX_READ_BYTES): ByteArray {
    val normalized = FlipperPath.normalize(path)
    val info = stat(normalized)
    if (info.isDirectory) throw FlipperException.InvalidPath("is a directory")
    if (info.size > maxBytes) throw FlipperException.Rpc("file is ${info.size} bytes, limit is $maxBytes")
    val parts = call(timeout = 60.seconds) { setStorageReadRequest(ReadRequest.newBuilder().setPath(normalized)) }
    val out = ByteArrayOutputStream()
    for (part in parts) {
        if (part.contentCase == Main.ContentCase.STORAGE_READ_RESPONSE) {
            out.write(part.storageReadResponse.file.data.toByteArray())
        }
    }
    return out.toByteArray()
}

suspend fun FlipperRpcClient.write(path: String, data: ByteArray, progress: ((Int, Int) -> Unit)? = null) {
    val normalized = FlipperPath.normalize(path)
    val size = FlipperLimits.WRITE_CHUNK_SIZE
    val contents = ArrayList<RpcContent>()
    var offset = 0
    do {
        val end = minOf(offset + size, data.size)
        val chunk = data.copyOfRange(offset, end)
        contents.add {
            setStorageWriteRequest(
                WriteRequest.newBuilder()
                    .setPath(normalized)
                    .setFile(File.newBuilder().setData(chunk.toByteString())),
            )
        }
        offset = end
    } while (offset < data.size)
    val seconds = 60 + data.size / 2048
    val total = data.size
    call(contents, timeout = seconds.seconds, progress = progress?.let { report ->
        { sent, _ -> report(minOf(sent * size, total), total) }
    })
}

/** Creates [path] and any missing parents. Existing folders are fine. */
suspend fun FlipperRpcClient.makeDirectories(path: String) {
    val normalized = FlipperPath.normalize(path)
    var current = ""
    for (component in normalized.split("/").filter { it.isNotEmpty() }) {
        current += "/$component"
        if (current.split("/").count { it.isNotEmpty() } <= 1) continue // /ext itself always exists
        try {
            makeDirectory(current)
        } catch (e: FlipperException.Rpc) {
            if (e.status.contains("exist", ignoreCase = true)) continue
            throw e
        }
    }
}

suspend fun FlipperRpcClient.makeDirectory(path: String) {
    val normalized = FlipperPath.normalize(path)
    call { setStorageMkdirRequest(MkdirRequest.newBuilder().setPath(normalized)) }
}

suspend fun FlipperRpcClient.delete(path: String, recursive: Boolean = false) {
    val normalized = FlipperPath.normalize(path)
    call { setStorageDeleteRequest(DeleteRequest.newBuilder().setPath(normalized).setRecursive(recursive)) }
}

suspend fun FlipperRpcClient.rename(from: String, to: String) {
    val oldPath = FlipperPath.normalize(from)
    val newPath = FlipperPath.normalize(to)
    call { setStorageRenameRequest(RenameRequest.newBuilder().setOldPath(oldPath).setNewPath(newPath)) }
}

suspend fun FlipperRpcClient.startApp(name: String, args: String = "") {
    call { setAppStartRequest(StartRequest.newBuilder().setName(name).setArgs(args)) }
}

// MARK: - App control (FlipperDevice.swift)

/** Opens [app] in RPC mode and loads [path]. The caller must call [exitApp] afterwards. */
private suspend fun FlipperRpcClient.openInRpcMode(app: FlipperApp, path: String) {
    val normalized = FlipperPath.normalize(path)
    if (!normalized.lowercase().endsWith("." + app.fileExtension)) {
        throw FlipperException.InvalidPath("${app.appName} expects a .${app.fileExtension} file")
    }
    stat(normalized) // fails early if the file is missing
    call(timeout = 30.seconds) { setAppStartRequest(StartRequest.newBuilder().setName(app.appName).setArgs("RPC")) }
    try {
        call(timeout = 30.seconds) { setAppLoadFileRequest(AppLoadFileRequest.newBuilder().setPath(normalized)) }
    } catch (e: Throwable) {
        runCatching { exitApp() }
        throw e
    }
}

suspend fun FlipperRpcClient.exitApp() {
    call(timeout = 15.seconds) { setAppExitRequest(AppExitRequest.getDefaultInstance()) }
}

/** Loads a file and emulates it until [exitApp]. Used for NFC, RFID and iButton. */
suspend fun FlipperRpcClient.emulate(app: FlipperApp, path: String) {
    openInRpcMode(app, path)
}

/** Loads a signal and transmits it once (press, short hold, release), then closes the app. */
suspend fun FlipperRpcClient.transmitOnce(
    app: FlipperApp,
    path: String,
    button: String = "",
    hold: Duration = 400.milliseconds,
) {
    openInRpcMode(app, path)
    try {
        call(timeout = 20.seconds) { setAppButtonPressRequest(AppButtonPressRequest.newBuilder().setArgs(button)) }
        delay(hold)
        call(timeout = 20.seconds) { setAppButtonReleaseRequest(AppButtonReleaseRequest.getDefaultInstance()) }
    } catch (e: Throwable) {
        runCatching { exitApp() }
        throw e
    }
    exitApp()
}

/**
 * Opens Bad KB with a script loaded. The script is NOT started: the firmware still requires a
 * press on the Flipper itself, which we deliberately do not automate.
 */
suspend fun FlipperRpcClient.loadBadKeyboardScript(path: String) {
    val normalized = FlipperPath.normalize(path)
    stat(normalized)
    call(timeout = 30.seconds) {
        setAppStartRequest(StartRequest.newBuilder().setName(FlipperApp.BAD_KEYBOARD.appName).setArgs(normalized))
    }
}

/** Last error reported by the running app, if any. */
suspend fun FlipperRpcClient.appError(): String? {
    val parts = call { setAppGetErrorRequest(GetErrorRequest.getDefaultInstance()) }
    val response = parts.firstOrNull()?.takeIf { it.contentCase == Main.ContentCase.APP_GET_ERROR_RESPONSE }
        ?.appGetErrorResponse ?: return null
    if (response.code == 0) return null
    return response.text.ifEmpty { "error code ${response.code}" }
}

/** Beeps, blinks and vibrates so the user can locate the device. */
suspend fun FlipperRpcClient.playAlert() {
    call { setSystemPlayAudiovisualAlertRequest(PlayAudiovisualAlertRequest.getDefaultInstance()) }
}

/**
 * Restarts into the given mode. The device drops the link while answering, so a lost connection
 * or timeout here means the request was received.
 */
suspend fun FlipperRpcClient.reboot(mode: RebootRequest.RebootMode = RebootRequest.RebootMode.OS) {
    try {
        call(timeout = 5.seconds) { setSystemRebootRequest(RebootRequest.newBuilder().setMode(mode)) }
    } catch (e: FlipperException.Timeout) {
        // expected: the link drops while the firmware answers
    } catch (e: FlipperException.NotConnected) {
        // expected
    }
}

suspend fun FlipperRpcClient.rebootIntoUpdater() = reboot(RebootRequest.RebootMode.UPDATE)

/** Asks the firmware to validate and stage an update package already on the SD card. */
suspend fun FlipperRpcClient.requestUpdate(manifestPath: String): FlipperUpdateResult {
    val normalized = FlipperPath.normalize(manifestPath)
    val parts = call(timeout = 60.seconds) { setSystemUpdateRequest(UpdateRequest.newBuilder().setUpdateManifest(normalized)) }
    val response = parts.firstOrNull()?.takeIf { it.contentCase == Main.ContentCase.SYSTEM_UPDATE_RESPONSE }
        ?.systemUpdateResponse ?: throw FlipperException.UnexpectedResponse
    return if (response.code == UpdateResponse.UpdateResultCode.OK) FlipperUpdateResult.Ok
    else FlipperUpdateResult.Rejected(response.code.name)
}

// MARK: - Screen / input (FlipperScreen.swift)

/** Presses a button the way a finger does: press, short or long, release. */
suspend fun FlipperRpcClient.press(key: FlipperKey, long: Boolean = false) {
    val types = listOf(InputType.PRESS, if (long) InputType.LONG else InputType.SHORT, InputType.RELEASE)
    val events: List<RpcContent> = types.map { type ->
        {
            setGuiSendInputEventRequest(SendInputEventRequest.newBuilder().setKey(key.proto).setType(type))
        }
    }
    callPipelined(events)
}

// MARK: - Device name (DeviceName.swift)

/** Custom name currently set on the SD card, if any. */
suspend fun FlipperRpcClient.customDeviceName(): String? {
    val data = runCatching { read(FlipperName.PATH, maxBytes = 1024) }.getOrNull() ?: return null
    val text = runCatching { String(data, Charsets.UTF_8) }.getOrNull() ?: return null
    return FlipperName.parse(text)
}

/** Writes the name file. The Flipper applies it on the next reboot. */
suspend fun FlipperRpcClient.setDeviceName(name: String) {
    val valid = FlipperName.validate(name)
    runCatching { makeDirectory("/ext/dolphin") }
    write(FlipperName.PATH, FlipperName.fileContents(valid).toByteArray(Charsets.UTF_8))
}

// MARK: - GPIO (FlipperGPIO.swift)

suspend fun FlipperRpcClient.gpioSetMode(pin: FlipperGpioPin, output: Boolean, pullUp: Boolean? = null) {
    if (output) {
        call { setGpioSetPinMode(SetPinMode.newBuilder().setPin(pin.proto).setMode(GpioPinMode.OUTPUT)) }
    } else {
        call { setGpioSetPinMode(SetPinMode.newBuilder().setPin(pin.proto).setMode(GpioPinMode.INPUT)) }
        val pull = when (pullUp) {
            true -> GpioInputPull.UP
            false -> GpioInputPull.DOWN
            null -> GpioInputPull.NO
        }
        call { setGpioSetInputPull(SetInputPull.newBuilder().setPin(pin.proto).setPullMode(pull)) }
    }
}

suspend fun FlipperRpcClient.gpioRead(pin: FlipperGpioPin): Boolean {
    val parts = call { setGpioReadPin(ReadPin.newBuilder().setPin(pin.proto)) }
    val response = parts.firstOrNull()?.takeIf { it.contentCase == Main.ContentCase.GPIO_READ_PIN_RESPONSE }
        ?.gpioReadPinResponse ?: throw FlipperException.UnexpectedResponse
    return response.value != 0
}

suspend fun FlipperRpcClient.gpioWrite(pin: FlipperGpioPin, level: Boolean) {
    call { setGpioWritePin(WritePin.newBuilder().setPin(pin.proto).setValue(if (level) 1 else 0)) }
}
