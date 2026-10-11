package com.flipperhero.flipper

import com.flipperdevices.protobuf.Main

/**
 * Errors raised by the pure Flipper layer. Mirrors Swift `FlipperError`.
 * BLE/transport-specific errors live in `:app`.
 */
sealed class FlipperException(message: String) : Exception(message) {
    object NotConnected : FlipperException("Flipper is not connected") {
        private fun readResolve(): Any = NotConnected
    }
    object Timeout : FlipperException("Flipper did not answer in time") {
        private fun readResolve(): Any = Timeout
    }
    data class FrameTooLarge(val size: Int) : FlipperException("Frame of $size bytes exceeds the limit")
    object MalformedFrame : FlipperException("Malformed RPC frame") {
        private fun readResolve(): Any = MalformedFrame
    }
    data class Rpc(val status: String) : FlipperException("Flipper RPC error: $status")
    data class InvalidPath(val why: String) : FlipperException("Invalid path: $why")
    object UnexpectedResponse : FlipperException("Unexpected response from Flipper") {
        private fun readResolve(): Any = UnexpectedResponse
    }
}

/** LEB128 varint, as the Flipper RPC framing uses. Mirrors Swift `Varint`. */
object Varint {
    fun encode(value: Long): ByteArray {
        var v = value
        val out = ArrayList<Byte>()
        do {
            var byte = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) byte = byte or 0x80
            out.add(byte.toByte())
        } while (v != 0L)
        return out.toByteArray()
    }

    /**
     * Returns (value, bytesConsumed) starting at [start], or null if the buffer ends mid-varint.
     * Throws [FlipperException.MalformedFrame] on an over-long varint.
     */
    fun decode(bytes: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var index = start
        while (index < bytes.size) {
            val byte = bytes[index].toInt() and 0xFF
            result = result or ((byte and 0x7F).toLong() shl shift)
            index++
            if (byte and 0x80 == 0) return result to (index - start)
            shift += 7
            if (shift > 28) throw FlipperException.MalformedFrame
        }
        return null
    }
}

/** Flipper RPC frames are length-delimited `Main` messages (varint length prefix). Mirrors Swift `FrameCodec`. */
object FrameCodec {
    const val MAX_FRAME_SIZE: Int = 1 shl 20

    fun encode(message: Main): ByteArray {
        val body = message.toByteArray()
        val prefix = Varint.encode(body.size.toLong())
        return prefix + body
    }
}

/**
 * Reassembles frames out of arbitrarily chunked BLE notifications. Mirrors Swift `FrameDecoder`.
 *
 * Android BLE delivers notifications in MTU-sized chunks, so a single RPC message can arrive
 * split across several callbacks — this buffers until whole frames are available.
 */
class FrameDecoder {
    private var buffer = ByteArray(0)

    fun append(chunk: ByteArray): List<Main> {
        buffer += chunk
        val messages = ArrayList<Main>()
        while (true) {
            val decoded = Varint.decode(buffer, 0) ?: break
            val (length, prefix) = decoded
            if (length > FrameCodec.MAX_FRAME_SIZE.toLong()) {
                buffer = ByteArray(0)
                throw FlipperException.FrameTooLarge(length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
            val total = prefix + length.toInt()
            if (buffer.size < total) break
            val body = buffer.copyOfRange(prefix, total)
            buffer = buffer.copyOfRange(total, buffer.size)
            try {
                messages.add(Main.parseFrom(body))
            } catch (e: Exception) {
                buffer = ByteArray(0)
                throw FlipperException.MalformedFrame
            }
        }
        return messages
    }
}
