package dev.dshn.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dshn.app.runtime.DshnNativeCore
import dev.dshn.core.ui.BrandMark
import dev.dshn.core.ui.PillTone
import dev.dshn.core.ui.SecondaryScaffold
import dev.dshn.core.ui.SettingsDivider
import dev.dshn.core.ui.SettingsGroup
import dev.dshn.core.ui.StatusPill
import dev.dshn.core.ui.appVersionName
import dev.dshn.core.ui.tr

@Composable
fun AboutScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val manifest by vm.envManifest.collectAsStateWithLifecycle()
    SecondaryScaffold(title = tr("关于", "About"), onBack = onBack) { inner ->
        SettingsList(inner) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(12.dp))
                    BrandMark(size = 76.dp)
                    Spacer(Modifier.height(14.dp))
                    Text("DSHN", style = MaterialTheme.typography.headlineSmall)
                    Text("DeepSeek Harness Native", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    StatusPill("v${appVersionName(ctx)}", PillTone.INFO)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        tr(
                            "在安卓上以原生应用运行完整的 DeepSeek Harness 智能体：本地 Ubuntu 容器、标准 ACP 协议、零 DSH 补丁。",
                            "Run the full DeepSeek Harness agent as a native Android app: a local Ubuntu container, the standard ACP protocol and zero DSH patches."
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item {
                SettingsGroup(tr("组件", "Components")) {
                    AboutRow("DSH", manifest.dshVersion.ifBlank { tr("未安装", "Not installed") })
                    SettingsDivider(indent = 16.dp)
                    AboutRow("Node.js", manifest.nodeVersion.ifBlank { "—" })
                    SettingsDivider(indent = 16.dp)
                    AboutRow(tr("原生加速引擎", "Native Rust core"), DshnNativeCore.engineInfo())
                    SettingsDivider(indent = 16.dp)
                    AboutRow(tr("容器运行时", "Container runtime"), "bxroot v0.2.5")
                    SettingsDivider(indent = 16.dp)
                    AboutRow(tr("系统", "Rootfs"), "Ubuntu 24.04 arm64")
                    SettingsDivider(indent = 16.dp)
                    AboutRow(tr("通信协议", "Protocol"), "ACP (Agent Client Protocol)")
                }
            }
            item {
                SettingsGroup(
                    tr("开源", "Open source"),
                    footer = tr(
                        "DSHN 是社区开源项目，与 DeepSeek 官方无隶属关系。DeepSeek 是其所有者的商标。",
                        "DSHN is a community project, not affiliated with DeepSeek. DeepSeek is a trademark of its owner."
                    )
                ) {
                    AboutRow(tr("许可证", "License"), "MIT")
                    SettingsDivider(indent = 16.dp)
                    AboutRow(tr("第三方声明", "Third-party notices"), "THIRD_PARTY_NOTICES.md")
                }
            }
        }
    }
}

@Composable
private fun AboutRow(title: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
