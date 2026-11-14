package dev.dshn.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.dshn.app.data.AppLocaleMode
import dev.dshn.app.data.AppThemeMode
import dev.dshn.app.data.PermissionTier

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    vm: MainViewModel,
    strings: UiStrings,
    modifier: Modifier = Modifier
) {
    val semantic = LocalDshnColors.current
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val hasApiKey by vm.hasApiKey.collectAsState()
    val accConnected by vm.accessibilityConnected.collectAsState()
    val adbEndpoints by vm.discoveredAdbEndpoints.collectAsState()
    val adbScanning by vm.adbScanning.collectAsState()
    val envManifest by vm.envManifest.collectAsState()
    val snapshots by vm.snapshots.collectAsState()
    val selfTestReport by vm.selfTestReport.collectAsState()
    val selfTestRunning by vm.selfTestRunning.collectAsState()
    val runtimeLogs by vm.runtimeLogs.collectAsState()
    val auditEntries by vm.auditEntries.collectAsState()

    var apiKeyDraft by remember { mutableStateOf("") }
    var revealKey by remember { mutableStateOf(false) }
    var manualAdbEndpoint by remember(settings.lastAdbEndpoint) {
        mutableStateOf(settings.lastAdbEndpoint ?: "127.0.0.1:5555")
    }
    var workspaceBindDraft by remember(settings.extraWorkspaceBindHostPath) {
        mutableStateOf(settings.extraWorkspaceBindHostPath.orEmpty())
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(strings.settingsTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                ThemeQuickToggleButton(strings = strings, onToggle = { vm.toggleLightDarkTheme(it) })
            }
        }

        // 1. Appearance & Language (Light / Dark / System switch right up front)
        item {
            DshnSectionCard(
                title = strings.appearanceSection,
                icon = Icons.Default.Palette,
                trailing = {
                    StatusPill(
                        text = if (semantic.isDark) strings.themeDark else strings.themeLight,
                        tone = if (semantic.isDark) PillTone.NEUTRAL else PillTone.INFO,
                        showDot = true
                    )
                }
            ) {
                Text(strings.themeLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val themeItems = listOf(
                        Triple(AppThemeMode.LIGHT, strings.themeLight, Icons.Default.LightMode),
                        Triple(AppThemeMode.DARK, strings.themeDark, Icons.Default.DarkMode),
                        Triple(AppThemeMode.SYSTEM, strings.themeSystem, Icons.Default.BrightnessAuto)
                    )
                    for ((mode, label, modeIcon) in themeItems) {
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { vm.setThemeMode(mode) },
                            leadingIcon = {
                                Icon(modeIcon, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            label = { Text(label) }
                        )
                    }
                }
                HorizontalDivider(color = semantic.cardBorder, modifier = Modifier.padding(vertical = 2.dp))
                Text(strings.languageLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (loc in AppLocaleMode.entries) {
                        FilterChip(
                            selected = settings.localeMode == loc,
                            onClick = { vm.setLocaleMode(loc) },
                            label = { Text(loc.name) }
                        )
                    }
                }
            }
        }

        // 1. Credentials & Model disclosure
        item {
            DshnSectionCard(
                title = strings.credentialsSection,
                icon = Icons.Default.Key,
                trailing = {
                    StatusPill(
                        text = if (hasApiKey) "CONFIGURED" else "MISSING",
                        tone = if (hasApiKey) PillTone.SUCCESS else PillTone.ERROR,
                        showDot = true
                    )
                }
            ) {
                OutlinedTextField(
                    value = apiKeyDraft,
                    onValueChange = { apiKeyDraft = it },
                    label = { Text("Enter DeepSeek API Key (sk-...)") },
                    visualTransformation = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(onClick = { revealKey = !revealKey }) {
                        Text(if (revealKey) "Hide" else "Show")
                    }
                    Button(
                        enabled = apiKeyDraft.isNotBlank(),
                        onClick = {
                            vm.saveApiKey(apiKeyDraft)
                            apiKeyDraft = ""
                        }
                    ) { Text("Save Key") }
                    OutlinedButton(onClick = { vm.saveApiKey("") }) { Text("Clear") }
                }
                Text(
                    "Security notice: Dormant key is AES-256-GCM encrypted with AndroidKeyStore in files/security/secrets.enc and synced with 0600 permissions to container root/.dsh/api_key. Agent code running inside the container shares your Android app UID.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 2. Execution Permission Tier & optional workspace bind
        item {
            DshnSectionCard(
                title = strings.permissionTierSection,
                icon = Icons.Default.Security
            ) {
                for (tier in PermissionTier.entries) {
                    val selected = settings.permissionMode == tier
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) semantic.heroBg else semantic.subtleBg,
                        border = BorderStroke(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else semantic.cardBorder
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.setPermissionTier(tier) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = { vm.setPermissionTier(tier) }
                            )
                            Column(Modifier.padding(start = 4.dp)) {
                                Text(tier.displayName, fontWeight = FontWeight.SemiBold)
                                Text(
                                    when (tier) {
                                        PermissionTier.WORKSPACE_WRITE -> "Files restricted via fs-sandbox + interactive approval. Bash tool blocked (no kernel Landlock)."
                                        PermissionTier.READ_ONLY -> "Read-only file inspection + approval. Writes and bash disabled."
                                        PermissionTier.DANGER_FULL_ACCESS -> "Enables direct container bash execution inside bxroot rootfs (no Landlock sandbox)."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = semantic.cardBorder, modifier = Modifier.padding(vertical = 4.dp))
                OutlinedTextField(
                    value = workspaceBindDraft,
                    onValueChange = { workspaceBindDraft = it },
                    label = { Text("Optional host workspace dir -> /mnt/workspace") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        vm.updateSettings {
                            it.copy(extraWorkspaceBindHostPath = workspaceBindDraft.trim().ifEmpty { null })
                        }
                    }) { Text("Save Bind Path") }
                    OutlinedButton(onClick = { vm.restartRuntime() }) { Text("Restart Runtime") }
                }
            }
        }

        // 3. Device Bridge (all OFF by default) + Shortcut to Trial Lab
        item {
            DshnSectionCard(
                title = strings.deviceBridgeSection,
                icon = Icons.Default.DeveloperBoard,
                trailing = {
                    StatusPill(
                        text = if (settings.bridgeEnabled) "ON" else "OFF",
                        tone = if (settings.bridgeEnabled) PillTone.SUCCESS else PillTone.NEUTRAL,
                        showDot = true
                    )
                }
            ) {
                Button(onClick = { vm.selectTab(MainTab.LAB) }) {
                    Text("打开设备桥与电脑 ADB 试验台")
                }

                ToggleRow(
                    title = "Enable Device Bridge MCP",
                    subtitle = "Master switch for device-mcp.sock tools",
                    checked = settings.bridgeEnabled
                ) { v -> vm.updateSettings { it.copy(bridgeEnabled = v) } }

                ToggleRow(
                    title = "Accessibility & Sensor Read",
                    subtitle = "Allows device_screen_tree, device_screenshot, device_sensors, device_info",
                    checked = settings.allowAccessibilityRead
                ) { v -> vm.updateSettings { it.copy(allowAccessibilityRead = v) } }

                ToggleRow(
                    title = "Accessibility UI Control",
                    subtitle = "Allows device_tap, device_swipe, device_input, device_global_action",
                    checked = settings.allowAccessibilityControl
                ) { v -> vm.updateSettings { it.copy(allowAccessibilityControl = v) } }

                ToggleRow(
                    title = "Wireless / TCP ADB Argv Bridge",
                    subtitle = "Allows structured allowlisted device_adb_exec commands",
                    checked = settings.allowAdbBridge
                ) { v -> vm.updateSettings { it.copy(allowAdbBridge = v) } }

                ToggleRow(
                    title = "Require Per-Call User Confirmation",
                    subtitle = "High-risk taps, input, global actions, and ADB commands prompt every time",
                    checked = settings.requirePerCallConfirmation
                ) { v -> vm.updateSettings { it.copy(requirePerCallConfirmation = v) } }

                HorizontalDivider(color = semantic.cardBorder, modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("AccessibilityService", fontWeight = FontWeight.SemiBold)
                    StatusPill(
                        text = if (accConnected) "CONNECTED" else "NOT CONNECTED",
                        tone = if (accConnected) PillTone.SUCCESS else PillTone.NEUTRAL,
                        showDot = true
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) { Text("Accessibility Settings") }
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                        )
                    }) { Text("App Info (Restricted)") }
                }
                Text(
                    "Android 13+ sideloaded APKs restrict Accessibility by default: open App Info -> tap top-right menu -> 'Allow restricted settings', then enable 'DSHN Device Bridge' in Accessibility Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(color = semantic.cardBorder, modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("ADB Endpoints (${adbEndpoints.size})", fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = { vm.toggleAdbDiscovery() }) {
                        Text(if (adbScanning) "Stop NSD Scan" else "Scan NSD")
                    }
                }
                for (ep in adbEndpoints) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${ep.serviceName} (${ep.host}:${ep.port})",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                        OutlinedButton(onClick = {
                            manualAdbEndpoint = "${ep.host}:${ep.port}"
                            vm.testAdbConnection(ep.host, ep.port, settings.adbTransportMode)
                        }) { Text("Test") }
                    }
                }
                OutlinedTextField(
                    value = manualAdbEndpoint,
                    onValueChange = { manualAdbEndpoint = it },
                    label = { Text("Connect host:port (e.g. 127.0.0.1:5555)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = manualAdbEndpoint.contains(':'),
                    onClick = {
                        val parts = manualAdbEndpoint.trim().split(':')
                        val p = parts.getOrNull(1)?.toIntOrNull()
                        if (p != null) vm.testAdbConnection(parts[0], p, settings.adbTransportMode)
                    }
                ) { Text("Test ADB Connection") }

                HorizontalDivider(color = semantic.cardBorder, modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Device Audit Log (${auditEntries.size})", fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = { vm.clearAuditEntries() }) { Text("Clear Audit") }
                }
                if (auditEntries.isNotEmpty()) {
                    CopyableCodeBox(
                        text = auditEntries.take(8).joinToString("\n") {
                            "[${it.decision}] ${it.tool}: ${it.summary}"
                        }
                    )
                }
            }
        }

        // 4. Keep-Alive & Android Phantom Process Killer Guide
        item {
            DshnSectionCard(
                title = strings.keepAliveSection,
                icon = Icons.Default.BatteryChargingFull
            ) {
                ToggleRow(
                    title = "WakeLock During Active Turns",
                    subtitle = "Holds PARTIAL_WAKE_LOCK only while agent turn runs",
                    checked = settings.wakeLockDuringTurn
                ) { v -> vm.updateSettings { it.copy(wakeLockDuringTurn = v) } }

                ToggleRow(
                    title = "Bounded Auto-Restart Runtime",
                    subtitle = "Automatically restarts crashed container (<= 3 times / 60s)",
                    checked = settings.autoRestartRuntime
                ) { v -> vm.updateSettings { it.copy(autoRestartRuntime = v) } }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        OutlinedButton(onClick = {
                            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }) { Text("Notification Perm") }
                    }
                    Button(onClick = {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        runCatching { context.startActivity(intent) }.onFailure {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }) { Text("Battery Unrestricted") }
                }
                CopyableCodeBox(
                    label = "Android 12+ Phantom Process Killer (optional ADB tuning)",
                    text = "adb shell \"/system/bin/device_config put activity_manager max_phantom_processes 2147483647\"",
                    onCopy = {
                        vm.copyToClipboard(
                            "Phantom Process Command",
                            "adb shell \"/system/bin/device_config put activity_manager max_phantom_processes 2147483647\""
                        )
                    }
                )
            }
        }

        // 5. Environment & Snapshots
        item {
            DshnSectionCard(
                title = strings.environmentSection,
                icon = Icons.Default.Storage,
                trailing = {
                    StatusPill(
                        text = if (envManifest.rootfsReady) "READY" else "INIT",
                        tone = if (envManifest.rootfsReady) PillTone.SUCCESS else PillTone.WARNING,
                        showDot = true
                    )
                }
            ) {
                Text(
                    "rootfsReady=${envManifest.rootfsReady} · dsh=${envManifest.dshVersion.ifEmpty { "unknown" }} · node=${envManifest.nodeVersion.ifEmpty { "unknown" }}\nassetHash=${envManifest.assetBundleHash.take(16)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(onClick = { vm.createEnvSnapshot() }) { Text("Create Snapshot") }
                    OutlinedButton(
                        enabled = !selfTestRunning,
                        onClick = { vm.runSelfTest() }
                    ) { Text(if (selfTestRunning) "Testing..." else "Run Self-Test") }
                }
                if (selfTestRunning) LinearProgressIndicator(Modifier.fillMaxWidth())

                selfTestReport?.let { report ->
                    Surface(
                        color = if (report.allPassed) semantic.successContainer else MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                if (report.allPassed) "Self-Test: ALL PASSED" else "Self-Test: ISSUES FOUND",
                                fontWeight = FontWeight.Bold,
                                color = if (report.allPassed) semantic.onSuccessContainer else MaterialTheme.colorScheme.onErrorContainer
                            )
                            for (c in report.checks) {
                                Text(
                                    "${if (c.passed) "[OK]" else "[FAIL]"} ${c.name}: ${c.detail}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (report.allPassed) semantic.onSuccessContainer else MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                for (snap in snapshots.take(6)) {
                    Surface(
                        color = semantic.subtleBg,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${snap.id} (${snap.sessionCount} sessions)",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                            OutlinedButton(onClick = { vm.restoreEnvSnapshot(snap.id) }) { Text("Restore") }
                        }
                    }
                }
            }
        }

        // 6. Diagnostics & Logs
        item {
            DshnSectionCard(
                title = strings.diagnosticsSection,
                icon = Icons.Default.BugReport
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(onClick = { vm.exportSanitizedDiagnosticsToClipboard() }) {
                        Text("Export Sanitized Diagnostics")
                    }
                    OutlinedButton(onClick = { vm.refreshLogsAndStatus() }) { Text("Refresh") }
                    OutlinedButton(onClick = { vm.clearRuntimeLogs() }) { Text("Clear") }
                }
                if (runtimeLogs.isNotEmpty()) {
                    CopyableCodeBox(
                        text = runtimeLogs.takeLast(40).joinToString("\n")
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
