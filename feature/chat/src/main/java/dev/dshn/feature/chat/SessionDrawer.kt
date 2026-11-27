package dev.dshn.feature.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dshn.app.runtime.RuntimeLifecycleState
import dev.dshn.app.runtime.SessionSummaryItem
import dev.dshn.core.ui.BrandMark
import dev.dshn.core.ui.LocalDshnColors
import dev.dshn.core.ui.PillTone
import dev.dshn.core.ui.StatusDot
import dev.dshn.core.ui.StatusPill
import dev.dshn.core.ui.tr
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
internal fun SessionDrawer(
    vm: ChatViewModel,
    activeId: String?,
    activeTitle: String,
    sessionBusy: Boolean,
    sessionsAwaitingInput: Set<String>,
    onNewChat: () -> Unit,
    onNewChatInDir: () -> Unit,
    onOpenSession: (SessionSummaryItem) -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenDeviceBridge: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val loading by vm.sessionsLoading.collectAsStateWithLifecycle()
    val error by vm.sessionsError.collectAsStateWithLifecycle()
    val runtimeStatus by vm.runtimeStatus.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val envManifest by vm.envManifest.collectAsStateWithLifecycle()
    val semantic = LocalDshnColors.current
    var query by remember { mutableStateOf("") }

    val filtered = remember(sessions, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) sessions
        else sessions.filter {
            (it.title ?: "").lowercase().contains(q) || it.id.lowercase().contains(q) || (it.cwd ?: "").lowercase().contains(q)
        }
    }
    val groups = groupSessionsByDay(filtered)

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.width(312.dp)
    ) {
        Column(Modifier.fillMaxHeight()) {
            Row(
                modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BrandMark(size = 38.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("DSHN", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "DeepSeek Harness Native",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = onNewChat,
                    enabled = !sessionBusy,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("drawer_new_chat_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Rounded.AddComment, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("新对话", "New chat"))
                }
                Spacer(Modifier.width(6.dp))
                IconButton(
                    onClick = onNewChatInDir,
                    enabled = !sessionBusy,
                    modifier = Modifier.testTag("drawer_new_chat_dir_button")
                ) {
                    Icon(Icons.Rounded.CreateNewFolder, contentDescription = tr("在指定目录新建", "New chat in folder"))
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                shape = RoundedCornerShape(14.dp),
                color = semantic.subtleBg
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            Box {
                                if (query.isEmpty()) {
                                    Text(tr("搜索对话", "Search chats"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                                }
                                inner()
                            }
                        }
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                if (loading && sessions.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    }
                }
                if (sessions.isEmpty() && !loading) {
                    item {
                        Text(
                            when {
                                !error.isNullOrBlank() -> tr("对话列表暂时不可用", "Chat list unavailable right now")
                                runtimeStatus.state != RuntimeLifecycleState.HEALTHY && runtimeStatus.state != RuntimeLifecycleState.DEGRADED ->
                                    tr("运行环境启动后，这里会显示你的对话", "Your chats appear here once the runtime is running")
                                else -> tr("还没有对话", "No chats yet")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)
                        )
                    }
                }
                for ((label, items) in groups) {
                    item(key = "hdr-$label") {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp)
                        )
                    }
                    itemsIndexed(items, key = { _, s -> s.id }) { _, item ->
                        SessionRow(
                            item = item,
                            isActive = item.id == activeId,
                            activeTitle = activeTitle,
                            enabled = !sessionBusy,
                            awaitingInput = item.id in sessionsAwaitingInput,
                            onOpen = { onOpenSession(item) },
                            onCopyId = { vm.copyToClipboard("session id", item.id) },
                            onClose = { vm.closeSession(item.id) }
                        )
                    }
                }
            }

            HorizontalDivider(color = semantic.cardBorder)
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                val itemColors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent)
                NavigationDrawerItem(
                    label = { Text(tr("插件", "Plugins")) },
                    icon = { Icon(Icons.Rounded.Extension, contentDescription = null) },
                    selected = false,
                    onClick = onOpenPlugins,
                    colors = itemColors,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("drawer_nav_plugins")
                )
                NavigationDrawerItem(
                    label = { Text(tr("终端", "Terminal")) },
                    icon = { Icon(Icons.Rounded.Terminal, contentDescription = null) },
                    selected = false,
                    onClick = onOpenTerminal,
                    colors = itemColors,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("drawer_nav_terminal")
                )
                NavigationDrawerItem(
                    label = { Text(tr("设备桥与 Root", "Device bridge & Root")) },
                    icon = { Icon(Icons.Rounded.DeveloperBoard, contentDescription = null) },
                    badge = {
                        if (settings.bridgeEnabled) StatusPill(tr("已开启", "On"), PillTone.SUCCESS)
                    },
                    selected = false,
                    onClick = onOpenDeviceBridge,
                    colors = itemColors,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("drawer_nav_bridge")
                )
                NavigationDrawerItem(
                    label = { Text(tr("设置", "Settings")) },
                    icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                    selected = false,
                    onClick = onOpenSettings,
                    colors = itemColors,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("drawer_nav_settings")
                )
                val (stateText, tone) = runtimeStateLabel(runtimeStatus.state)
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusDot(tone, size = 7.dp, pulsing = runtimeStatus.state == RuntimeLifecycleState.STARTING)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        buildString {
                            append(tr("运行环境：", "Runtime: "))
                            append(stateText)
                            if (envManifest.dshVersion.isNotBlank()) append(" · DSH ${envManifest.dshVersion}")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun groupSessionsByDay(items: List<SessionSummaryItem>): List<Pair<String, List<SessionSummaryItem>>> {
    val today = tr("今天", "Today")
    val yesterday = tr("昨天", "Yesterday")
    val week = tr("7 天内", "Previous 7 days")
    val earlier = tr("更早", "Earlier")
    return remember(items, today) {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val startToday = cal.timeInMillis
        val startYesterday = startToday - 86_400_000L
        val startWeek = startToday - 6 * 86_400_000L
        val buckets = linkedMapOf<String, MutableList<SessionSummaryItem>>(
            today to mutableListOf(), yesterday to mutableListOf(), week to mutableListOf(), earlier to mutableListOf()
        )
        for (s in items) {
            val key = when {
                s.createdAt >= startToday -> today
                s.createdAt >= startYesterday -> yesterday
                s.createdAt >= startWeek -> week
                else -> earlier
            }
            buckets.getValue(key).add(s)
        }
        buckets.filterValues { it.isNotEmpty() }.map { it.key to it.value.toList() }
    }
}

@Composable
private fun SessionRow(
    item: SessionSummaryItem,
    isActive: Boolean,
    activeTitle: String,
    enabled: Boolean,
    awaitingInput: Boolean,
    onOpen: () -> Unit,
    onCopyId: () -> Unit,
    onClose: () -> Unit
) {
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    var menu by remember { mutableStateOf(false) }
    val title = item.title?.takeIf { it.isNotBlank() }
        ?: if (isActive) activeTitle else null
        ?: if (item.createdAt > 0) tr("对话 ", "Chat ") + timeFmt.format(Date(item.createdAt)) else tr("未命名对话", "Untitled chat")
    Surface(
        onClick = onOpen,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append(item.cwd ?: "/root")
                        if (item.createdAt > 0) append(" · ").append(timeFmt.format(Date(item.createdAt)))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!item.error.isNullOrBlank()) {
                    Text(
                        tr("读取警告：", "Read warning: ") + item.error,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (item.corrupt || item.readOnly) MaterialTheme.colorScheme.error else LocalDshnColors.current.warning,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            when {
                item.corrupt || item.readOnly -> Icon(
                    Icons.Rounded.WarningAmber,
                    contentDescription = tr("只读", "Read-only"),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
                awaitingInput -> StatusDot(PillTone.WARNING, size = 7.dp, pulsing = true)
                !item.error.isNullOrBlank() -> Icon(
                    Icons.Rounded.WarningAmber,
                    contentDescription = tr("警告", "Warning"),
                    tint = LocalDshnColors.current.warning,
                    modifier = Modifier.size(16.dp)
                )
                item.live -> StatusDot(PillTone.SUCCESS, size = 6.dp)
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = tr("更多", "More"), modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(tr("复制对话 ID", "Copy session ID")) },
                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                        onClick = { menu = false; onCopyId() }
                    )
                    if (item.live) {
                        DropdownMenuItem(
                            text = { Text(tr("关闭对话（释放资源）", "Close session (free resources)")) },
                            leadingIcon = { Icon(Icons.Rounded.Cancel, contentDescription = null) },
                            onClick = { menu = false; onClose() }
                        )
                    }
                }
            }
        }
    }
}
