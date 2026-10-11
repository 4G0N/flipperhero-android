package com.flipperhero.agent

import java.util.UUID

data class ApprovalSettings(
    /** Skip the prompt for medium-risk actions. */
    val autoApproveMedium: Boolean = false,
    /** Skip the prompt for high-risk actions too. Blocked actions stay blocked. */
    val yolo: Boolean = false,
    /** In YOLO mode, still ask when the action follows content the agent read from the Flipper. */
    val yoloAsksAfterUntrusted: Boolean = true,
    /** Armed engagement mode. Pre-authorizes a capability set for the session. */
    val engagement: EngagementState = EngagementState(),
)

data class ApprovalRequest(
    val tool: String,
    val summary: String,
    val risk: RiskLevel,
    val reasons: List<String>,
    val diff: String? = null,
    /** The action was requested after the model read content from the Flipper in this turn. */
    val afterUntrustedContent: Boolean = false,
    /** The agent asks to loosen its own permissions; the UI presents this as a permission dialog. */
    val isPermissionChange: Boolean = false,
    val id: UUID = UUID.randomUUID(),
)

/** The press-and-hold sheet implements this in `:app`; tests provide a scripted gate. */
interface ApprovalGate {
    suspend fun decide(request: ApprovalRequest): Boolean
}

object ApprovalPolicy {
    fun requiresApproval(assessment: RiskAssessment, settings: ApprovalSettings, tainted: Boolean): Boolean =
        requiresApproval(assessment.level, settings, tainted)

    fun requiresApproval(level: RiskLevel, settings: ApprovalSettings, tainted: Boolean): Boolean {
        // Armed engagement mode: the operator pre-approved this session's actions up front.
        // Blocked levels never pass; capability-gated tools are checked separately by the executor.
        if (level != RiskLevel.BLOCKED && settings.engagement.active && settings.engagement.profile.autoApprovals) {
            return false
        }
        return when (level) {
            RiskLevel.LOW -> false
            RiskLevel.BLOCKED -> true
            RiskLevel.MEDIUM, RiskLevel.HIGH -> {
                if (settings.yolo) {
                    tainted && settings.yoloAsksAfterUntrusted
                } else {
                    level == RiskLevel.HIGH || !settings.autoApproveMedium || tainted
                }
            }
        }
    }
}
