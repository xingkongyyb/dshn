package dev.dshn.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.dshn.app.acp.AcpConfigChoice
import dev.dshn.app.acp.AcpConfigOption
import dev.dshn.core.ui.LocalDshnColors
import dev.dshn.core.ui.LocalIsZh
import dev.dshn.core.ui.tr

/**
 * Sets one ACP config option. [onResult] receives the complete option list after the change,
 * or `null` when nothing was applied (failure, chat switched, or chat not attached).
 */
internal typealias SetConfigOption = (configId: String, value: String, onResult: (List<AcpConfigOption>?) -> Unit) -> Unit

/** Screen size (dp) from which the model menu shows models and efforts side by side. */
internal const val MODEL_MENU_TWO_PANE_MIN_WIDTH_DP = 600
internal const val MODEL_MENU_TWO_PANE_MIN_HEIGHT_DP = 480

/** Widths of the model menu's panes; [twoPane] is false when two panes would not fit comfortably. */
internal data class ModelMenuLayout(val twoPane: Boolean, val listWidth: Dp, val effortWidth: Dp)

/**
 * Picks the menu layout for a screen. Pane widths grow with the font scale (clamped) and are capped by
 * the screen width; short screens (phone landscape) use the two-level layout even when wide.
 */
internal fun modelMenuLayout(screenWidthDp: Int, screenHeightDp: Int, fontScale: Float): ModelMenuLayout {
    val scale = fontScale.coerceIn(1f, 1.35f)
    val maxWidth = (screenWidthDp - 24).coerceAtLeast(200).toFloat()
    val list = 300f * scale
    val effort = 256f * scale
    val twoPane = screenWidthDp >= MODEL_MENU_TWO_PANE_MIN_WIDTH_DP &&
        screenHeightDp >= MODEL_MENU_TWO_PANE_MIN_HEIGHT_DP &&
        list + effort + 1f <= maxWidth
    return ModelMenuLayout(twoPane = twoPane, listWidth = minOf(list, maxWidth).dp, effortWidth = minOf(effort, maxWidth).dp)
}

/** The composer's config options split into the merged model/effort picker and the remaining chips. */
internal data class ComposerConfigGroups(
    val model: AcpConfigOption?,
    val effort: AcpConfigOption?,
    val others: List<AcpConfigOption>
)

internal fun groupComposerConfigOptions(options: List<AcpConfigOption>): ComposerConfigGroups {
    val usable = options.filter { it.choices.isNotEmpty() }
    val model = usable.firstOrNull { optionKind(it) == "model" }
    val effort = usable.firstOrNull { optionKind(it) == "effort" }
    // Without a model picker there is nothing to merge the effort into: keep it as its own chip.
    val merged = if (model != null) setOfNotNull(model, effort) else emptySet()
    return ComposerConfigGroups(
        model = model,
        effort = if (model != null) effort else null,
        others = usable.filter { it !in merged }
    )
}

private data class KnownEffort(val zhName: String, val enName: String, val zhDesc: String, val enDesc: String)

/** Reasoning-effort ids DSH providers advertise (DeepSeek: off / low / high / max; "" = provider default). */
private val KNOWN_EFFORTS: Map<String, KnownEffort> = mapOf(
    "" to KnownEffort("默认", "Default", "使用该模型自己的默认强度", "Use the model's default effort."),
    "off" to KnownEffort("关闭", "Off", "不思考，适合简单任务", "Use for simple tasks that do not need reasoning."),
    "none" to KnownEffort("关闭", "Off", "不思考，适合简单任务", "Use for simple tasks that do not need reasoning."),
    "minimal" to KnownEffort("极低", "Minimal", "只做很少的思考，回复最快", "Minimal reasoning for the fastest replies."),
    "low" to KnownEffort("低", "Low", "适合日常任务，或想快点得到回复时", "Prefer for routine or latency-sensitive tasks."),
    "medium" to KnownEffort("中", "Medium", "思考深度和速度兼顾", "Balanced reasoning and speed."),
    "high" to KnownEffort("高", "High", "适合大多数任务的默认平衡", "The default balance for most tasks."),
    "xhigh" to KnownEffort("很高", "Extra high", "更充分的思考，回复更慢", "Deeper reasoning with slower replies."),
    "max" to KnownEffort("最高", "Max", "最难、最看重质量的任务，回复最慢", "Reserve for the hardest quality-first tasks.")
)

/**
 * Display name and description for a reasoning-effort choice. Known ids get Chinese names in Chinese
 * UI; in English UI the provider's own wording wins. Unknown ids fall back to what the provider sent.
 */
internal fun effortLabel(value: String, name: String, description: String, isZh: Boolean): Pair<String, String> {
    val known = KNOWN_EFFORTS[value.lowercase()]
        ?: return name.ifBlank { value.ifBlank { if (isZh) "默认" else "Default" } } to description
    return if (isZh) {
        known.zhName to known.zhDesc
    } else {
        name.ifBlank { known.enName } to description.ifBlank { known.enDesc }
    }
}

@Composable
private fun effortLabel(choice: AcpConfigChoice): Pair<String, String> =
    effortLabel(choice.value, choice.name, choice.description, LocalIsZh.current)

@Composable
private fun currentEffortName(opt: AcpConfigOption): String {
    val choice = opt.choices.firstOrNull { it.value == opt.currentValue }
    return if (choice != null) effortLabel(choice).first
    else effortLabel(opt.currentValue, opt.currentValue, "", LocalIsZh.current).first
}

/**
 * One chip for model + reasoning effort. Tapping opens a compact menu: side by side on wide screens
 * (models | efforts), or two levels on phones (models → efforts of the chosen model).
 *
 * DSH only advertises the efforts of the *current* model, and switching model resets the effort to that
 * model's default, so picking another model switches immediately and then shows its efforts.
 */
@Composable
internal fun ModelEffortMenuChip(
    modelOpt: AcpConfigOption,
    effortOpt: AcpConfigOption?,
    onSetOption: SetConfigOption
) {
    var expanded by remember { mutableStateOf(false) }
    var showEfforts by remember { mutableStateOf(false) }
    var pendingModel by remember { mutableStateOf<String?>(null) }
    // Bumped whenever the menu opens/closes or a new switch starts, so late answers to older requests are ignored.
    var requestSeq by remember { mutableIntStateOf(0) }
    val config = LocalConfiguration.current
    val layout = modelMenuLayout(config.screenWidthDp, config.screenHeightDp, config.fontScale)
    val twoPane = layout.twoPane

    fun close() {
        expanded = false
        showEfforts = false
        pendingModel = null
        requestSeq++
    }

    val modelName = currentChoiceName(modelOpt)
    val label = if (effortOpt != null) "$modelName · ${currentEffortName(effortOpt)}" else modelName

    val onModelTap: (String) -> Unit = tap@{ value ->
        if (pendingModel != null) return@tap
        if (value == modelOpt.currentValue) {
            when {
                effortOpt == null -> close()
                !twoPane -> showEfforts = true
            }
            return@tap
        }
        pendingModel = value
        val token = ++requestSeq
        onSetOption(modelOpt.id, value) { updated ->
            if (token != requestSeq) return@onSetOption // menu was closed/reopened or another switch started
            pendingModel = null
            if (updated == null) return@onSetOption // failed: stay on the model list (a banner explains why)
            val hasEffort = groupComposerConfigOptions(updated).effort != null
            when {
                !hasEffort -> close()
                !twoPane -> showEfforts = true
            }
        }
    }
    val onEffortTap: (String) -> Unit = { value ->
        val opt = effortOpt
        if (opt != null && value != opt.currentValue) onSetOption(opt.id, value) { }
        close()
    }

    Box {
        MenuChip(
            icon = Icons.Rounded.Memory,
            label = label,
            expanded = expanded,
            tag = "model_menu_chip",
            onClick = {
                showEfforts = false
                pendingModel = null
                requestSeq++
                expanded = true
            }
        )
        MenuPopup(expanded = expanded, onDismiss = ::close) {
            when {
                twoPane -> Row(Modifier.height(IntrinsicSize.Min)) {
                    ModelList(
                        option = modelOpt,
                        pendingValue = pendingModel,
                        showChevron = false,
                        modifier = Modifier.width(layout.listWidth),
                        onTap = onModelTap
                    )
                    VerticalDivider(Modifier.fillMaxHeight(), color = LocalDshnColors.current.cardBorder)
                    EffortList(
                        option = effortOpt,
                        loading = pendingModel != null,
                        header = tr("思考强度", "Reasoning effort"),
                        onBack = null,
                        modifier = Modifier.width(layout.effortWidth),
                        onTap = onEffortTap
                    )
                }
                showEfforts && effortOpt != null -> EffortList(
                    option = effortOpt,
                    loading = false,
                    header = modelName,
                    onBack = { showEfforts = false },
                    modifier = Modifier.width(layout.listWidth),
                    onTap = onEffortTap
                )
                else -> ModelList(
                    option = modelOpt,
                    pendingValue = pendingModel,
                    showChevron = effortOpt != null,
                    modifier = Modifier.width(layout.listWidth),
                    onTap = onModelTap
                )
            }
        }
    }
}

/** A single-level menu chip for any other config option (or a lone effort option without a model picker). */
@Composable
internal fun OptionMenuChip(opt: AcpConfigOption, onSetOption: SetConfigOption) {
    var expanded by remember { mutableStateOf(false) }
    val config = LocalConfiguration.current
    val width = modelMenuLayout(config.screenWidthDp, config.screenHeightDp, config.fontScale).listWidth
    val isEffort = optionKind(opt) == "effort"
    val label = if (isEffort) tr("思考·", "Effort·") + currentEffortName(opt) else "${opt.name}·${currentChoiceName(opt)}"
    Box {
        MenuChip(
            icon = if (isEffort) Icons.Rounded.Lightbulb else Icons.Rounded.Tune,
            label = label,
            expanded = expanded,
            tag = "config_menu_chip_${opt.id}",
            onClick = { expanded = true }
        )
        MenuPopup(expanded = expanded, onDismiss = { expanded = false }) {
            Column(Modifier.width(width).padding(vertical = 6.dp)) {
                MenuHeader(if (isEffort) tr("思考强度", "Reasoning effort") else opt.name)
                var lastGroup: String? = null
                for (choice in opt.choices) {
                    if (choice.group.isNotBlank() && choice.group != lastGroup) {
                        lastGroup = choice.group
                        MenuHeader(choice.group)
                    }
                    val (name, desc) = if (isEffort) effortLabel(choice) else choice.name to choice.description
                    ChoiceRow(
                        title = name,
                        description = desc,
                        selected = choice.value == opt.currentValue,
                        tag = "config_menu_item_${opt.id}_${choice.value}",
                        onClick = {
                            if (choice.value != opt.currentValue) onSetOption(opt.id, choice.value) { }
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuChip(icon: ImageVector, label: String, expanded: Boolean, tag: String, onClick: () -> Unit) {
    val semantic = LocalDshnColors.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = if (expanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else semantic.subtleBg,
        modifier = Modifier.testTag(tag)
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 260.dp)
            )
            Icon(
                if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MenuPopup(expanded: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, LocalDshnColors.current.cardBorder)
    ) {
        content()
    }
}

@Composable
private fun MenuHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun ModelList(
    option: AcpConfigOption,
    pendingValue: String?,
    showChevron: Boolean,
    modifier: Modifier,
    onTap: (String) -> Unit
) {
    Column(modifier.padding(vertical = 6.dp)) {
        var lastGroup: String? = null
        for (choice in option.choices) {
            if (choice.group.isNotBlank() && choice.group != lastGroup) {
                lastGroup = choice.group
                MenuHeader(choice.group)
            }
            val selected = choice.value == option.currentValue
            ChoiceRow(
                title = choice.name,
                description = choice.description,
                selected = selected,
                tag = "model_menu_item_${choice.value}",
                trailing = when {
                    pendingValue == choice.value -> {
                        { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) }
                    }
                    showChevron -> {
                        {
                            Icon(
                                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = tr("选择思考强度", "Choose reasoning effort"),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    else -> null
                },
                onClick = { onTap(choice.value) }
            )
        }
    }
}

@Composable
private fun EffortList(
    option: AcpConfigOption?,
    loading: Boolean,
    header: String,
    onBack: (() -> Unit)?,
    modifier: Modifier,
    onTap: (String) -> Unit
) {
    Column(modifier.padding(vertical = 6.dp)) {
        if (onBack != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onBack)
                    .padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)
                    .testTag("model_menu_back"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = tr("返回模型列表", "Back to models"),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    header,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = LocalDshnColors.current.cardBorder)
            MenuHeader(tr("思考强度", "Reasoning effort"))
        } else if (loading) {
            // Same height as the plain header, so the menu does not jump while switching.
            Row(verticalAlignment = Alignment.CenterVertically) {
                MenuHeader(header)
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    tr("正在切换…", "Switching…"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        } else {
            MenuHeader(header)
        }
        when {
            option == null -> Text(
                tr("这个模型不支持调节思考强度", "This model has no reasoning-effort setting"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
            )
            // While switching, keep the old list in place (dimmed, not tappable) so the menu does not jump.
            else -> Column(Modifier.alpha(if (loading) 0.38f else 1f)) {
                for (choice in option.choices) {
                    val (name, desc) = effortLabel(choice)
                    ChoiceRow(
                        title = name,
                        description = desc,
                        selected = choice.value == option.currentValue,
                        enabled = !loading,
                        tag = "effort_menu_item_${choice.value.ifEmpty { "default" }}",
                        onClick = { onTap(choice.value) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    description: String,
    selected: Boolean,
    tag: String,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    val semantic = LocalDshnColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(10.dp))
            .then(if (selected) Modifier.background(semantic.subtleBg) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(start = 10.dp, end = 8.dp, top = 9.dp, bottom = 9.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (description.isNotBlank()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = tr("当前选择", "Selected"), modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
        if (trailing != null) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { trailing() }
        }
    }
}
