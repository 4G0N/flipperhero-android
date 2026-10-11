package com.flipperhero.flipper

import com.flipperdevices.protobuf.Main
import com.google.gson.JsonParser
import com.google.protobuf.util.JsonFormat
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Raw RPC escape hatch (Swift `RawRPC.swift`). Engagement-mode capability `raw_rpc` gates callers
 * at the executor; this only transports an arbitrary PB_Main built from the model's JSON. Only the
 * `content` oneof is taken from the request; command id and framing stay ours.
 *
 * Uses full protobuf-java's [JsonFormat] (javalite has no JSON support — hence the full runtime).
 */
suspend fun FlipperRpcClient.rawRpc(jsonRequest: String, timeout: Duration = 20.seconds): String {
    val trimmed = jsonRequest.trim()
    if (trimmed.toByteArray(Charsets.UTF_8).size > 16 * 1024) {
        throw FlipperException.Rpc("request larger than 16 KB")
    }
    // The model is instructed to wrap the message under a "content" key, e.g.
    // {"content":{"systemPingRequest":{}}}. Canonical protobuf JSON flattens the oneof instead,
    // so unwrap a top-level "content" object before parsing; a flat form is tolerated too.
    val forParsing = try {
        val root = JsonParser.parseString(trimmed)
        if (root.isJsonObject && root.asJsonObject.get("content")?.isJsonObject == true) {
            root.asJsonObject.getAsJsonObject("content").toString()
        } else {
            trimmed
        }
    } catch (e: Exception) {
        throw FlipperException.Rpc("invalid JSON request")
    }
    val requestBuilder = Main.newBuilder()
    try {
        JsonFormat.parser().merge(forParsing, requestBuilder)
    } catch (e: Exception) {
        throw FlipperException.Rpc("invalid JSON request")
    }
    val parsed = requestBuilder.build()
    if (parsed.contentCase == Main.ContentCase.CONTENT_NOT_SET) {
        throw FlipperException.Rpc("request must set a 'content' field, e.g. {\"content\":{\"systemPingRequest\":{}}}")
    }
    // Copy only the content oneof; our command id (set by call) survives because proto3 merge
    // skips default singular fields (parsed.command_id == 0, command_status == OK).
    val parts = call(timeout = timeout) { mergeFrom(parsed) }
    val printer = JsonFormat.printer().omittingInsignificantWhitespace()
    val out = StringBuilder()
    for ((index, part) in parts.take(50).withIndex()) {
        if (index > 0) out.append("\n")
        out.append(runCatching { printer.print(part) }.getOrDefault("{\"commandStatus\":\"ERROR\"}"))
        if (out.toString().toByteArray(Charsets.UTF_8).size > 16 * 1024) {
            out.append("\n[truncated]")
            break
        }
    }
    return out.toString()
}
