package com.flipperhero.app.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.flipperhero.agent.ApprovalRequest
import com.flipperhero.agent.RiskLevel
import com.flipperhero.app.approval.PendingApproval
import kotlinx.coroutines.delay

/**
 * The approval sheet. Approval requires a DELIBERATE action — press-and-hold — so it can't be
 * satisfied by one reflexive tap. For TalkBack / switch-access / motor-impaired users there is an
 * equivalent deliberate path that preserves that property: a two-step "Approve…" then "Confirm"
 * (two distinct intentional activations), never a single tap. Denial is a single tap (it is safe).
 */
@Composable
fun ApprovalSheet(pending: PendingApproval, onResolve: (PendingApproval, Boolean) -> Unit) {
    val request = pending.request
    Dialog(onDismissRequest = { onResolve(pending, false) }) {
        Card {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(riskLabel(request.risk), style = MaterialTheme.typography.labelLarge)
                Text(request.summary, style = MaterialTheme.typography.titleMedium)

                if (request.isPermissionChange) {
                    Text(
                        "This loosens the app's own safety permissions.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (request.afterUntrustedContent) {
                    Text(
                        "⚠ Requested right after content was read from the Flipper. If you didn't ask " +
                            "for this, it may have been planted in a file. Review carefully.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                request.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                request.diff?.let { diff ->
                    Text(diff, style = MaterialTheme.typography.bodySmall)
                }

                HoldToApprove(onApproved = { onResolve(pending, true) })

                // Accessible, deliberate alternative (two intentional steps).
                TwoStepApprove(onApproved = { onResolve(pending, true) })

                OutlinedButton(
                    onClick = { onResolve(pending, false) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Deny this action" },
                ) { Text("Deny") }
            }
        }
    }
}

private fun riskLabel(risk: RiskLevel): String = when (risk) {
    RiskLevel.LOW -> "Low risk"
    RiskLevel.MEDIUM -> "Medium risk — needs your approval"
    RiskLevel.HIGH -> "HIGH RISK — real-world effect"
    RiskLevel.BLOCKED -> "Blocked"
}

private const val HOLD_MS = 800L

@Composable
private fun HoldToApprove(onApproved: () -> Unit) {
    var progress by remember { mutableFloatStateOf(0f) }
    var holding by remember { mutableStateOf(false) }

    LaunchedEffect(holding) {
        if (holding) {
            val step = 16L
            var elapsed = 0L
            while (elapsed < HOLD_MS && holding) {
                delay(step)
                elapsed += step
                progress = elapsed.toFloat() / HOLD_MS
            }
            if (holding && elapsed >= HOLD_MS) {
                holding = false
                onApproved()
            }
        } else {
            progress = 0f
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Button(
            onClick = { /* a single tap must NOT approve; holding does */ },
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Press and hold to approve. A single tap will not approve; " +
                        "use the two-step approve below if you cannot press and hold."
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            holding = true
                            val released = tryAwaitRelease()
                            holding = false
                            if (released) progress = 0f
                        },
                    )
                },
        ) { Text(if (progress > 0f) "Hold to approve…" else "Press and hold to approve") }
        if (progress > 0f) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun TwoStepApprove(onApproved: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!armed) {
            OutlinedButton(
                onClick = { armed = true },
                modifier = Modifier.semantics { contentDescription = "Approve, step 1 of 2" },
            ) { Text("Approve…") }
        } else {
            Button(
                onClick = onApproved,
                modifier = Modifier.semantics { contentDescription = "Confirm approval, step 2 of 2" },
            ) { Text("Confirm approval") }
            OutlinedButton(onClick = { armed = false }) { Text("Cancel") }
        }
    }
}
