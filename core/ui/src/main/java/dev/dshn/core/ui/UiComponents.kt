package dev.dshn.core.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class PillTone { NEUTRAL, INFO, SUCCESS, WARNING, ERROR }

@Composable
private fun toneColors(tone: PillTone): Triple<Color, Color, Color> {
    val semantic = LocalDshnColors.current
    val cs = MaterialTheme.colorScheme
    return when (tone) {
        PillTone.NEUTRAL -> Triple(cs.surfaceVariant, cs.onSurfaceVariant, cs.outline)
        PillTone.INFO -> Triple(cs.primaryContainer, cs.onPrimaryContainer, cs.primary)
        PillTone.SUCCESS -> Triple(semantic.successContainer, semantic.onSuccessContainer, semantic.success)
        PillTone.WARNING -> Triple(semantic.warningContainer, semantic.onWarningContainer, semantic.warning)
        PillTone.ERROR -> Triple(cs.errorContainer, cs.onErrorContainer, cs.error)
    }
}

@Composable
fun toneAccent(tone: PillTone): Color = toneColors(tone).third

@Composable
fun StatusPill(
    text: String,
    tone: PillTone = PillTone.NEUTRAL,
    showDot: Boolean = false,
    modifier: Modifier = Modifier
) {
    val (bg, fg, dot) = toneColors(tone)
    Surface(color = bg, shape = RoundedCornerShape(999.dp), modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (showDot) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(dot)
                )
            }
            Text(
                text = text,
                color = fg,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun StatusDot(tone: PillTone, modifier: Modifier = Modifier, size: Dp = 8.dp, pulsing: Boolean = false) {
    val color = toneAccent(tone)
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "dot")
        val a by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "dotAlpha"
        )
        a
    } else 1f
    Box(
        modifier = modifier
            .size(size)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
fun ThemeQuickToggleButton(
    strings: UiStrings,
    onToggle: (currentlyDark: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = LocalDshnColors.current.isDark
    IconButton(onClick = { onToggle(isDark) }, modifier = modifier.testTag("theme_quick_toggle")) {
        Icon(
            imageVector = if (isDark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
            contentDescription = if (isDark) strings.themeLight else strings.themeDark
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Brand mark (same artwork as the launcher icon, drawn natively so it scales crisply)
// ---------------------------------------------------------------------------------------------

private const val BUBBLE_PATH =
    "M41,38 H61 A11,11 0 0 1 72,49 V60 A11,11 0 0 1 61,71 H47 L36.5,79 A1.2,1.2 0 0 1 34.6,78 L36.2,70.2 A11,11 0 0 1 30,60 V49 A11,11 0 0 1 41,38 Z"
private const val CHEVRON_PATH = "M41,48.5 L48,54.5 L41,60.5"
private const val CURSOR_PATH = "M52.5,60.5 H61.5"
private const val SPARK_PATH =
    "M72,29 C72.7,33.3 73.7,34.3 78,35 C73.7,35.7 72.7,36.7 72,41 C71.3,36.7 70.3,35.7 66,35 C70.3,34.3 71.3,33.3 72,29 Z"

@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier) {
    val bubble = remember { PathParser().parsePathString(BUBBLE_PATH).toPath() }
    val chevron = remember { PathParser().parsePathString(CHEVRON_PATH).toPath() }
    val cursor = remember { PathParser().parsePathString(CURSOR_PATH).toPath() }
    val spark = remember { PathParser().parsePathString(SPARK_PATH).toPath() }
    Canvas(modifier = modifier.size(size)) {
        val unit = this.size.minDimension / 72f
        scale(scale = unit, pivot = Offset.Zero) {
            translate(left = -18f, top = -18f) {
                val clip = Path().apply {
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            left = 18f, top = 18f, right = 90f, bottom = 90f,
                            cornerRadius = CornerRadius(20f, 20f)
                        )
                    )
                }
                clipPath(clip) {
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(DshnBrandGradientStart, DshnBrandGradientEnd),
                            start = Offset(18f, 18f),
                            end = Offset(90f, 90f)
                        ),
                        topLeft = Offset(18f, 18f),
                        size = Size(72f, 72f)
                    )
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.28f), Color.Transparent),
                            center = Offset(32f, 24f),
                            radius = 70f
                        ),
                        topLeft = Offset(18f, 18f),
                        size = Size(72f, 72f)
                    )
                    drawPath(bubble, Color.White)
                    val stroke = Stroke(width = 4.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    drawPath(chevron, Color(0xFF3A50E6), style = stroke)
                    drawPath(cursor, Color(0xFF3A50E6), style = stroke)
                    drawPath(spark, Color.White)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Layout scaffolds
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecondaryScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("secondary_back_button")) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = tr("返回", "Back"))
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        content = content
    )
}

/** A rounded group of settings rows with an optional caption above and footnote below. */
@Composable
fun SettingsGroup(
    title: String?,
    modifier: Modifier = Modifier,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val semantic = LocalDshnColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
            )
        }
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, semantic.cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(content = content)
        }
        if (!footer.isNullOrBlank()) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp)
            )
        }
    }
}

@Composable
fun SettingsDivider(indent: Dp = 56.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = indent),
        thickness = 1.dp,
        color = LocalDshnColors.current.cardBorder
    )
}

@Composable
private fun RowIcon(icon: ImageVector, tint: Color) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = tint.copy(alpha = 0.12f),
        modifier = Modifier.size(32.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    subtitle: String? = null,
    enabled: Boolean = true,
    showChevron: Boolean = true,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            RowIcon(icon, iconTint)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor, fontWeight = FontWeight.Medium)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        if (onClick != null && showChevron) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    subtitle: String? = null,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            RowIcon(icon, iconTint)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** Contextual banner used for runtime status, missing prerequisites and warnings. */
@Composable
fun InfoBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: PillTone = PillTone.INFO,
    title: String? = null,
    icon: ImageVector? = null,
    progress: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    val (bg, fg, accent) = toneColors(tone)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = bg,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                when {
                    progress -> CircularProgressIndicator(
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(18.dp),
                        strokeWidth = 2.dp,
                        color = accent
                    )
                    else -> Icon(
                        icon ?: when (tone) {
                            PillTone.ERROR -> Icons.Rounded.ErrorOutline
                            PillTone.WARNING -> Icons.Rounded.WarningAmber
                            PillTone.SUCCESS -> Icons.Rounded.CheckCircle
                            else -> Icons.Rounded.Info
                        },
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (!title.isNullOrBlank()) {
                        Text(title, style = MaterialTheme.typography.titleSmall, color = fg)
                    }
                    Text(text, style = MaterialTheme.typography.bodySmall, color = fg)
                }
            }
            if (actionLabel != null || secondaryLabel != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (secondaryLabel != null && onSecondary != null) {
                        TextButton(onClick = onSecondary) { Text(secondaryLabel, color = fg) }
                    }
                    if (actionLabel != null && onAction != null) {
                        Spacer(Modifier.width(4.dp))
                        Button(
                            onClick = onAction,
                            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = bg),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                        ) { Text(actionLabel) }
                    }
                }
            }
        }
    }
}

@Composable
fun TypingDots(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until 3) {
            val a by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, delayMillis = i * 160),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "typingDot$i"
            )
            Box(
                Modifier
                    .size(7.dp)
                    .alpha(a)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

@Composable
fun DshnSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    highlighted: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val semantic = LocalDshnColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (highlighted) semantic.heroBg else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else semantic.cardBorder
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (icon != null) RowIcon(icon, MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
fun CopyableCodeBox(
    text: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    onCopy: (() -> Unit)? = null
) {
    val semantic = LocalDshnColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = semantic.codeBg,
        border = BorderStroke(1.dp, semantic.cardBorder)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (!label.isNullOrBlank() || onCopy != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = label.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    if (onCopy != null) {
                        IconButton(onClick = onCopy, modifier = Modifier.size(48.dp)) {
                            Icon(
                                Icons.Rounded.ContentCopy,
                                contentDescription = tr("复制", "Copy"),
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Generic dialogs
// ---------------------------------------------------------------------------------------------

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismissLabel: String = tr("取消", "Cancel")
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Button(
                onClick = {
                    onDismiss()
                    onConfirm()
                },
                colors = if (destructive) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                } else ButtonDefaults.buttonColors(),
                modifier = Modifier.testTag("confirm_dialog_confirm_button")
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("confirm_dialog_dismiss_button")) {
                Text(dismissLabel)
            }
        }
    )
}

@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                for ((value, label) in options) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onSelect(value)
                                onDismiss()
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = value == selected, onClick = null, modifier = Modifier.padding(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("关闭", "Close")) } }
    )
}
