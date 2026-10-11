package com.flipperhero.agent

import com.flipperhero.flipper.FlipperPath
import com.google.gson.Gson
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the agent knows about the connected Flipper without going looking every turn: firmware,
 * installed apps and what is saved on the SD card. Mirrors Swift `DeviceProfile`.
 */
data class DeviceProfile(
    val deviceName: String = "",
    val firmware: String = "",
    val firmwareCommit: String = "",
    val apiVersion: String = "",
    val hardwareUID: String = "",
    val freeSpaceBytes: Long = 0,
    val totalSpaceBytes: Long = 0,
    /** Installed external apps as "Category/Name". */
    val apps: List<String> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val updated: Long = System.currentTimeMillis(),
) {
    data class Folder(val path: String, val fileCount: Int, val samples: List<String>)

    val isEmpty: Boolean get() = firmware.isEmpty() && apps.isEmpty() && folders.isEmpty()

    /** Compact, fenced summary for the system prompt. Device-sourced names are sanitized and wrapped. */
    fun promptSummary(nonce: String): String {
        if (isEmpty) return ""
        val lines = ArrayList<String>()
        lines.add("Device: ${Untrusted.sanitizeName(deviceName)}, firmware ${Untrusted.sanitizeName(firmware)} ($firmwareCommit), SDK $apiVersion")
        if (totalSpaceBytes > 0) {
            lines.add("SD card: ${humanBytes(freeSpaceBytes)} free of ${humanBytes(totalSpaceBytes)}")
        }
        if (apps.isNotEmpty()) {
            lines.add("Installed apps (${apps.size}): " + apps.joinToString(", ") { Untrusted.sanitizeName(it) })
        }
        for (folder in folders) {
            if (folder.fileCount <= 0) continue
            val names = folder.samples.joinToString(", ") { Untrusted.sanitizeName(it) }
            val more = if (folder.fileCount > folder.samples.size) ", +${folder.fileCount - folder.samples.size} more" else ""
            lines.add("${folder.path} (${folder.fileCount}): $names$more")
        }
        val stamp = SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(updated))
        lines.add("Snapshot taken $stamp; it can be out of date, verify before acting on it.")
        return Untrusted.wrap(lines.joinToString("\n"), source = "device inventory", nonce = nonce)
    }

    companion object {
        val scanned = listOf("/ext/subghz", "/ext/nfc", "/ext/lfrfid", "/ext/infrared", "/ext/ibutton", "/ext/badusb")
        const val MAX_APPS = 60
        const val MAX_SAMPLES_PER_FOLDER = 12

        private fun humanBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes bytes"
            val units = listOf("KB", "MB", "GB", "TB")
            var value = bytes.toDouble() / 1024
            var unit = 0
            while (value >= 1024 && unit < units.size - 1) { value /= 1024; unit++ }
            return String.format(Locale.US, "%.1f %s", value, units[unit])
        }
    }
}

/** Builds and caches a [DeviceProfile], keyed by hardware UID. Mirrors Swift `DeviceProfileStore`. */
class DeviceProfileStore(private val directory: File) {
    private val gson = Gson()
    private val cache = HashMap<String, DeviceProfile>()

    init {
        directory.mkdirs()
    }

    private fun file(uid: String): File = File(directory, "profile-${uid.ifEmpty { "unknown" }}.json")

    fun cached(uid: String): DeviceProfile? {
        cache[uid]?.let { return it }
        val f = file(uid)
        if (!f.exists()) return null
        return runCatching { gson.fromJson(f.readText(), DeviceProfile::class.java) }.getOrNull()?.also { cache[uid] = it }
    }

    fun store(profile: DeviceProfile) {
        cache[profile.hardwareUID] = profile
        runCatching { file(profile.hardwareUID).writeText(gson.toJson(profile)) }
    }

    /** Reads the device. Individual steps may fail so a missing folder does not cost the whole profile. */
    suspend fun build(from: FlipperControlling): DeviceProfile {
        var profile = DeviceProfile()
        runCatching { from.deviceInfo() }.getOrNull()?.let { info ->
            profile = profile.copy(
                deviceName = info["hardware_name"] ?: "",
                hardwareUID = info["hardware_uid"] ?: "",
                firmwareCommit = info["firmware_commit"] ?: "",
                apiVersion = "${info["firmware_api_major"] ?: "0"}.${info["firmware_api_minor"] ?: "0"}",
                firmware = listOfNotNull(info["firmware_origin_fork"], info["firmware_version"])
                    .filter { it.isNotEmpty() }.joinToString(" "),
            )
        }
        runCatching { from.storageInfo("/ext") }.getOrNull()?.let {
            profile = profile.copy(freeSpaceBytes = it.freeSpace, totalSpaceBytes = it.totalSpace)
        }
        val apps = ArrayList<String>()
        runCatching { from.list("/ext/apps") }.getOrNull()?.let { categories ->
            for (category in categories) {
                if (!category.isDirectory) continue
                if (apps.size >= DeviceProfile.MAX_APPS) break
                val path = FlipperPath.join("/ext/apps", category.name)
                val entries = runCatching { from.list(path) }.getOrNull() ?: continue
                for (entry in entries) {
                    if (!entry.name.lowercase().endsWith(".fap")) continue
                    if (apps.size >= DeviceProfile.MAX_APPS) break
                    apps.add("${category.name}/${entry.name.removeSuffix(".fap")}")
                }
            }
        }
        val folders = ArrayList<DeviceProfile.Folder>()
        for (path in DeviceProfile.scanned) {
            val entries = runCatching { from.list(path) }.getOrNull() ?: continue
            val files = entries.filter { !it.isDirectory }
            val dirs = entries.filter { it.isDirectory }
            val samples = ArrayList(files.take(DeviceProfile.MAX_SAMPLES_PER_FOLDER).map { it.name })
            if (samples.size < DeviceProfile.MAX_SAMPLES_PER_FOLDER) {
                samples += dirs.take(DeviceProfile.MAX_SAMPLES_PER_FOLDER - samples.size).map { it.name + "/" }
            }
            folders.add(DeviceProfile.Folder(path, entries.size, samples))
        }
        profile = profile.copy(apps = apps, folders = folders, updated = System.currentTimeMillis())
        store(profile)
        return profile
    }
}
