package com.flipperhero.app.approval

import com.flipperhero.agent.ApprovalGate
import com.flipperhero.agent.ApprovalRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A pending approval the UI must resolve, with the deferred the executor is suspended on. */
class PendingApproval(val request: ApprovalRequest) {
    internal val deferred = CompletableDeferred<Boolean>()
}

/**
 * [ApprovalGate] that drives the Compose approval sheet. `decide` suspends the executor until the
 * UI resolves the request (via the deliberate press-and-hold gesture or its accessible equivalent).
 * One request is presented at a time.
 */
class ComposeApprover : ApprovalGate {
    private val mutex = Mutex()
    private val pendingFlow = MutableStateFlow<PendingApproval?>(null)
    val pending: StateFlow<PendingApproval?> = pendingFlow.asStateFlow()

    override suspend fun decide(request: ApprovalRequest): Boolean {
        val approval = PendingApproval(request)
        mutex.withLock { pendingFlow.value = approval }
        return try {
            approval.deferred.await()
        } finally {
            mutex.withLock { if (pendingFlow.value === approval) pendingFlow.value = null }
        }
    }

    /** Called by the UI when the user has made a deliberate approve/deny decision. */
    fun resolve(approval: PendingApproval, approved: Boolean) {
        approval.deferred.complete(approved)
    }

    /** Deny anything outstanding (e.g. on disconnect or screen dismissal). */
    fun cancelAll() {
        pendingFlow.value?.let { it.deferred.complete(false) }
    }
}
