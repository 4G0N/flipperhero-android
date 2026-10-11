package com.flipperhero.flipper

import com.flipperdevices.protobuf.screen.ScreenFrame
import com.flipperdevices.protobuf.screen.ScreenOrientation

/**
 * One frame of the Flipper's 128x64 monochrome display. Mirrors Swift `FlipperScreenFrame`.
 *
 * The firmware sends the raw u8g2 buffer: 8 pages of 128 bytes, each byte a vertical strip of
 * 8 pixels with the least significant bit at the top.
 */
class FlipperScreenFrame(
    val buffer: ByteArray,
    val orientation: Orientation = Orientation.NORMAL,
) {
    enum class Orientation { NORMAL, FLIPPED, VERTICAL, VERTICAL_FLIPPED }

    companion object {
        const val WIDTH = 128
        const val HEIGHT = 64

        /** Builds a frame from a protobuf [ScreenFrame], or null if the buffer is too small. */
        fun from(frame: ScreenFrame): FlipperScreenFrame? {
            val data = frame.data.toByteArray()
            if (data.size < WIDTH * HEIGHT / 8) return null
            val orientation = when (frame.orientation) {
                ScreenOrientation.HORIZONTAL_FLIP -> Orientation.FLIPPED
                ScreenOrientation.VERTICAL -> Orientation.VERTICAL
                ScreenOrientation.VERTICAL_FLIP -> Orientation.VERTICAL_FLIPPED
                else -> Orientation.NORMAL
            }
            return FlipperScreenFrame(data, orientation)
        }
    }

    data class DisplaySize(val width: Int, val height: Int)

    /** Size as shown to the user, after applying the orientation. */
    val displaySize: DisplaySize
        get() = when (orientation) {
            Orientation.NORMAL, Orientation.FLIPPED -> DisplaySize(WIDTH, HEIGHT)
            Orientation.VERTICAL, Orientation.VERTICAL_FLIPPED -> DisplaySize(HEIGHT, WIDTH)
        }

    /** Raw buffer pixel, in the framebuffer's own coordinates. */
    fun rawPixel(x: Int, y: Int): Boolean {
        if (x !in 0 until WIDTH || y !in 0 until HEIGHT) return false
        val index = (y / 8) * WIDTH + x
        return buffer[index].toInt() and (1 shl (y % 8)) != 0
    }

    /** Pixel as it appears to the user, honouring the orientation. */
    fun pixel(x: Int, y: Int): Boolean = when (orientation) {
        Orientation.NORMAL -> rawPixel(x, y)
        Orientation.FLIPPED -> rawPixel(WIDTH - 1 - x, HEIGHT - 1 - y)
        Orientation.VERTICAL -> rawPixel(WIDTH - 1 - y, x)
        Orientation.VERTICAL_FLIPPED -> rawPixel(y, HEIGHT - 1 - x)
    }

    /** Row-major on/off pixels in display orientation, convenient for rendering. */
    fun pixels(): BooleanArray {
        val size = displaySize
        val out = BooleanArray(size.width * size.height)
        for (y in 0 until size.height) {
            for (x in 0 until size.width) {
                out[y * size.width + x] = pixel(x, y)
            }
        }
        return out
    }
}
