package com.flipperhero.agent

import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class AuditRecord(
    val tool: String,
    val summary: String,
    val risk: RiskLevel?,
    val decision: Decision,
    val succeeded: Boolean,
    val detail: String,
    val id: UUID = UUID.randomUUID(),
    val date: Long = System.currentTimeMillis(),
) {
    enum class Decision(val raw: String) {
        AUTO("auto"), YOLO("yolo"), ENGAGED("engaged"), APPROVED("approved"),
        DENIED("denied"), BLOCKED("blocked"), INVALID("invalid"), SHORTCUT("shortcut"),
    }
}

/** Append-only audit sink. `:app` provides a durable implementation; tests use the in-memory one. */
interface AuditSink {
    suspend fun record(record: AuditRecord)
    /** Newest last. */
    suspend fun recent(limit: Int): List<AuditRecord>
}

class InMemoryAuditLog : AuditSink {
    private val lock = ReentrantLock()
    private val _records = ArrayList<AuditRecord>()

    val records: List<AuditRecord> get() = lock.withLock { _records.toList() }

    override suspend fun record(record: AuditRecord) = lock.withLock { _records.add(record); Unit }
    override suspend fun recent(limit: Int): List<AuditRecord> = lock.withLock { _records.takeLast(limit) }
}

/** Append-only JSON Lines file. Mirrors Swift `FileAuditLog`. */
class FileAuditLog(private val file: File) : AuditSink {
    private val lock = ReentrantLock()

    override suspend fun record(record: AuditRecord) = lock.withLock {
        file.parentFile?.mkdirs()
        file.appendText(encode(record) + "\n")
    }

    override suspend fun recent(limit: Int): List<AuditRecord> = load().takeLast(limit)

    fun load(): List<AuditRecord> = lock.withLock {
        if (!file.exists()) return emptyList()
        file.readLines().filter { it.isNotBlank() }.mapNotNull { decode(it) }
    }

    private fun encode(r: AuditRecord): String {
        val o = com.google.gson.JsonObject()
        o.addProperty("id", r.id.toString())
        o.addProperty("date", r.date)
        o.addProperty("tool", r.tool)
        o.addProperty("summary", r.summary)
        o.addProperty("risk", r.risk?.name)
        o.addProperty("decision", r.decision.raw)
        o.addProperty("succeeded", r.succeeded)
        o.addProperty("detail", r.detail)
        return o.toString()
    }

    private fun decode(line: String): AuditRecord? = try {
        val o = com.google.gson.JsonParser.parseString(line).asJsonObject
        AuditRecord(
            tool = o.get("tool").asString,
            summary = o.get("summary").asString,
            risk = o.get("risk")?.takeIf { !it.isJsonNull }?.asString?.let { RiskLevel.valueOf(it) },
            decision = AuditRecord.Decision.entries.first { it.raw == o.get("decision").asString },
            succeeded = o.get("succeeded").asBoolean,
            detail = o.get("detail").asString,
            id = UUID.fromString(o.get("id").asString),
            date = o.get("date").asLong,
        )
    } catch (e: Exception) {
        null
    }
}
