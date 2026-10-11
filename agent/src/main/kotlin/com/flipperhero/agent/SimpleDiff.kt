package com.flipperhero.agent

/** Minimal line diff for approval prompts. Mirrors Swift `SimpleDiff`. */
object SimpleDiff {
    fun render(old: String?, new: String, maxLines: Int = 40): String {
        val newLines = new.split("\n")
        if (old == null) {
            val shown = newLines.take(maxLines).map { "+ $it" }
            val more = if (newLines.size > maxLines) listOf("… ${newLines.size - maxLines} more lines") else emptyList()
            return (listOf("New file, ${newLines.size} lines:") + shown + more).joinToString("\n")
        }
        val oldLines = old.split("\n")
        if (old == new) return "No changes."
        if (oldLines.size.toLong() * newLines.size.toLong() > 250_000L) {
            return "File changes from ${oldLines.size} to ${newLines.size} lines (too large to diff)."
        }
        val n = oldLines.size
        val m = newLines.size
        val table = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                table[i][j] = if (oldLines[i] == newLines[j]) table[i + 1][j + 1] + 1
                else maxOf(table[i + 1][j], table[i][j + 1])
            }
        }
        val out = ArrayList<String>()
        var i = 0
        var j = 0
        while (i < n || j < m) {
            when {
                i < n && j < m && oldLines[i] == newLines[j] -> { i++; j++ }
                j < m && (i == n || table[i][j + 1] >= table[i + 1][j]) -> { out.add("+ ${newLines[j]}"); j++ }
                else -> { out.add("- ${oldLines[i]}"); i++ }
            }
        }
        val extra = if (out.size > maxLines) listOf("… ${out.size - maxLines} more changed lines") else emptyList()
        return (out.take(maxLines) + extra).joinToString("\n")
    }
}
