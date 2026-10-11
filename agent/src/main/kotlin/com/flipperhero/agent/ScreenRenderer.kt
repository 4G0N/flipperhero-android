package com.flipperhero.agent

import com.flipperhero.flipper.FlipperScreenFrame
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Renders a Flipper frame the way the device looks: dark pixels on the orange backlight.
 * Pure-Kotlin PNG encoder (java.util.zip only), so it works unchanged on the JVM and on Android —
 * no java.awt / android.graphics. Mirrors Swift `ScreenRenderer`.
 */
object ScreenRenderer {
    private val backlight = Triple(255, 130, 0)
    private val ink = Triple(22, 14, 6)

    /** RGB PNG at [scale]x, for vision models and previews. Returns null only on an empty frame. */
    fun png(frame: FlipperScreenFrame, scale: Int = 4): ByteArray? {
        val size = frame.displaySize
        val width = size.width * scale
        val height = size.height * scale
        if (width <= 0 || height <= 0) return null
        val pixels = frame.pixels()

        // Raw image: each row prefixed with filter byte 0, then RGB triples.
        val raw = ByteArray(height * (1 + width * 3))
        var p = 0
        for (y in 0 until height) {
            raw[p++] = 0 // filter: none
            val srcRow = (y / scale) * size.width
            for (x in 0 until width) {
                val on = pixels[srcRow + (x / scale)]
                val c = if (on) ink else backlight
                raw[p++] = c.first.toByte()
                raw[p++] = c.second.toByte()
                raw[p++] = c.third.toByte()
            }
        }

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) // PNG signature

        val ihdr = ByteArrayOutputStream()
        ihdr.writeInt(width); ihdr.writeInt(height)
        ihdr.write(8)  // bit depth
        ihdr.write(2)  // color type: truecolor RGB
        ihdr.write(0); ihdr.write(0); ihdr.write(0) // compression, filter, interlace
        writeChunk(out, "IHDR", ihdr.toByteArray())

        writeChunk(out, "IDAT", zlib(raw))
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun zlib(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val buffer = ByteArray(64 * 1024)
        val out = ByteArrayOutputStream()
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            out.write(buffer, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        out.writeInt(data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        out.writeInt(crc.value.toInt())
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write((value ushr 24) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }
}
