package dev.dshn.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.dshn.app.data.PermissionTier

data class EnvCheckUi(
    val title: String,
    val detail: String,
    val ok: Boolean,
    val warn: Boolean = false
)

@Composable
fun buildEnvironmentChecks(
    rootfsReady: Boolean,
    nodeVersion: String,
    nodeCompatible: Boolean,
    dshVersion: String,
    dshAtTarget: Boolean,
    targetDshVersion: String,
    assetBundleHash: String
): List<EnvCheckUi> {
    val nodeOk = nodeVersion.isNotBlank() && nodeCompatible
    val dshInstalled = dshVersion.isNotBlank()
    return listOf(
        EnvCheckUi(
            tr("Ubuntu 运行环境", "Ubuntu environment"),
            if (rootfsReady) tr("已就绪", "Ready") else tr("未找到", "Not found"),
            rootfsReady
        ),
        EnvCheckUi(
            "Node.js",
            when {
                nodeVersion.isBlank() -> tr("未检测到", "Not detected")
                nodeOk -> nodeVersion
                else -> tr("$nodeVersion（需要 ≥ 24.9）", "$nodeVersion (needs ≥ 24.9)")
            },
            nodeOk
        ),
        EnvCheckUi(
            "DeepSeek Harness",
            when {
                !dshInstalled -> tr("未安装", "Not installed")
                dshAtTarget -> dshVersion
                else -> tr(
                    "$dshVersion（建议更新到 $targetDshVersion）",
                    "$dshVersion (update to $targetDshVersion recommended)"
                )
            },
            dshInstalled,
            warn = dshInstalled && !dshAtTarget
        ),
        EnvCheckUi(
            tr("桥接组件", "Bridge components"),
            if (assetBundleHash.isNotBlank()) tr("已同步", "Synced") else tr("等待运行环境", "Waiting for runtime"),
            assetBundleHash.isNotBlank()
        )
    )
}

@Composable
fun BundledEnvProgressCard(
    stageIndex: Int,
    stageCount: Int,
    stageLabelZh: String,
    stageLabelEn: String,
    bytesDone: Long,
    totalBytes: Long,
    fraction: Float,
    modifier: Modifier = Modifier
) {
    val semantic = LocalDshnColors.current
    val pct = (fraction * 100f).toInt().coerceIn(0, 100)
    val doneMb = bytesDone / (1024f * 1024f)
    val totalMb = totalBytes / (1024f * 1024f)
    val stageText = tr(stageLabelZh, stageLabelEn).ifBlank {
        tr("正在解压内置运行环境…", "Unpacking bundled runtime…")
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = semantic.heroBg,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    tr("正在初始化内置环境", "Initializing bundled runtime"),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "$pct%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    buildString {
                        if (stageCount > 0) append("$stageIndex/$stageCount · ")
                        append(stageText)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (totalBytes > 1024L * 1024L) {
                    Text(
                        String.format("%.0f / %.0f MB", doneMb, totalMb),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun CheckRow(c: EnvCheckUi, checking: Boolean, multilineDetail: Boolean = false) {
    val semantic = LocalDshnColors.current
    val stackDetail = multilineDetail || c.detail.length > 28 || c.detail.contains('\n')
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = if (stackDetail) Alignment.Top else Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .padding(top = if (stackDetail) 2.dp else 0.dp)
                .size(22.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                checking -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                c.ok && c.warn -> Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = semantic.warning)
                c.ok -> Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = semantic.success)
                else -> Icon(Icons.Rounded.Cancel, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.width(14.dp))
        if (stackDetail) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(c.title, style = MaterialTheme.typography.bodyLarge)
                if (c.detail.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            c.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            Text(c.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                c.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/** Step-by-step instructions for pushing the Ubuntu rootfs with a computer (manual bootstrap path). */
@Composable
fun RootfsPrepGuide(
    targetDshVersion: String,
    requiredAllowScripts: String,
    onCopy: (String) -> Unit
) {
    val pkg = "dev.dshn.app"
    val push = "adb push ubuntu-24.04-server-cloudimg-arm64-root.tar /data/local/tmp/"
    val extract = "adb shell run-as $pkg mkdir -p files/linux/ubuntu\n" +
        "adb shell run-as $pkg tar -xf /data/local/tmp/ubuntu-24.04-server-cloudimg-arm64-root.tar -C files/linux/ubuntu"
    val dsh = "npm install -g --allow-scripts=$requiredAllowScripts @deepseek-ai/dsh@$targetDshVersion"
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GuideStep(1, tr("下载 Ubuntu 24.04 arm64 根文件系统，并核对官方 SHA256。", "Download the Ubuntu 24.04 arm64 root filesystem and verify its official SHA256."))
        GuideStep(2, tr("用数据线连接电脑，开启 USB 调试，推送到手机：", "Connect via USB with debugging on, then push it:"))
        CopyableCodeBox(push, onCopy = { onCopy(push) })
        GuideStep(3, tr("解压到 DSHN 的私有目录：", "Extract into DSHN's private folder:"))
        CopyableCodeBox(extract, onCopy = { onCopy(extract) })
        GuideStep(4, tr("把 Node.js 24 放到容器的 /opt/node24，然后在 DSHN 终端里安装 DSH：", "Place Node.js 24 at /opt/node24 in the container, then install DSH from the DSHN terminal:"))
        CopyableCodeBox(dsh, onCopy = { onCopy(dsh) })
        GuideStep(5, tr("回到这里点「重新检测」。", "Come back and tap \"Check again\"."))
    }
}

@Composable
private fun GuideStep(n: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(22.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text("$n", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ApiKeyField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var reveal by remember { mutableStateOf(false) }
    val trimmed = value.trim()
    val looksOdd = trimmed.isNotEmpty() && !trimmed.startsWith("sk-")
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("API Key") },
        placeholder = { Text("sk-…") },
        singleLine = true,
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        trailingIcon = {
            IconButton(onClick = { reveal = !reveal }) {
                Icon(
                    if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (reveal) tr("隐藏", "Hide") else tr("显示", "Show")
                )
            }
        },
        supportingText = if (looksOdd) {
            { Text(tr("DeepSeek 的 Key 通常以 sk- 开头，请确认是否粘贴正确", "DeepSeek keys usually start with sk-")) }
        } else null,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("api_key_input")
    )
}

@Composable
fun tierLabel(tier: PermissionTier): String = when (tier) {
    PermissionTier.READ_ONLY -> tr("只读", "Read only")
    PermissionTier.WORKSPACE_WRITE -> tr("工作区可写", "Workspace write")
    PermissionTier.DANGER_FULL_ACCESS -> tr("完全访问", "Full access")
}

@Composable
private fun tierDescription(tier: PermissionTier): String = when (tier) {
    PermissionTier.READ_ONLY -> tr(
        "只能查看文件，不能修改，也不能运行命令。适合让它帮你读代码、解释问题。",
        "Can read files only — no edits, no commands. Good for reviewing and explaining."
    )
    PermissionTier.WORKSPACE_WRITE -> tr(
        "可以在工作目录中读写文件，写入前会征求你的同意。此设备内核不支持 Landlock 沙箱，所以 bash 命令不可用。",
        "Can read and write files in the workspace with your approval. Bash is unavailable because this kernel lacks Landlock sandboxing."
    )
    PermissionTier.DANGER_FULL_ACCESS -> tr(
        "可以在容器里直接运行 bash 命令，能完成大多数编程任务。容器不是安全沙箱，请只交给它你信任的任务。",
        "Can run bash directly in the container — needed for most coding tasks. The container is not a security sandbox."
    )
}

/** Selectable cards for the three DSH execution tiers. */
@Composable
fun PermissionTierCards(selected: PermissionTier, onSelect: (PermissionTier) -> Unit) {
    val semantic = LocalDshnColors.current
    val order = listOf(PermissionTier.READ_ONLY, PermissionTier.WORKSPACE_WRITE, PermissionTier.DANGER_FULL_ACCESS)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (tier in order) {
            val isSel = selected == tier
            val danger = tier == PermissionTier.DANGER_FULL_ACCESS
            val accent = if (danger) semantic.warning else MaterialTheme.colorScheme.primary
            Surface(
                onClick = { onSelect(tier) },
                shape = RoundedCornerShape(18.dp),
                color = if (isSel) (if (danger) semantic.warningContainer else semantic.heroBg) else MaterialTheme.colorScheme.surface,
                border = BorderStroke(if (isSel) 1.5.dp else 1.dp, if (isSel) accent else semantic.cardBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("permission_tier_${tier.name.lowercase()}")
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    RadioButton(selected = isSel, onClick = null, modifier = Modifier.padding(top = 2.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                when (tier) {
                                    PermissionTier.READ_ONLY -> Icons.Rounded.Visibility
                                    PermissionTier.WORKSPACE_WRITE -> Icons.Rounded.Folder
                                    PermissionTier.DANGER_FULL_ACCESS -> Icons.Rounded.Terminal
                                },
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(tierLabel(tier), style = MaterialTheme.typography.titleMedium)
                            if (tier == PermissionTier.WORKSPACE_WRITE) {
                                Spacer(Modifier.width(8.dp))
                                StatusPill(tr("默认", "Default"), PillTone.INFO)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            tierDescription(tier),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
