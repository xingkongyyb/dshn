package dev.dshn.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dshn.app.runtime.SessionSummaryItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SessionsScreen(
    vm: MainViewModel,
    strings: UiStrings,
    modifier: Modifier = Modifier
) {
    val sessions by vm.sessions.collectAsState()
    val loading by vm.sessionsLoading.collectAsState()
    val error by vm.sessionsError.collectAsState()
    val activeId by vm.activeSessionId.collectAsState()
    val settings by vm.settings.collectAsState()

    var newCwd by remember { mutableStateOf("/root") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(strings.sessionsTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                StatusPill("${sessions.size}", PillTone.NEUTRAL)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemeQuickToggleButton(strings = strings, onToggle = { vm.toggleLightDarkTheme(it) })
                OutlinedButton(onClick = { vm.refreshSessions() }) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(strings.refresh)
                }
            }
        }

        DshnSectionCard(
            title = strings.newSession,
            icon = Icons.Default.Add
        ) {
            OutlinedTextField(
                value = newCwd,
                onValueChange = { newCwd = it },
                label = { Text("Working Directory (container path)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = { newCwd = "/root" }, label = { Text("/root") })
                if (!settings.extraWorkspaceBindHostPath.isNullOrBlank()) {
                    AssistChip(onClick = { newCwd = "/mnt/workspace" }, label = { Text("/mnt/workspace") })
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { vm.createNewSession(newCwd.trim().ifEmpty { "/root" }) }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(strings.newSession)
                }
            }
        }

        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (!error.isNullOrBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Session bridge unavailable: $error",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(sessions, key = { it.id }) { item ->
                SessionRowCard(
                    item = item,
                    isActive = item.id == activeId,
                    strings = strings,
                    onOpen = { vm.switchToSession(item) },
                    onClose = { vm.closeSession(item.id) }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionRowCard(
    item: SessionSummaryItem,
    isActive: Boolean,
    strings: UiStrings,
    onOpen: () -> Unit,
    onClose: () -> Unit
) {
    val semantic = LocalDshnColors.current
    val dateFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    val border = when {
        item.corrupt -> BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.65f))
        isActive -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        else -> BorderStroke(1.dp, semantic.cardBorder)
    }
    val bg = when {
        item.corrupt -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        isActive -> semantic.heroBg
        else -> MaterialTheme.colorScheme.surface
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = bg,
        border = border
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Forum,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item.title?.ifBlank { null } ?: "Session ${item.id.take(10)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (item.live) {
                    IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = strings.closeSession, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Text(
                text = "id=${item.id.take(12)} · cwd=${item.cwd ?: "/root"}${
                    if (item.createdAt > 0) " · ${dateFmt.format(Date(item.createdAt))}" else ""
                }",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isActive) StatusPill("ACTIVE", PillTone.INFO, showDot = true)
                    if (item.live) StatusPill("LIVE", PillTone.SUCCESS, showDot = true)
                    else if (item.persisted) StatusPill("PERSISTED", PillTone.NEUTRAL)
                    if (item.corrupt || item.readOnly) StatusPill("CORRUPT READ-ONLY", PillTone.ERROR)
                }
                Button(onClick = onOpen) {
                    Text(if (item.corrupt) "Inspect Read-Only" else strings.resumeSwitch)
                }
            }

            if (!item.error.isNullOrBlank()) {
                Text(
                    "Read warning (file left intact): ${item.error}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
