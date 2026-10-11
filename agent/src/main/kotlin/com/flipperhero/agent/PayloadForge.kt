package com.flipperhero.agent

enum class PayloadKind(val id: String, val fileExtension: String, val folder: String) {
    BADUSB("badusb", "txt", "/ext/badusb"),
    SUBGHZ("subghz", "sub", "/ext/subghz"),
    INFRARED("infrared", "ir", "/ext/infrared"),
    NFC("nfc", "nfc", "/ext/nfc"),
    RFID("rfid", "rfid", "/ext/lfrfid"),
    IBUTTON("ibutton", "ibtn", "/ext/ibutton");

    companion object {
        fun fromId(id: String): PayloadKind? = entries.firstOrNull { it.id == id }
    }

    /** Format reference handed to the model, from this firmware's parsers and real sample files. */
    val formatReference: String
        get() = when (this) {
            BADUSB -> """
                Flipper "Bad KB" DuckyScript. One command per line.

                Supported keywords in this firmware:
                REM (comment), DELAY <ms>, DEFAULTDELAY/DEFAULT_DELAY <ms>, STRINGDELAY/STRING_DELAY <ms>,
                STRING <text>, STRINGLN <text> (types text then Enter), REPEAT <n> (repeats previous line),
                WAIT_FOR_BUTTON_PRESS, HOLD <key>, RELEASE <key>, ALTCHAR/ALTCODE/ALTSTRING,
                Modifiers: CTRL/CONTROL, ALT, SHIFT, GUI/WINDOWS, FN
                Keys: ENTER, ESC/ESCAPE, TAB, SPACE, BACKSPACE/BACK, DELETE, INSERT, HOME, END,
                PAGEUP, PAGEDOWN, UP/UPARROW, DOWN/DOWNARROW, LEFT/LEFTARROW, RIGHT/RIGHTARROW,
                F1-F24, CAPSLOCK, NUMLOCK, SCROLLLOCK, PRINTSCREEN, PAUSE, BREAK, MENU, APP, POWER,
                Media: MUTE, VOLUME_UP, VOLUME_DOWN, PLAY, PLAY_PAUSE, STOP, NEXT_TRACK, PREV_TRACK, EJECT,
                Mouse: LEFTCLICK/LEFT_CLICK, RIGHTCLICK/RIGHT_CLICK, MIDDLECLICK, WHEELCLICK,
                MOUSEMOVE <x> <y>, MOUSESCROLL <n>
                Combine modifiers with a key on one line, e.g. "GUI r" or "CTRL SHIFT ESC".

                Start with REM lines describing what the script does. Begin with a DELAY of at least 500
                so the host enumerates the keyboard before typing.
            """.trimIndent()
            SUBGHZ -> """
                Flipper Sub-GHz file. Two forms.

                Protocol-based (preferred when the protocol is known):
                Filetype: Flipper SubGhz Key File
                Version: 1
                Frequency: 433920000
                Preset: FuriHalSubGhzPresetOok650Async
                Protocol: Princeton
                Bit: 24
                Key: 00 00 00 00 00 12 34 56
                TE: 400

                RAW capture:
                Filetype: Flipper SubGhz RAW File
                Version: 1
                Frequency: 433920000
                Preset: FuriHalSubGhzPresetOok270Async
                Protocol: RAW
                RAW_Data: 400 -400 800 -800 ...   (durations in microseconds, negative = low)

                Presets: FuriHalSubGhzPresetOok270Async, FuriHalSubGhzPresetOok650Async,
                FuriHalSubGhzPreset2FSKDev238Async, FuriHalSubGhzPreset2FSKDev476Async.
                Frequency must be a legal band for the user's region (433.92, 868.35, 315, 915 MHz).
            """.trimIndent()
            INFRARED -> """
                Flipper infrared remote file. One block per button.

                Filetype: IR signals file
                Version: 1
                #
                name: POWER
                type: parsed
                protocol: NEC
                address: 07 00 00 00
                command: 02 00 00 00

                Protocols: NEC, NECext, NEC42, NEC42ext, Samsung32, RC5, RC5X, RC6, SIRC, SIRC15,
                SIRC20, Kaseikyo, RCA, Pioneer.
                Raw blocks use: type: raw, frequency: 38000, duty_cycle: 0.330000,
                data: <space separated durations>.
                Button names are free text, commonly POWER, VOL_UP, VOL_DN, CH_UP, CH_DN, MUTE.
            """.trimIndent()
            NFC -> """
                Flipper NFC dump.

                Filetype: Flipper NFC device
                Version: 4
                Device type: UID
                UID: 04 11 22 33
                ATQA: 00 44
                SAK: 00

                Card contents normally come from reading a real card. Generating a dump only makes
                sense for simple UID-only tags; anything with keys or sectors should be captured.
            """.trimIndent()
            RFID -> """
                Flipper 125 kHz RFID key file.

                Filetype: Flipper RFID key
                Version: 1
                Key type: EM4100
                Data: 12 34 56 78 90

                Key types: EM4100, H10301, Idteck, Indala26, IOProxXSF, AWID, FDX-A, FDX-B,
                HIDProx, HIDExt, Pyramid, Viking, Jablotron, Paradox, PAC/Stanley, Keri, Gallagher.
                Data length depends on the type (EM4100 is 5 bytes, HID formats differ).
            """.trimIndent()
            IBUTTON -> """
                Flipper iButton key file.

                Filetype: Flipper iButton key
                Version: 1
                Key type: DS1990
                Data: 01 23 45 67 89 AB CD EF

                Key types: DS1990 (8 bytes, first is family code 01, last is CRC), Cyfral (2 bytes),
                Metakom (4 bytes).
            """.trimIndent()
        }
}

sealed class ForgeError(message: String) : Exception(message) {
    object Unavailable : ForgeError("Payload generation needs an API key in Settings") { private fun readResolve(): Any = Unavailable }
    object Empty : ForgeError("The model returned no content") { private fun readResolve(): Any = Empty }
    data class WrongFormat(val why: String) : ForgeError("The generated file does not look valid: $why")
}

/** Generates Flipper file contents from a description, validating against the target format. */
class PayloadForge(private val llm: LLMClient) {
    suspend fun forge(kind: PayloadKind, description: String): String {
        val system = """
            You write Flipper Zero ${kind.id} files for the user's own device and authorized testing.
            Output ONLY the file contents. No markdown fences, no commentary, no explanation.
            The file must load on the device, so follow the format exactly.

            ${kind.formatReference}
        """.trimIndent()
        val reply = llm.complete(
            listOf(
                ChatMessage(role = ChatRole.SYSTEM, content = system),
                ChatMessage(role = ChatRole.USER, content = description),
            ),
            emptyList(),
        )
        val content = stripFences(reply.content ?: "")
        if (content.isEmpty()) throw ForgeError.Empty
        validate(content, kind)
        return content
    }

    companion object {
        /** Models often wrap output in ``` despite instructions. */
        fun stripFences(text: String): String {
            val lines = text.trim().split("\n").toMutableList()
            if (lines.firstOrNull()?.startsWith("```") == true) lines.removeAt(0)
            if (lines.lastOrNull()?.startsWith("```") == true) lines.removeAt(lines.size - 1)
            return lines.joinToString("\n").trim()
        }

        private val knownBadUsb = setOf(
            "REM", "DELAY", "DEFAULTDELAY", "DEFAULT_DELAY", "STRINGDELAY", "STRING_DELAY",
            "DEFAULTSTRINGDELAY", "DEFAULT_STRING_DELAY", "STRING", "STRINGLN", "REPEAT", "HOLD",
            "RELEASE", "WAIT_FOR_BUTTON_PRESS", "ALTCHAR", "ALTCODE", "ALTSTRING", "CTRL", "CONTROL",
            "ALT", "SHIFT", "GUI", "WINDOWS", "FN", "ENTER", "ESC", "ESCAPE", "TAB", "SPACE",
            "BACKSPACE", "BACK", "DELETE", "INSERT", "HOME", "END", "PAGEUP", "PAGEDOWN", "UP",
            "UPARROW", "DOWN", "DOWNARROW", "LEFT", "LEFTARROW", "RIGHT", "RIGHTARROW", "CAPSLOCK",
            "NUMLOCK", "SCROLLLOCK", "PRINTSCREEN", "PAUSE", "BREAK", "MENU", "APP", "POWER",
            "MUTE", "VOLUME_UP", "VOLUME_DOWN", "PLAY", "PLAY_PAUSE", "STOP", "NEXT_TRACK",
            "PREV_TRACK", "EJECT", "LEFTCLICK", "LEFT_CLICK", "RIGHTCLICK", "RIGHT_CLICK",
            "MIDDLECLICK", "MIDDLE_CLICK", "WHEELCLICK", "WHEEL_CLICK", "MOUSEMOVE", "MOUSE_MOVE",
            "MOUSESCROLL", "MOUSE_SCROLL", "SLEEP", "REFRESH", "LOGOFF", "REBOOT", "EXIT", "ID",
            "BT_ID", "BLE_ID", "MEDIA", "GLOBE", "SYSRQ", "SNAPSHOT", "FORWARD", "BRIGHT_UP",
            "BRIGHT_DOWN", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11",
            "F12", "F13", "F14", "F15", "F16", "F17", "F18", "F19", "F20", "F21", "F22", "F23", "F24",
        )

        fun validate(content: String, kind: PayloadKind) {
            val lines = content.split("\n")
            when (kind) {
                PayloadKind.BADUSB -> {
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) continue
                        val head = trimmed.split(" ").first()
                        if (!knownBadUsb.contains(head.uppercase())) {
                            throw ForgeError.WrongFormat("unknown command '$head'")
                        }
                    }
                }
                PayloadKind.SUBGHZ, PayloadKind.INFRARED, PayloadKind.NFC, PayloadKind.RFID, PayloadKind.IBUTTON -> {
                    if (lines.firstOrNull()?.startsWith("Filetype:") != true) {
                        throw ForgeError.WrongFormat("must start with a Filetype: header")
                    }
                    if (!content.contains("Version:")) {
                        throw ForgeError.WrongFormat("missing Version: line")
                    }
                }
            }
        }
    }
}
