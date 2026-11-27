package dev.dshn.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dshn.app.runtime.ContextBreakdown
import dev.dshn.app.runtime.ContextInspectSnapshot
import dev.dshn.app.runtime.ContextSectionInfo
import dev.dshn.app.runtime.ContextSkillInfo
import dev.dshn.app.runtime.ContextTokenUsage
import dev.dshn.app.runtime.ContextToolInfo
import dev.dshn.core.ui.LocalDshnColors
import dev.dshn.core.ui.PillTone
import dev.dshn.core.ui.StatusPill
import dev.dshn.core.ui.tr
import java.util.Locale

internal fun formatCompactTokens(tokens: Long): String = when {
    tokens >= 1_000_000L -> String.format(Locale.US, "%.2fM", tokens / 1_000_000.0)
    tokens >= 10_000L -> String.format(Locale.US, "%.1fk", tokens / 1_000.0)
    tokens >= 1_000L -> String.format(Locale.US, "%.2fk", tokens / 1_000.0)
    else -> tokens.toString()
}

internal fun formatCacheHitPercent(rate: Double?): String {
    if (rate == null || rate.isNaN() || rate.isInfinite()) return "--"
    return String.format(Locale.US, "%.1f%%", (rate.coerceIn(0.0, 1.0)) * 100.0)
}

@Composable
internal fun ContextUsageMenuChip(
    used: Long,
    size: Long,
    snapshot: ContextInspectSnapshot?,
    loading: Boolean,
    error: String?,
    onInspect: () -> Unit,
    onCopy: (label: String, text: String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val semantic = LocalDshnColors.current
    val config = LocalConfiguration.current
    val popupWidth = minOf((config.screenWidthDp - 24).coerceAtLeast(260), 348).dp

    val effectiveSize = if (size > 0L) size else (snapshot?.contextWindow ?: 0L)
    val effectiveUsed = if (used > 0L) used else (snapshot?.usedTokens ?: 0L)
    val fraction = if (effectiveSize > 0L) (effectiveUsed.toFloat() / effectiveSize.toFloat()).coerceIn(0f, 1f) else 0f
    val ringColor = when {
        fraction > 0.85f -> MaterialTheme.colorScheme.error
        fraction > 0.6f -> semantic.warning
        else -> MaterialTheme.colorScheme.primary
    }

    Box {
        Surface(
            onClick = {
                expanded = true
                onInspect()
            },
            shape = RoundedCornerShape(999.dp),
            color = if (expanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
            modifier = Modifier.testTag("context_usage_chip")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.5.dp,
                    color = ringColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "${(fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, semantic.cardBorder)
        ) {
            Column(
                modifier = Modifier
                    .width(popupWidth)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("context_inspector_popup"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        tr("上下文详情", "Context details"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    IconButton(
                        onClick = onInspect,
                        enabled = !loading,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = tr("刷新", "Refresh"),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Summary card: Context usage + Cache hit rate + token breakdown
                ContextSummaryCard(
                    used = effectiveUsed,
                    size = effectiveSize,
                    fraction = fraction,
                    ringColor = ringColor,
                    breakdown = snapshot?.breakdown ?: ContextBreakdown(),
                    tokenUsage = snapshot?.tokenUsage ?: ContextTokenUsage(),
                    lastTurnUsage = snapshot?.lastTurnUsage
                )

                // Error banner (non-blocking if cached snapshot exists)
                if (!error.isNullOrBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            error,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(onClick = onInspect) {
                            Text(tr("重试", "Retry"), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                // 3-tab selector: System Prompt / Tools / Skills
                val sectionsCount = snapshot?.systemPrompt?.sections?.size ?: 0
                val toolsCount = snapshot?.tools?.size ?: 0
                val skillsCount = snapshot?.skills?.size ?: 0
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(semantic.subtleBg)
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    InspectorTabChip(
                        icon = Icons.Rounded.Description,
                        label = tr("提示词", "Prompt") + if (snapshot != null) " ($sectionsCount)" else "",
                        selected = selectedTab == 0,
                        modifier = Modifier.weight(1f).testTag("ctx_tab_prompt"),
                        onClick = { selectedTab = 0 }
                    )
                    InspectorTabChip(
                        icon = Icons.Rounded.Build,
                        label = tr("工具", "Tools") + if (snapshot != null) " ($toolsCount)" else "",
                        selected = selectedTab == 1,
                        modifier = Modifier.weight(1f).testTag("ctx_tab_tools"),
                        onClick = { selectedTab = 1 }
                    )
                    InspectorTabChip(
                        icon = Icons.Rounded.Extension,
                        label = "Skills" + if (snapshot != null) " ($skillsCount)" else "",
                        selected = selectedTab == 2,
                        modifier = Modifier.weight(1f).testTag("ctx_tab_skills"),
                        onClick = { selectedTab = 2 }
                    )
                }

                HorizontalDivider(color = semantic.cardBorder)

                // Tab body
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp, max = 264.dp)
                ) {
                    when {
                        snapshot == null && loading -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 28.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            }
                        }
                        snapshot == null -> {
                            Text(
                                tr("暂无上下文快照，请点击右上角刷新。", "No context snapshot yet. Tap refresh above."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 18.dp, horizontal = 4.dp)
                            )
                        }
                        selectedTab == 0 -> SystemPromptTabBody(
                            snapshot = snapshot,
                            onCopy = onCopy
                        )
                        selectedTab == 1 -> ToolsTabBody(tools = snapshot.tools)
                        else -> SkillsTabBody(skills = snapshot.skills)
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextSummaryCard(
    used: Long,
    size: Long,
    fraction: Float,
    ringColor: Color,
    breakdown: ContextBreakdown,
    tokenUsage: ContextTokenUsage,
    lastTurnUsage: ContextTokenUsage?
) {
    val semantic = LocalDshnColors.current
    val sessionHitRate = tokenUsage.resolvedCacheHitRate()
    val lastHitRate = lastTurnUsage?.resolvedCacheHitRate()
    val primaryHitRate = sessionHitRate ?: lastHitRate
    val hitTone = when {
        primaryHitRate == null -> PillTone.NEUTRAL
        primaryHitRate >= 0.70 -> PillTone.SUCCESS
        primaryHitRate >= 0.35 -> PillTone.INFO
        else -> PillTone.WARNING
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = semantic.subtleBg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        tr("上下文占用", "Context window"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (size > 0L) {
                            "${formatCompactTokens(used)} / ${formatCompactTokens(size)} (${(fraction * 100).toInt()}%)"
                        } else if (used > 0L) {
                            "${formatCompactTokens(used)} tokens"
                        } else {
                            tr("0 tokens（新对话）", "0 tokens (new chat)")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = ringColor
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        tr("缓存命中率", "Cache hit rate"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    StatusPill(
                        text = formatCacheHitPercent(primaryHitRate),
                        tone = hitTone
                    )
                }
            }

            // Token breakdown bar when available
            if (breakdown.totalTokens > 0L) {
                val total = breakdown.totalTokens.toFloat().coerceAtLeast(1f)
                val sysW = (breakdown.systemTokens / total).coerceAtLeast(0.02f)
                val toolsW = (breakdown.toolsTokens / total).coerceAtLeast(0.02f)
                val msgW = (breakdown.messageTokens / total).coerceAtLeast(0.02f)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    horizontalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    if (breakdown.systemTokens > 0L) {
                        Box(Modifier.weight(sysW).height(5.dp).background(MaterialTheme.colorScheme.primary))
                    }
                    if (breakdown.toolsTokens > 0L) {
                        Box(Modifier.weight(toolsW).height(5.dp).background(semantic.warning))
                    }
                    if (breakdown.messageTokens > 0L) {
                        Box(Modifier.weight(msgW).height(5.dp).background(semantic.success))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    LegendDot(
                        color = MaterialTheme.colorScheme.primary,
                        text = tr("提示词 ", "Sys ") + formatCompactTokens(breakdown.systemTokens)
                    )
                    LegendDot(
                        color = semantic.warning,
                        text = tr("工具 ", "Tools ") + formatCompactTokens(breakdown.toolsTokens)
                    )
                    LegendDot(
                        color = semantic.success,
                        text = tr("消息 ", "Msgs ") + formatCompactTokens(breakdown.messageTokens)
                    )
                }
            }

            // Cache token counters
            if (tokenUsage.totalInputTokens > 0L || tokenUsage.outputTokens > 0L) {
                Text(
                    buildString {
                        append(tr("命中 ", "Hit "))
                        append(formatCompactTokens(tokenUsage.cacheReadTokens))
                        if (tokenUsage.cacheWriteTokens > 0L) {
                            append(tr(" · 写入 ", " · Write "))
                            append(formatCompactTokens(tokenUsage.cacheWriteTokens))
                        }
                        append(tr(" · 未命中 ", " · Miss "))
                        append(formatCompactTokens(tokenUsage.uncachedInputTokens))
                        append(tr(" · 输出 ", " · Out "))
                        append(formatCompactTokens(tokenUsage.outputTokens))
                        if (lastHitRate != null && sessionHitRate != null && kotlin.math.abs(lastHitRate - sessionHitRate) >= 0.01) {
                            append(tr(" · 最近一轮 ", " · Last "))
                            append(formatCacheHitPercent(lastHitRate))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Text(
                    tr("发送消息后将显示实时缓存命中与 Token 统计", "Send a message to see live cache hit and token stats"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun InspectorTabChip(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
        shadowElevation = if (selected) 1.dp else 0.dp,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(3.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SystemPromptTabBody(
    snapshot: ContextInspectSnapshot,
    onCopy: (label: String, text: String) -> Unit
) {
    val sp = snapshot.systemPrompt
    val expandedMap = remember(snapshot.sessionId) { mutableStateMapOf<String, Boolean>() }
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                tr(
                    "共 ${sp.sections.size} 段 · ${sp.totalChars} 字符 · 约 ${formatCompactTokens(sp.estimatedTokens)} tok",
                    "${sp.sections.size} sections · ${sp.totalChars} chars · ~${formatCompactTokens(sp.estimatedTokens)} tok"
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (sp.sections.isNotEmpty()) {
                IconButton(
                    onClick = {
                        val combined = sp.sections.joinToString("\n\n") { "### ${it.name}\n${it.preview}" }
                        onCopy("system_prompt", combined)
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = tr("复制系统提示词", "Copy system prompt"),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        if (sp.sections.isEmpty() && sp.contexts.isEmpty()) {
            Text(
                tr("未检测到已组装的系统提示词分段", "No assembled system prompt sections detected"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            for (sec in sp.sections) {
                val key = "sec:${sec.name}"
                SectionPreviewRow(
                    section = sec,
                    expanded = expandedMap[key] == true,
                    onToggle = { expandedMap[key] = !(expandedMap[key] == true) }
                )
            }
            if (sp.contexts.isNotEmpty()) {
                Text(
                    tr("动态运行时上下文 (${sp.contexts.size})", "Dynamic runtime contexts (${sp.contexts.size})"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp)
                )
                for (ctxSec in sp.contexts) {
                    val key = "ctx:${ctxSec.name}"
                    SectionPreviewRow(
                        section = ctxSec,
                        expanded = expandedMap[key] == true,
                        onToggle = { expandedMap[key] = !(expandedMap[key] == true) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionPreviewRow(
    section: ContextSectionInfo,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val semantic = LocalDshnColors.current
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = semantic.subtleBg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    section.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "${section.chars}c",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = section.preview + if (section.truncated) "\n…" else "",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(semantic.codeBg)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun ToolsTabBody(tools: List<ContextToolInfo>) {
    val semantic = LocalDshnColors.current
    val scroll = rememberScrollState()
    val mcpCount = tools.count { it.source == "mcp" }
    val builtinCount = tools.size - mcpCount

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            tr(
                "共 ${tools.size} 个工具 · 内置 $builtinCount · MCP $mcpCount",
                "${tools.size} tools · $builtinCount built-in · $mcpCount MCP"
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (tools.isEmpty()) {
            Text(
                tr("当前会话未挂载工具", "No tools registered in this session"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            for (tool in tools) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = semantic.subtleBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                tool.name,
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (tool.source == "mcp") {
                                StatusPill("MCP", PillTone.INFO)
                            }
                            if (tool.deferLoading) {
                                StatusPill(tr("按需", "Deferred"), PillTone.NEUTRAL)
                            }
                        }
                        if (tool.description.isNotBlank()) {
                            Text(
                                tool.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillsTabBody(skills: List<ContextSkillInfo>) {
    val semantic = LocalDshnColors.current
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (skills.isEmpty()) {
            Text(
                tr(
                    "当前工作区未发现 Skill。可将 SKILL.md 放入 ~/.dsh/skills 或项目的 .dsh/skills 目录。",
                    "No skills discovered. Place SKILL.md files in ~/.dsh/skills or <project>/.dsh/skills."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            Text(
                tr("已发现 ${skills.size} 个 Skill", "${skills.size} skills discovered"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            for (skill in skills) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = semantic.subtleBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                skill.name,
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            StatusPill(skill.provider, PillTone.NEUTRAL)
                        }
                        if (skill.description.isNotBlank()) {
                            Text(
                                skill.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        val skillPath = skill.path
                        if (!skillPath.isNullOrBlank()) {
                            Text(
                                skillPath,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
