package com.flipperhero.agent

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Well-known Flipper firmware distributions, recognised from `firmware_origin_fork`. */
data class FirmwareDistribution(val name: String, val repo: String, val notes: String) {
    val releasesURL: String get() = "https://github.com/$repo/releases"
}

object FirmwareCatalog {
    val known: List<FirmwareDistribution> = listOf(
        FirmwareDistribution("Momentum", "Next-Flip/Momentum-Firmware", L("Feature-rich fork with asset packs and extra apps")),
        FirmwareDistribution("Unleashed", "DarkFlippers/unleashed-firmware", L("Region-unlocked fork, large protocol set")),
        FirmwareDistribution("RogueMaster", "RogueMaster/flipperzero-firmware-wPlugins", L("Unleashed-based, ships a very large app bundle")),
        FirmwareDistribution("Xtreme", "Flipper-XFW/Xtreme-Firmware", L("Predecessor of Momentum, archived")),
        FirmwareDistribution("Official", "flipperdevices/flipperzero-firmware", L("Stock firmware from Flipper Devices")),
    )

    /** Matches `firmware_origin_fork` (and falls back to the version string) against the catalog. */
    fun identify(fork: String?, version: String?): FirmwareDistribution? {
        val haystack = listOfNotNull(fork, version).joinToString(" ").lowercase()
        if (haystack.isEmpty()) return null
        known.firstOrNull { haystack.contains(it.name.lowercase()) }?.let { return it }
        if (haystack.contains("mntm")) return known.first { it.name == "Momentum" }
        if (haystack.contains("unlshd") || haystack.contains("unleashed")) return known.first { it.name == "Unleashed" }
        if (haystack.contains("rm ") || haystack.contains("roguemaster")) return known.first { it.name == "RogueMaster" }
        return null
    }
}

data class FirmwareStatus(
    val distribution: FirmwareDistribution,
    val installedVersion: String,
    val latestRelease: String?,
    /** null when the installed build is a dev build and cannot be compared to a release tag. */
    val updateAvailable: Boolean?,
) {
    val summary: String
        get() {
            val latest = latestRelease ?: return "${distribution.name} $installedVersion"
            return when (updateAvailable) {
                true -> L("${distribution.name} $installedVersion; $latest is available")
                false -> L("${distribution.name} $installedVersion is the newest release")
                null -> L("${distribution.name} $installedVersion (dev build); newest release is $latest")
            }
        }
}

/** Looks up the newest published release of a firmware distribution. Mirrors Swift `FirmwareUpdateChecker`. */
class FirmwareUpdateChecker(private val client: OkHttpClient = defaultClient) {
    suspend fun latestRelease(distribution: FirmwareDistribution): String? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${distribution.repo}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (response.code !in 200..299) return@use null
                val root = runCatching { JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject }.getOrNull()
                    ?: return@use null
                root.get("tag_name")?.asString ?: root.get("name")?.asString
            }
        }
    }

    suspend fun status(fork: String?, version: String?): FirmwareStatus? {
        val distribution = FirmwareCatalog.identify(fork, version) ?: return null
        val installed = version ?: "unknown"
        val latest = runCatching { latestRelease(distribution) }.getOrNull()
        return FirmwareStatus(distribution, installed, latest, compare(installed, latest))
    }

    companion object {
        private val defaultClient = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

        /** Dev builds carry no release tag; a comparison would be misleading, so return null for those. */
        fun compare(installed: String, latest: String?): Boolean? {
            if (latest.isNullOrEmpty()) return null
            val ni = installed.trim().lowercase()
            if (ni.isEmpty() || ni == "unknown") return null
            for (marker in listOf("dev", "dirty", "local", "rc")) if (ni.contains(marker)) return null
            val nl = latest.trim().lowercase()
            fun digits(s: String) = s.dropWhile { !it.isDigit() }
            if (ni == nl) return false
            if (digits(ni) == digits(nl) && digits(nl).isNotEmpty()) return false
            return true
        }
    }
}
