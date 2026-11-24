package dev.dshn.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dshn.app.runtime.EnvManager
import dev.dshn.app.runtime.RuntimeLifecycleState
import dev.dshn.core.ui.BundledEnvProgressCard
import dev.dshn.core.ui.CheckRow
import dev.dshn.core.ui.ConfirmDialog
import dev.dshn.core.ui.EnvCheckUi
import dev.dshn.core.ui.InfoBanner
import dev.dshn.core.ui.PillTone
import dev.dshn.core.ui.RootfsPrepGuide
import dev.dshn.core.ui.SecondaryScaffold
import dev.dshn.core.ui.SettingsDivider
import dev.dshn.core.ui.SettingsGroup
import dev.dshn.core.ui.SettingsRow
import dev.dshn.core.ui.StatusPill
import dev.dshn.core.ui.buildEnvironmentChecks
import dev.dshn.core.ui.tr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EnvironmentSettingsScreen(
    vm: EnvironmentDiagnosticsViewModel,
    onBack: () -> Unit
) {
    val manifest by vm.envManifest.collectAsStateWithLifecycle()
    val bundled by vm.bundledEnvManifest.collectAsStateWithLifecycle()
    val install by vm.envInstallProgress.collectAsStateWithLifecycle()
    val checking by vm.envChecking.collectAsStateWithLifecycle()
    val runtime by vm.runtimeStatus.collectAsStateWithLifecycle()
    val snapshots by vm.snapshots.collectAsStateWithLifecycle()
    val selfTest by vm.selfTestReport.collectAsStateWithLifecycle()
    val selfTestRunning by vm.selfTestRunning.collectAsStateWithLifecycle()
    val upgrading by vm.dshUpgrading.collectAsStateWithLifecycle()
    val upgradeReport by vm.dshUpgradeReport.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.onEnvironmentEntered()
    }

    var confirmUpgrade by remember { mutableStateOf(false) }
    var confirmReinstall by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf<String?>(null) }
    var showManualGuide by rememberSaveable { mutableStateOf(false) }
    val timeFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    val usable = runtime.state == RuntimeLifecycleState.HEALTHY || runtime.state == RuntimeLifecycleState.DEGRADED
    val atTarget = EnvManager.isDshVersionAtTarget(manifest.dshVersion)

    SecondaryScaffold(title = tr("运行环境", "Runtime environment"), onBack = onBack) { inner ->
        SettingsList(inner) {
            if (install.inProgress) {
                item {
                    BundledEnvProgressCard(
                        stageIndex = install.stageIndex,
                        stageCount = install.stageCount,
                        stageLabelZh = install.stageLabelZh,
                        stageLabelEn = install.stageLabelEn,
                        bytesDone = install.bytesDone,
                        totalBytes = install.totalBytes,
                        fraction = install.fraction
                    )
                }
            }
            if (!install.error.isNullOrBlank() && !install.inProgress) {
                item {
                    InfoBanner(
                        title = tr("内置环境安装失败", "Bundled install failed"),
                        text = install.error.orEmpty(),
                        tone = PillTone.ERROR,
                        actionLabel = if (bundled != null) tr("重试", "Retry") else null,
                        onAction = if (bundled != null) ({ vm.installBundledEnvironment(reinstall = false) }) else null
                    )
                }
            }
            if (!manifest.rootfsReady && !install.inProgress) {
                item {
                    if (bundled != null) {
                        val b = bundled!!
                        InfoBanner(
                            title = tr("可一键安装内置运行环境", "Bundled runtime ready to install"),
                            text = tr(
                                "安装包已内置 Ubuntu ${b.ubuntuVersion} + Node.js ${b.nodeVersion} + DSH ${b.dshVersion}，点击下方按钮即可离线解压就绪。",
                                "This APK includes Ubuntu ${b.ubuntuVersion} + Node.js ${b.nodeVersion} + DSH ${b.dshVersion}. Tap below to unpack offline."
                            ),
                            tone = PillTone.INFO,
                            actionLabel = tr("立即安装", "Install now"),
                            onAction = { vm.installBundledEnvironment(reinstall = false) }
                        )
                    } else {
                        InfoBanner(
                            title = tr("还没有检测到运行环境", "Runtime environment not found"),
                            text = tr("按下面的步骤用电脑准备一次即可。", "Prepare it once with a computer using the steps below."),
                            tone = PillTone.WARNING
                        )
                    }
                }
                if (bundled == null || showManualGuide) {
                    item {
                        SettingsGroup(tr("手动准备步骤（ADB）", "Manual setup steps (ADB)")) {
                            Column(Modifier.padding(14.dp)) {
                                RootfsPrepGuide(
                                    targetDshVersion = EnvManager.TARGET_DSH_VERSION,
                                    requiredAllowScripts = EnvManager.REQUIRED_ALLOW_SCRIPTS,
                                    onCopy = { vm.copyToClipboard("commands", it) }
                                )
                            }
                        }
                    }
                }
            }
            item {
                val checks = buildEnvironmentChecks(
                    rootfsReady = manifest.rootfsReady,
                    nodeVersion = manifest.nodeVersion,
                    nodeCompatible = EnvManager.isNodeVersionCompatible(manifest.nodeVersion),
                    dshVersion = manifest.dshVersion,
                    dshAtTarget = atTarget,
                    targetDshVersion = EnvManager.TARGET_DSH_VERSION,
                    assetBundleHash = manifest.assetBundleHash
                )
                SettingsGroup(tr("组件状态", "Components")) {
                    checks.forEachIndexed { i, c ->
                        if (i > 0) SettingsDivider(indent = 52.dp)
                        CheckRow(c, checking || install.inProgress)
                    }
                    SettingsDivider(indent = 16.dp)
                    val (stateText, tone) = runtimeStateText(runtime.state)
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("运行状态", "Status"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (runtime.restartCount > 0) {
                            Text(
                                tr("已重启 ${runtime.restartCount} 次", "${runtime.restartCount} restarts"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        StatusPill(stateText, tone, showDot = true)
                    }
                }
            }
            item {
                SettingsGroup(tr("操作", "Actions")) {
                    if (usable) {
                        SettingsRow(tr("重启运行环境", "Restart runtime"), icon = Icons.Rounded.RestartAlt, enabled = !install.inProgress, onClick = { vm.restartRuntime() }, showChevron = false)
                        SettingsDivider()
                        SettingsRow(tr("停止运行环境", "Stop runtime"), icon = Icons.Rounded.Stop, enabled = !install.inProgress, onClick = { vm.stopRuntime() }, showChevron = false)
                    } else {
                        SettingsRow(
                            tr("启动运行环境", "Start runtime"),
                            icon = Icons.Rounded.PlayArrow,
                            enabled = manifest.rootfsReady && !install.inProgress && runtime.state != RuntimeLifecycleState.STARTING,
                            onClick = { vm.startRuntimeAndAttach() },
                            showChevron = false
                        )
                    }
                    bundled?.let { b ->
                        SettingsDivider()
                        SettingsRow(
                            if (manifest.rootfsReady) tr("修复 / 重装内置环境", "Repair / reinstall bundled runtime")
                            else tr("安装内置运行环境", "Install bundled runtime"),
                            subtitle = tr(
                                "离线解压 Ubuntu ${b.ubuntuVersion} · Node ${b.nodeVersion} · DSH ${b.dshVersion}（保留对话与 Key）",
                                "Offline Ubuntu ${b.ubuntuVersion} · Node ${b.nodeVersion} · DSH ${b.dshVersion} (keeps chats & key)"
                            ),
                            icon = Icons.Rounded.Storage,
                            enabled = !install.inProgress && !upgrading,
                            trailing = { if (install.inProgress) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
                            onClick = {
                                if (manifest.rootfsReady) confirmReinstall = true
                                else vm.installBundledEnvironment(reinstall = false)
                            },
                            showChevron = false
                        )
                    }
                    SettingsDivider()
                    SettingsRow(
                        tr("重新检测组件", "Re-check components"),
                        icon = Icons.Rounded.Refresh,
                        enabled = !checking && !install.inProgress,
                        onClick = { vm.refreshEnvironment() },
                        showChevron = false
                    )
                    SettingsDivider()
                    SettingsRow(
                        tr("运行自检", "Run self-test"),
                        icon = Icons.Rounded.Verified,
                        enabled = !selfTestRunning && !install.inProgress && manifest.rootfsReady,
                        trailing = { if (selfTestRunning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
                        onClick = { vm.runSelfTest() },
                        showChevron = false
                    )
                    SettingsDivider()
                    SettingsRow(
                        if (atTarget) tr("通过 npm 更新/重装 DSH ${EnvManager.TARGET_DSH_VERSION}", "Update/reinstall DSH ${EnvManager.TARGET_DSH_VERSION} via npm")
                        else tr("更新 DSH 到 ${EnvManager.TARGET_DSH_VERSION}", "Update DSH to ${EnvManager.TARGET_DSH_VERSION}"),
                        subtitle = tr("更新前会自动创建快照，失败可恢复", "A snapshot is taken first so you can roll back"),
                        icon = Icons.Rounded.Download,
                        enabled = manifest.rootfsReady && !upgrading && !install.inProgress,
                        trailing = { if (upgrading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
                        onClick = { confirmUpgrade = true },
                        showChevron = false
                    )
                }
            }
            selfTest?.let { report ->
                item {
                    SettingsGroup(
                        if (report.allPassed) tr("自检全部通过", "Self-test passed") else tr("自检发现问题", "Self-test found issues")
                    ) {
                        report.checks.forEachIndexed { i, c ->
                            if (i > 0) SettingsDivider(indent = 52.dp)
                            CheckRow(EnvCheckUi(c.name, c.detail, c.passed), checking = false, multilineDetail = true)
                        }
                    }
                }
            }
            upgradeReport?.let { up ->
                item {
                    InfoBanner(
                        title = tr("DSH 更新结果", "DSH update result"),
                        text = "${up.previousVersion.ifEmpty { "?" }} → ${up.installedVersion} · " +
                            (if (up.selfTestPassed) tr("自检通过", "self-test passed") else tr("自检未通过", "self-test failed")) +
                            (up.snapshotId?.let { tr("\n更新前快照：$it", "\nPre-update snapshot: $it") } ?: ""),
                        tone = if (up.selfTestPassed) PillTone.SUCCESS else PillTone.WARNING
                    )
                }
            }
            item {
                SettingsGroup(
                    tr("快照", "Snapshots"),
                    footer = tr("快照保存对话记录和 DSH 配置，不包含 API Key。恢复前会校验格式兼容性。", "Snapshots include chats and DSH config, never the API key. Compatibility is checked before restoring.")
                ) {
                    SettingsRow(
                        tr("立即创建快照", "Create snapshot now"),
                        icon = Icons.Rounded.CameraAlt,
                        enabled = manifest.rootfsReady,
                        onClick = { vm.createEnvSnapshot() },
                        showChevron = false
                    )
                    for (snap in snapshots.take(8)) {
                        SettingsDivider()
                        SettingsRow(
                            title = if (snap.createdAtMs > 0) timeFmt.format(Date(snap.createdAtMs)) else snap.id,
                            subtitle = tr("${snap.sessionCount} 个对话", "${snap.sessionCount} chats") +
                                if (snap.dshVersion.isNotBlank()) " · DSH ${snap.dshVersion}" else "",
                            icon = Icons.Rounded.Restore,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            showChevron = false,
                            trailing = {
                                TextButton(onClick = { confirmRestore = snap.id }) { Text(tr("恢复", "Restore")) }
                            }
                        )
                    }
                }
            }
        }
    }

    if (confirmReinstall) {
        ConfirmDialog(
            title = tr("重新安装内置运行环境？", "Reinstall bundled runtime?"),
            text = tr(
                "将停止运行环境并用安装包内置的 Ubuntu + Node.js + DSH 镜像重新解压覆盖（你在 ~/.dsh 中的对话记录、配置与 API Key 会自动保留）。",
                "The runtime will stop and re-unpack the bundled Ubuntu + Node.js + DSH image. Your chats, config and API key in ~/.dsh are preserved."
            ),
            confirmLabel = tr("开始重装", "Reinstall"),
            onConfirm = { vm.installBundledEnvironment(reinstall = true) },
            onDismiss = { confirmReinstall = false }
        )
    }
    if (confirmUpgrade) {
        ConfirmDialog(
            title = tr("更新 DSH？", "Update DSH?"),
            text = tr(
                "将停止运行环境，先创建快照，再通过 npm 安装 DSH ${EnvManager.TARGET_DSH_VERSION}，完成后自动自检并重启。需要联网，可能耗时几分钟。",
                "The runtime will stop, a snapshot will be taken, then DSH ${EnvManager.TARGET_DSH_VERSION} is installed via npm and self-tested. Needs network; may take a few minutes."
            ),
            confirmLabel = tr("开始更新", "Update"),
            onConfirm = { vm.upgradeDshToVersion() },
            onDismiss = { confirmUpgrade = false }
        )
    }
    confirmRestore?.let { id ->
        ConfirmDialog(
            title = tr("恢复这个快照？", "Restore this snapshot?"),
            text = tr("当前的对话记录和 DSH 配置会被快照内容替换（API Key 不受影响），运行环境会自动重启。", "Current chats and DSH config will be replaced by the snapshot (API key unaffected). The runtime restarts automatically."),
            confirmLabel = tr("恢复", "Restore"),
            destructive = true,
            onConfirm = { vm.restoreEnvSnapshot(id) },
            onDismiss = { confirmRestore = null }
        )
    }
}
