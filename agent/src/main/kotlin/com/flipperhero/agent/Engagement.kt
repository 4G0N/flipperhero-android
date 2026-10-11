package com.flipperhero.agent

/**
 * What the operator grants when engagement mode is armed. The agent can ask for these, but only the
 * human arm dialog (or an approved `set_engagement_mode`) applies them. Mirrors Swift.
 */
class EngagementProfile(
    /** Medium- and high-risk actions run without a per-action prompt. Blocked actions stay blocked. */
    val autoApprovals: Boolean = true,
    /** Unlocks `rpc_raw`: any device command from the protobuf surface, bypassing path protections. */
    val rawRPC: Boolean = false,
    /** Unlocks `badusb_execute`: the agent starts Bad KB scripts itself instead of only loading them. */
    val autoBadKB: Boolean = false,
    note: String = "",
) {
    /** The operator's scope note; reaches the model, so it is fenced like any other free text. */
    val note: String = cleanNote(note)

    /** Short machine-readable summary for settings, audit and prompts. */
    val summary: String
        get() {
            val parts = buildList {
                if (autoApprovals) add("auto_approvals")
                if (rawRPC) add("raw_rpc")
                if (autoBadKB) add("auto_badusb")
            }
            return if (parts.isEmpty()) "none" else parts.joinToString("+")
        }

    override fun equals(other: Any?): Boolean = other is EngagementProfile &&
        autoApprovals == other.autoApprovals && rawRPC == other.rawRPC &&
        autoBadKB == other.autoBadKB && note == other.note

    override fun hashCode(): Int {
        var result = autoApprovals.hashCode()
        result = 31 * result + rawRPC.hashCode()
        result = 31 * result + autoBadKB.hashCode()
        result = 31 * result + note.hashCode()
        return result
    }

    companion object {
        fun cleanNote(note: String): String = Untrusted.sanitizeName(note).take(200)
    }
}

/** Capability selector for [EngagementState.armed]. */
enum class EngagementCapability { RAW_RPC, AUTO_BAD_KB }

/** Session-only arming state. Never persisted: a restart always returns to disarmed. Mirrors Swift. */
data class EngagementState(
    val active: Boolean = false,
    val profile: EngagementProfile = EngagementProfile(),
    val startedAt: Long? = null,
) {
    fun armed(capability: EngagementCapability): Boolean = active && when (capability) {
        EngagementCapability.RAW_RPC -> profile.rawRPC
        EngagementCapability.AUTO_BAD_KB -> profile.autoBadKB
    }

    companion object {
        val inactive = EngagementState()
    }
}
