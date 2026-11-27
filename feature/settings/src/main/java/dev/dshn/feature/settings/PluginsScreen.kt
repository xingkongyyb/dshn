package dev.dshn.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dshn.app.runtime.PluginBundleInfo
import dev.dshn.app.runtime.RuntimeLifecycleState
import dev.dshn.core.ui.ConfirmDialog
import dev.dshn.core.ui.DshnSectionCard
import dev.dshn.core.ui.InfoBanner
import dev.dshn.core.ui.PillTone
import dev.dshn.core.ui.SecondaryScaffold
import dev.dshn.core.ui.SettingsDivider
import dev.dshn.core.ui.SettingsGroup
import dev.dshn.core.ui.StatusPill
import dev.dshn.core.ui.tr

@Composable
fun PluginsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit
) {
    val runtime by vm.runtimeStatus.collectAsStateWithLifecycle()
    val snapshot by vm.pluginsSnapshot.collectAsStateWithLifecycle()
    val loading by vm.pluginsLoading.collectAsStateWithLifecycle()
    val busyName by vm.pluginsBusyName.collectAsStateWithLifecycle()
    val error by vm.pluginsError.collectAsStateWithLifecycle()
    val restartRequired by vm.pluginsRestartRequired.collectAsStateWithLifecycle()

    var installSpec by rememberSaveable { mutableStateOf("") }
    var pendingRemove by remember { mutableStateOf<PluginBundleInfo?>(null) }

    val runtimeUsable = runtime.state == RuntimeLifecycleState.HEALTHY || runtime.state == RuntimeLifecycleState.DEGRADED

    LaunchedEffect(runtimeUsable) {
        if (runtimeUsable) {
            vm.refreshPlugins()
        }
    }

    val bundles = snapshot?.bundles.orEmpty()
    val installedExternal = remember(bundles) {
        bundles.filter { it.isProfileInstalled }.sortedBy { it.displayName.lowercase() }
    }
    val shippedOptional = remember(bundles) {
        bundles.filter { it.isShippedBuiltIn && !it.isProtected && it.optional && it.listedByDefault }
            .sortedWith(compareByDescending<PluginBundleInfo> { it.enabled }.thenBy { it.displayName.lowercase() })
    }
    val officialOnDemand = remember(bundles) {
        bundles.filter { it.isOnDemandMissing && it.official && it.listedByDefault }
            .sortedBy { it.displayName.lowercase() }
    }
    val coreBuiltIn = remember(bundles) {
        bundles.filter { it.isProtected || (it.isShippedBuiltIn && !it.optional) }
            .sortedBy { it.name.lowercase() }
    }

    SecondaryScaffold(
        title = tr("插件管理", "Plugins"),
        subtitle = snapshot?.let { "profile: ${it.profile} · ${bundles.count { b -> b.enabled }} " + tr("已启用", "enabled") },
        onBack = onBack,
        actions = {
            IconButton(
                onClick = { vm.refreshPlugins() },
                enabled = runtimeUsable && !loading && busyName == null,
                modifier = Modifier.testTag("plugins_refresh_button")
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Refresh, contentDescription = tr("刷新插件列表", "Refresh plugins"))
                }
            }
        }
    ) { inner ->
        SettingsList(inner) {
            // Runtime not ready banner
            if (!runtimeUsable) {
                item {
                    InfoBanner(
                        title = tr("运行环境尚未就绪", "Runtime is not running"),
                        text = tr(
                            "DSH 插件由本地运行环境托管。启动运行环境后即可查看、启用或安装插件。",
                            "DSH plugins are managed by the local runtime. Start the runtime to browse, toggle, or install plugins."
                        ),
                        tone = PillTone.WARNING,
                        actionLabel = if (runtime.state == RuntimeLifecycleState.STARTING) null else tr("启动运行环境", "Start runtime"),
                        progress = runtime.state == RuntimeLifecycleState.STARTING,
                        onAction = { vm.startRuntimeAndAttach() }
                    )
                }
            }

            // Restart required banner
            if (restartRequired) {
                item {
                    InfoBanner(
                        title = tr("需要重启运行环境", "Restart required"),
                        text = tr(
                            "ACP 模式未启用热重载，插件的启用、停用、安装或卸载需要重启运行环境后才能生效。",
                            "HMR is disabled in ACP mode. Restart the runtime for plugin changes to take effect."
                        ),
                        tone = PillTone.WARNING,
                        actionLabel = tr("立即重启", "Restart now"),
                        onAction = { vm.restartRuntime() }
                    )
                }
            }

            // Missing pnpm banner (needed for installing third-party / on-demand plugins)
            if (runtimeUsable && snapshot != null && snapshot?.available == true && snapshot?.pnpmAvailable == false) {
                item {
                    val installingPnpm = busyName == "__pnpm__"
                    InfoBanner(
                        title = tr("安装外部插件需要 pnpm", "pnpm required for external plugins"),
                        text = tr(
                            "下方「内置可选插件」可直接免下载开关。如需安装 npm / GitHub 第三方插件或官方按需插件，请先在容器内安装 pnpm。",
                            "Built-in optional bundles below can be toggled offline. To install npm/GitHub or on-demand bundles, install pnpm in the container first."
                        ),
                        tone = PillTone.INFO,
                        progress = installingPnpm,
                        actionLabel = if (installingPnpm) null else tr("安装 pnpm", "Install pnpm"),
                        onAction = { vm.installPnpm() }
                    )
                }
            }

            // Error banner
            if (!error.isNullOrBlank()) {
                item {
                    InfoBanner(
                        text = error.orEmpty(),
                        tone = PillTone.ERROR,
                        actionLabel = if (runtimeUsable) tr("重试", "Retry") else null,
                        onAction = { vm.refreshPlugins() }
                    )
                }
            }

            // Install third-party / npm / GitHub plugin card
            if (runtimeUsable) {
                item {
                    val installing = busyName == "__install__"
                    DshnSectionCard(
                        title = tr("安装插件", "Install plugin"),
                        subtitle = tr(
                            "支持 npm 包名或 GitHub 仓库（等同于 dsh plugin --profile acp add <包名>）",
                            "Supports npm package names or GitHub specs (equivalent to dsh plugin --profile acp add <spec>)"
                        ),
                        icon = Icons.Rounded.Extension
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = installSpec,
                                onValueChange = { installSpec = it },
                                singleLine = true,
                                enabled = !installing && busyName == null,
                                placeholder = {
                                    Text(
                                        "dsh-cost-meter",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("plugins_install_input")
                            )
                            Button(
                                onClick = {
                                    vm.installPlugin(installSpec) { ok ->
                                        if (ok) installSpec = ""
                                    }
                                },
                                enabled = installSpec.isNotBlank() && busyName == null,
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.testTag("plugins_install_button")
                            ) {
                                if (installing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(tr("安装", "Install"))
                                }
                            }
                        }
                    }
                }
            }

            // Initial loading state
            if (runtimeUsable && loading && snapshot == null) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    }
                }
            }

            // 1. User-installed external bundles
            if (installedExternal.isNotEmpty()) {
                item {
                    SettingsGroup(
                        title = tr("已安装的外部插件 (${installedExternal.size})", "Installed plugins (${installedExternal.size})")
                    ) {
                        installedExternal.forEachIndexed { idx, bundle ->
                            if (idx > 0) SettingsDivider(indent = 16.dp)
                            PluginBundleRow(
                                bundle = bundle,
                                busy = busyName == bundle.name,
                                anyBusy = busyName != null,
                                onToggle = { vm.setPluginEnabled(bundle.name, it) },
                                onInstall = { vm.installPlugin(bundle.name) },
                                onRequestRemove = { pendingRemove = bundle }
                            )
                        }
                    }
                }
            }

            // 2. Shipped official optional bundles (instant toggle, zero download)
            if (shippedOptional.isNotEmpty()) {
                item {
                    SettingsGroup(
                        title = tr("内置可选插件 (${shippedOptional.size})", "Built-in optional bundles (${shippedOptional.size})"),
                        footer = tr(
                            "以上插件已随 DSH 内置，直接开关并重启运行环境即可生效，无需联网下载。",
                            "These bundles ship with DSH and require no download. Toggle and restart the runtime to apply."
                        )
                    ) {
                        shippedOptional.forEachIndexed { idx, bundle ->
                            if (idx > 0) SettingsDivider(indent = 16.dp)
                            PluginBundleRow(
                                bundle = bundle,
                                busy = busyName == bundle.name,
                                anyBusy = busyName != null,
                                onToggle = { vm.setPluginEnabled(bundle.name, it) },
                                onInstall = { vm.installPlugin(bundle.name) },
                                onRequestRemove = null
                            )
                        }
                    }
                }
            }

            // 3. Official on-demand bundles
            if (officialOnDemand.isNotEmpty()) {
                item {
                    SettingsGroup(
                        title = tr("官方按需插件 (${officialOnDemand.size})", "Official on-demand bundles (${officialOnDemand.size})")
                    ) {
                        officialOnDemand.forEachIndexed { idx, bundle ->
                            if (idx > 0) SettingsDivider(indent = 16.dp)
                            PluginBundleRow(
                                bundle = bundle,
                                busy = busyName == bundle.name || busyName == "__install__",
                                anyBusy = busyName != null,
                                onToggle = { vm.setPluginEnabled(bundle.name, it) },
                                onInstall = { vm.installPlugin(bundle.name) },
                                onRequestRemove = null
                            )
                        }
                    }
                }
            }

            // 4. Core protected bundles
            if (coreBuiltIn.isNotEmpty()) {
                item {
                    SettingsGroup(
                        title = tr("核心内置组件 (${coreBuiltIn.size})", "Core bundles (${coreBuiltIn.size})"),
                        footer = tr(
                            "核心组件维持 ACP 与基础工具运行，处于受保护状态不可停用。",
                            "Core bundles power ACP and base tools and are protected from disabling."
                        )
                    ) {
                        coreBuiltIn.forEachIndexed { idx, bundle ->
                            if (idx > 0) SettingsDivider(indent = 16.dp)
                            PluginBundleRow(
                                bundle = bundle,
                                busy = false,
                                anyBusy = busyName != null,
                                onToggle = {},
                                onInstall = {},
                                onRequestRemove = null
                            )
                        }
                    }
                }
            }
        }
    }

    pendingRemove?.let { target ->
        ConfirmDialog(
            title = tr("卸载插件？", "Remove plugin?"),
            text = tr(
                "确定从 acp 配置中卸载「${target.displayName}」（${target.name}）吗？卸载后需要重启运行环境生效。",
                "Remove \"${target.displayName}\" (${target.name}) from the acp profile? A runtime restart is required."
            ),
            confirmLabel = tr("卸载", "Remove"),
            destructive = true,
            onConfirm = {
                vm.removePlugin(target.name)
                pendingRemove = null
            },
            onDismiss = { pendingRemove = null }
        )
    }
}

@Composable
private fun PluginBundleRow(
    bundle: PluginBundleInfo,
    busy: Boolean,
    anyBusy: Boolean,
    onToggle: (Boolean) -> Unit,
    onInstall: () -> Unit,
    onRequestRemove: (() -> Unit)?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = bundle.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!bundle.version.isNullOrBlank()) {
                        Text(
                            text = "v${bundle.version}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    when {
                        bundle.isProtected -> StatusPill(tr("核心", "Core"), PillTone.NEUTRAL)
                        bundle.enabled -> StatusPill(tr("已启用", "On"), PillTone.SUCCESS)
                        bundle.isOnDemandMissing -> StatusPill(tr("按需", "On-demand"), PillTone.INFO)
                    }
                }
                if (bundle.name != bundle.displayName) {
                    Text(
                        text = bundle.name,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            when {
                busy -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                bundle.isProtected -> Spacer(Modifier.width(4.dp))
                bundle.isOnDemandMissing -> {
                    FilledTonalButton(
                        onClick = onInstall,
                        enabled = !anyBusy,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tr("安装", "Install"), style = MaterialTheme.typography.labelMedium)
                    }
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (bundle.removable && onRequestRemove != null) {
                            IconButton(
                                onClick = onRequestRemove,
                                enabled = !anyBusy,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = tr("卸载", "Remove"),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        Switch(
                            checked = bundle.enabled,
                            onCheckedChange = onToggle,
                            enabled = !anyBusy
                        )
                    }
                }
            }
        }

        if (bundle.description.isNotBlank()) {
            Text(
                text = bundle.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (bundle.pluginCount > 0) {
            Text(
                text = tr("包含 ${bundle.pluginCount} 个子插件", "Contains ${bundle.pluginCount} plugin(s)"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        if (!bundle.errorMessage.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = tr("状态提示：${bundle.errorMessage}", "Status: ${bundle.errorMessage}"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
