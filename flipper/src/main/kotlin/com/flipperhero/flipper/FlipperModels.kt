package com.flipperhero.flipper

import com.flipperdevices.protobuf.gpio.GpioPin
import com.flipperdevices.protobuf.screen.InputKey

data class FlipperDirEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Int,
)

data class FlipperStorageInfo(
    val totalSpace: Long,
    val freeSpace: Long,
)

object FlipperLimits {
    const val WRITE_CHUNK_SIZE = 512
    const val DEFAULT_MAX_READ_BYTES = 64 * 1024
}

/** Apps on the Flipper that can be driven over RPC, with the loader name the firmware expects. */
enum class FlipperApp(val appName: String, val fileExtension: String) {
    SUB_GHZ("Sub-GHz", "sub"),
    INFRARED("Infrared", "ir"),
    RFID("125 kHz RFID", "rfid"),
    NFC("NFC", "nfc"),
    IBUTTON("iButton", "ibtn"),
    BAD_KEYBOARD("Bad KB", "txt"),
}

/** D-pad keys, mapped to the firmware's input enum. Mirrors Swift `FlipperKey`. */
enum class FlipperKey(val proto: InputKey) {
    UP(InputKey.UP),
    DOWN(InputKey.DOWN),
    LEFT(InputKey.LEFT),
    RIGHT(InputKey.RIGHT),
    OK(InputKey.OK),
    BACK(InputKey.BACK);

    /** Lower-case name used in tool arguments ("ok", "back", …). */
    val key: String get() = name.lowercase()

    companion object {
        fun fromKey(raw: String): FlipperKey? = entries.firstOrNull { it.key == raw }
    }
}

/** GPIO pins the Flipper exposes on its header. Mirrors Swift `FlipperGPIOPin`. */
enum class FlipperGpioPin(val proto: GpioPin) {
    PC0(GpioPin.PC0),
    PC1(GpioPin.PC1),
    PC3(GpioPin.PC3),
    PB2(GpioPin.PB2),
    PB3(GpioPin.PB3),
    PA4(GpioPin.PA4),
    PA6(GpioPin.PA6),
    PA7(GpioPin.PA7);

    val key: String get() = name.lowercase()

    companion object {
        fun fromKey(raw: String): FlipperGpioPin? = entries.firstOrNull { it.key == raw }
    }
}

sealed class FlipperUpdateResult {
    object Ok : FlipperUpdateResult()
    data class Rejected(val reason: String) : FlipperUpdateResult()
}
