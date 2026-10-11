package com.flipperhero.flipper

/**
 * The custom device name lives on the SD card, not in internal storage:
 * /ext/dolphin/name.settings, read by the firmware at boot. Mirrors Swift `FlipperName`.
 */
object FlipperName {
    const val PATH = "/ext/dolphin/name.settings"
    const val MAX_LENGTH = 8

    sealed class NameError(message: String) : Exception(message) {
        data class TooLong(val length: Int) :
            NameError("The name is $length characters; the Flipper allows at most $MAX_LENGTH.")
        object Empty : NameError("The name is empty.") {
            private fun readResolve(): Any = Empty
        }
        object InvalidCharacters : NameError("Use only letters, digits, hyphen and underscore.") {
            private fun readResolve(): Any = InvalidCharacters
        }
    }

    fun validate(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw NameError.Empty
        if (trimmed.length > MAX_LENGTH) throw NameError.TooLong(trimmed.length)
        val ok = trimmed.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        if (!ok) throw NameError.InvalidCharacters
        return trimmed
    }

    fun fileContents(name: String): String =
        "Filetype: Flipper Name File\nVersion: 1\nName: $name\n"

    /** Parses the Name: field out of the settings file. */
    fun parse(contents: String): String? {
        for (line in contents.split("\n", "\r\n", "\r")) {
            if (!line.startsWith("Name:")) continue
            val value = line.removePrefix("Name:").trim()
            return value.ifEmpty { null }
        }
        return null
    }
}
