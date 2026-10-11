package com.flipperhero.agent

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns the audit log into a markdown engagement report: the timeline an operator can hand to a
 * client or keep as evidence of what was done and who approved it. Mirrors Swift `EngagementReport`.
 */
object EngagementReport {
    private fun stamp(millis: Long): String =
        SimpleDateFormat("MMM d, yyyy 'at' HH:mm:ss", Locale.US).format(Date(millis))

    fun markdown(records: List<AuditRecord>, engagement: EngagementState, generatedAt: Long = System.currentTimeMillis()): String {
        val lines = ArrayList<String>()
        lines.add("# Engagement report")
        lines.add("")
        lines.add("- Generated: ${stamp(generatedAt)}")
        if (engagement.active && engagement.startedAt != null) {
            lines.add("- Engagement armed: ${stamp(engagement.startedAt)} (${engagement.profile.summary})")
            if (engagement.profile.note.isNotEmpty()) lines.add("- Scope note: ${escape(engagement.profile.note)}")
        }
        val first = records.firstOrNull()?.date
        if (first != null) {
            lines.add("- Entries: ${records.size}, ${stamp(first)} to ${records.last().date.let { stamp(it) }}")
        } else {
            lines.add("- Entries: 0")
        }

        val byDecision = HashMap<AuditRecord.Decision, Int>()
        var failures = 0
        for (record in records) {
            byDecision[record.decision] = (byDecision[record.decision] ?: 0) + 1
            if (!record.succeeded) failures++
        }
        lines.add("- Outcome: $failures failed of ${records.size}")
        val decisions = byDecision.entries.sortedBy { it.key.raw }.joinToString(", ") { "${it.key.raw} ${it.value}" }
        if (decisions.isNotEmpty()) lines.add("- Decisions: $decisions")

        lines.add("")
        lines.add("| Time | Tool | Risk | Decision | Result | Summary |")
        lines.add("|---|---|---|---|---|---|")
        for (record in records) {
            val time = stamp(record.date)
            val risk = record.risk?.name?.lowercase() ?: "-"
            val result = if (record.succeeded) "ok" else "failed"
            val summary = escape(if (record.detail.isEmpty()) record.summary else "${record.summary} [${record.detail}]")
            lines.add("| $time | ${escape(record.tool)} | $risk | ${record.decision.raw} | $result | $summary |")
        }
        lines.add("")
        lines.add("Approvals were enforced in code; the decision column shows how each action was authorized.")
        return lines.joinToString("\n")
    }

    /** Keeps the markdown table intact even when summaries contain pipes or newlines. */
    fun escape(text: String): String = Untrusted.sanitizeName(text.replace("|", "\\|"))
}
