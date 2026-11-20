package app.sucareto.hidora.profile.keyboardmouse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sucareto.hidora.R
import app.sucareto.hidora.profile.keyboardmouse.agent.AgentBridge

/**
 * One nearby entry for request/approval/status/revoke; never an invisible
 * settings toggle or a permission that silently persists after backgrounding.
 */
@Composable
fun AgentControlBar(bridge: AgentBridge) {
    val status = bridge.ui
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .displayCutoutPadding()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .heightIn(min = 42.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val label = when {
            status.error != null -> stringResource(R.string.agent_control_error, status.error)
            status.waitingForApproval -> stringResource(R.string.agent_control_request)
            status.allowed -> stringResource(R.string.agent_control_granted)
            else -> stringResource(R.string.agent_control_idle)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        when {
            status.waitingForApproval -> {
                TextButton(onClick = bridge::denyPending) {
                    Text(stringResource(R.string.agent_control_deny))
                }
                Button(onClick = bridge::allowPending) {
                    Text(stringResource(R.string.agent_control_allow))
                }
            }
            status.allowed -> Button(onClick = bridge::revoke) {
                Text(stringResource(R.string.agent_control_stop))
            }
        }
    }
}
