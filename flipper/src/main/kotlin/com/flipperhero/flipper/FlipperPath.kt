package com.flipperhero.flipper

/**
 * Validation and normalization for paths sent to the Flipper. Mirrors Swift `FlipperPath`.
 *
 * Canonicalize-then-check: "." and "//" collapse; ".." is rejected outright rather than resolved;
 * the result must sit under one of [roots]. Control characters and over-long paths are refused.
 */
object FlipperPath {
    val roots = listOf("/ext", "/int", "/any")
    const val MAX_LENGTH = 255

    /** Returns a normalized absolute path or throws [FlipperException.InvalidPath]. */
    fun normalize(raw: String): String {
        if (raw.isEmpty()) throw FlipperException.InvalidPath("empty")
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_LENGTH) throw FlipperException.InvalidPath("too long")
        if (!raw.startsWith("/")) throw FlipperException.InvalidPath("must be absolute")
        for (ch in raw) {
            val code = ch.code
            if (code < 0x20 || code == 0x7F) throw FlipperException.InvalidPath("control character")
        }
        val parts = ArrayList<String>()
        for (part in raw.split("/")) {
            if (part.isEmpty()) continue
            if (part == ".") continue
            if (part == "..") throw FlipperException.InvalidPath("'..' is not allowed")
            parts.add(part)
        }
        val first = parts.firstOrNull()
        if (first == null || !roots.contains("/$first")) {
            throw FlipperException.InvalidPath("must be under /ext, /int or /any")
        }
        return "/" + parts.joinToString("/")
    }

    fun lastComponent(path: String): String =
        path.split("/").lastOrNull { it.isNotEmpty() } ?: path

    fun parent(path: String): String =
        "/" + path.split("/").filter { it.isNotEmpty() }.dropLast(1).joinToString("/")

    fun join(dir: String, name: String): String =
        if (dir.endsWith("/")) dir + name else "$dir/$name"
}
