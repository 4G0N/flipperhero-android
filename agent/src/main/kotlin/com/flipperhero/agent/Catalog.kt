package com.flipperhero.agent

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class CatalogApp(
    val id: String,
    val alias: String,
    val name: String,
    val version: String,
    val versionID: String,
    val summary: String,
    val categoryID: String,
)

sealed class CatalogError(message: String) : Exception(message) {
    data class Http(val code: Int) : CatalogError("App catalog returned HTTP $code")
    object Malformed : CatalogError("Unexpected response from the app catalog") { private fun readResolve(): Any = Malformed }
    data class NotFound(val query: String) : CatalogError("No app found for '$query'")
    data class TooLarge(val size: Int) : CatalogError("Download is $size bytes, which is above the limit")
}

private val catalogHttp: OkHttpClient = OkHttpClient.Builder()
    .callTimeout(45, TimeUnit.SECONDS)
    .build()

/** Read-only client for the official Flipper app catalog (FapHub). Mirrors Swift `FapHubClient`. */
class FapHubClient(
    private val base: String = "https://catalog.flipperzero.one/api/v0",
    private val client: OkHttpClient = catalogHttp,
) {
    private suspend fun get(path: String, query: Map<String, String>): ByteArray = withContext(Dispatchers.IO) {
        val url = base.toHttpUrl().newBuilder().apply {
            path.split("/").filter { it.isNotEmpty() }.forEach { addPathSegment(it) }
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.code !in 200..299) throw CatalogError.Http(response.code)
            response.body?.bytes() ?: throw CatalogError.Malformed
        }
    }

    suspend fun search(query: String, api: String, target: String = "f7", limit: Int = 8): List<CatalogApp> {
        val data = get("application", mapOf("query" to query, "limit" to limit.toString(), "api" to api, "target" to target))
        return parseApps(data)
    }

    suspend fun categories(api: String, target: String = "f7"): Map<String, String> {
        val data = get("category", mapOf("api" to api, "target" to target))
        val raw = runCatching { JsonParser.parseString(String(data)).asJsonArray }.getOrNull() ?: throw CatalogError.Malformed
        return raw.mapNotNull {
            val o = it.asJsonObject
            val id = o.get("_id")?.asString ?: return@mapNotNull null
            val name = o.get("name")?.asString ?: return@mapNotNull null
            id to name
        }.toMap()
    }

    suspend fun downloadBuild(versionID: String, api: String, target: String = "f7"): ByteArray {
        val data = get("application/version/$versionID/build/compatible", mapOf("target" to target, "api" to api))
        if (data.size > MAX_DOWNLOAD_BYTES) throw CatalogError.TooLarge(data.size)
        if (!(data.size >= 4 && data[0].toInt() and 0xFF == 0x7F && data[1].toInt() == 0x45 &&
                data[2].toInt() == 0x4C && data[3].toInt() == 0x46)) {
            throw CatalogError.Malformed // .fap files are ELF
        }
        return data
    }

    companion object {
        const val MAX_DOWNLOAD_BYTES = 4 * 1024 * 1024

        fun parseApps(data: ByteArray): List<CatalogApp> {
            val raw = runCatching { JsonParser.parseString(String(data)).asJsonArray }.getOrNull() ?: throw CatalogError.Malformed
            return raw.mapNotNull { element ->
                val item = element.asJsonObject
                val id = item.get("_id")?.asString ?: return@mapNotNull null
                val version = item.getAsJsonObject("current_version") ?: return@mapNotNull null
                val versionID = version.get("_id")?.asString ?: return@mapNotNull null
                CatalogApp(
                    id = id,
                    alias = item.get("alias")?.asString ?: "",
                    name = version.get("name")?.asString ?: item.get("alias")?.asString ?: "app",
                    version = version.get("version")?.asString ?: "?",
                    versionID = versionID,
                    summary = version.get("short_description")?.asString ?: "",
                    categoryID = item.get("category_id")?.asString ?: "",
                )
            }
        }
    }
}

/** Searches GitHub for Flipper-compatible files and fetches raw file contents. Mirrors Swift `GitHubClient`. */
class GitHubClient(private val client: OkHttpClient = catalogHttp) {
    data class Hit(val repository: String, val path: String, val rawURL: String)

    suspend fun searchCode(query: String, fileExtension: String?, limit: Int = 8): List<Hit> {
        val q = if (fileExtension != null) "$query extension:$fileExtension" else query
        val url = "https://api.github.com/search/code".toHttpUrl().newBuilder()
            .addQueryParameter("q", q).addQueryParameter("per_page", limit.toString()).build()
        val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
            if (response.code !in 200..299) throw CatalogError.Http(response.code)
            val root = runCatching { JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject }.getOrNull()
                ?: throw CatalogError.Malformed
            val items = root.getAsJsonArray("items") ?: throw CatalogError.Malformed
            items.mapNotNull { element ->
                val item = element.asJsonObject
                val path = item.get("path")?.asString ?: return@mapNotNull null
                val repo = item.getAsJsonObject("repository")?.get("full_name")?.asString ?: return@mapNotNull null
                val html = item.get("html_url")?.asString ?: return@mapNotNull null
                Hit(repo, path, html.replace("https://github.com/", "https://raw.githubusercontent.com/").replace("/blob/", "/"))
            }
            }
        }
    }

    suspend fun fetchRaw(urlString: String): ByteArray {
        val url = runCatching { urlString.toHttpUrl() }.getOrNull() ?: throw CatalogError.Malformed
        if (url.scheme != "https" || (url.host != "raw.githubusercontent.com" && url.host != "github.com")) {
            throw CatalogError.Malformed
        }
        return withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.code !in 200..299) throw CatalogError.Http(response.code)
                val data = response.body?.bytes() ?: throw CatalogError.Malformed
                if (data.size > MAX_DOWNLOAD_BYTES) throw CatalogError.TooLarge(data.size)
                data
            }
        }
    }

    companion object {
        const val MAX_DOWNLOAD_BYTES = 1 * 1024 * 1024
    }
}
