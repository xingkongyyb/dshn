package dev.dshn.feature.bridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.dshn.app.bridge.PendingBridgeConfirmation
import dev.dshn.core.ui.InfoBanner
import dev.dshn.core.ui.LocalDshnColors
import dev.dshn.core.ui.PillTone
import dev.dshn.core.ui.StatusPill
import dev.dshn.core.ui.tr

@Composable
fun DeviceBridgeConfirmDialog(
    req: PendingBridgeConfirmation,
    onDecision: (Boolean) -> Unit
) {
    val semantic = LocalDshnColors.current
    val isRoot = req.isRootAction
    AlertDialog(
        onDismissRequest = { onDecision(false) },
        icon = {
            Icon(
                imageVector = if (isRoot) Icons.Rounded.Security else Icons.Rounded.Shield,
                contentDescription = null,
                tint = if (isRoot) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                if (isRoot) tr("确认以 Root 身份执行", "Confirm Root (uid=0) action") else tr("确认设备操作", "Confirm device action"),
                style = MaterialTheme.typography.titleLarge,
                color = if (isRoot) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusPill(req.tool, if (isRoot) PillTone.ERROR else PillTone.INFO)
                    if (isRoot) StatusPill(tr("宿主机超级用户", "Host superuser"), PillTone.ERROR, showDot = true)
                }
                Text(req.summary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                if (req.detail.isNotBlank()) {
                    Surface(
                        color = semantic.codeBg,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, semantic.cardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            req.detail,
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                InfoBanner(
                    text = tr(
                        "屏幕上来自其他应用的内容可能包含诱导指令。请确认这个操作确实是你想让 DSH 做的。",
                        req.promptInjectionWarning
                    ),
                    tone = if (isRoot) PillTone.ERROR else PillTone.WARNING
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onDecision(true) },
                modifier = Modifier.testTag("bridge_confirm_allow_button"),
                colors = if (isRoot) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                } else ButtonDefaults.buttonColors()
            ) { Text(tr("允许本次", "Allow once")) }
        },
        dismissButton = {
            OutlinedButton(
                onClick = { onDecision(false) },
                modifier = Modifier.testTag("bridge_confirm_deny_button")
            ) { Text(tr("拒绝", "Deny")) }
        }
    )
}
