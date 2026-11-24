package dev.dshn.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dshn.core.ui.ApiKeyField
import dev.dshn.core.ui.ConfirmDialog
import dev.dshn.core.ui.DEEPSEEK_API_KEYS_URL
import dev.dshn.core.ui.InfoBanner
import dev.dshn.core.ui.LocalDshnColors
import dev.dshn.core.ui.PillTone
import dev.dshn.core.ui.SecondaryScaffold
import dev.dshn.core.ui.SettingsGroup
import dev.dshn.core.ui.SettingsRow
import dev.dshn.core.ui.openUrl
import dev.dshn.core.ui.tr

@Composable
fun ModelSettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val hasApiKey by vm.hasApiKey.collectAsStateWithLifecycle()
    val masked by vm.apiKeyMasked.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    var savingKey by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var baseUrl by remember(settings.deepseekBaseUrl) { mutableStateOf(settings.deepseekBaseUrl.orEmpty()) }
    var searchUrl by remember(settings.deepseekSearchBaseUrl) { mutableStateOf(settings.deepseekSearchBaseUrl.orEmpty()) }
    var provider by remember(settings.webSearchProvider) { mutableStateOf(settings.webSearchProvider) }

    LaunchedEffect(Unit) {
        vm.onModelSettingsEntered()
    }

    SecondaryScaffold(title = tr("模型与 API Key", "Model & API key"), onBack = onBack) { inner ->
        SettingsList(inner) {
            item {
                SettingsGroup(
                    tr("DeepSeek API Key", "DeepSeek API key"),
                    footer = tr(
                        "Key 用 AndroidKeyStore 加密保存。为了让 DSH 使用，容器内会保留一份仅本应用可读的副本。建议使用专门的低额度 Key。",
                        "Encrypted with AndroidKeyStore. A copy readable only by this app is kept in the container for DSH. Prefer a dedicated low-limit key."
                    )
                ) {
                    SettingsRow(
                        title = if (hasApiKey) tr("已设置", "Configured") else tr("未设置", "Not set"),
                        subtitle = masked ?: if (hasApiKey) "sk-••••••••" else tr("输入 Key 后才能开始对话", "Required before chatting"),
                        icon = Icons.Rounded.Key,
                        iconTint = if (hasApiKey) LocalDshnColors.current.success else LocalDshnColors.current.warning,
                        showChevron = false
                    )
                    Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ApiKeyField(draft, onValueChange = { draft = it })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                enabled = draft.isNotBlank() && !savingKey,
                                modifier = Modifier.testTag("settings_save_api_key_button"),
                                onClick = {
                                    savingKey = true
                                    vm.saveApiKey(draft) { ok ->
                                        savingKey = false
                                        if (ok) draft = ""
                                    }
                                }
                            ) {
                                Text(
                                    when {
                                        savingKey -> tr("保存中…", "Saving…")
                                        hasApiKey -> tr("更换", "Replace")
                                        else -> tr("保存", "Save")
                                    }
                                )
                            }
                            if (hasApiKey) {
                                OutlinedButton(
                                    onClick = { confirmClear = true },
                                    modifier = Modifier.testTag("settings_clear_api_key_button")
                                ) { Text(tr("清除", "Clear")) }
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { openUrl(ctx, DEEPSEEK_API_KEYS_URL) }) {
                                Text(tr("获取 Key", "Get a key"))
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                }
            }
            item {
                SettingsGroup(
                    tr("接口与联网搜索（高级）", "Endpoint & web search (advanced)"),
                    footer = tr("留空即使用 DeepSeek 官方接口。修改后需要重启运行环境。", "Leave empty for the official API. Restart the runtime after changing.")
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text(tr("API 地址（DEEPSEEK_BASE_URL）", "API base URL (DEEPSEEK_BASE_URL)")) },
                            placeholder = { Text("https://api.deepseek.com") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = searchUrl,
                            onValueChange = { searchUrl = it },
                            label = { Text(tr("搜索网关地址（可选）", "Search gateway URL (optional)")) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(tr("联网搜索方式", "Web search provider"), style = MaterialTheme.typography.labelLarge)
                        val providers = listOf(
                            "" to tr("自动（推荐）", "Auto (recommended)"),
                            "deepseek-official" to tr("DeepSeek 官方消息搜索", "DeepSeek official (messages)"),
                            "deepseek-search-api" to tr("DeepSeek Search API", "DeepSeek Search API")
                        )
                        for ((value, label) in providers) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { provider = value }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = provider == value, onClick = null, modifier = Modifier.padding(8.dp))
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Button(
                            onClick = { vm.saveEndpointAndSearchConfig(baseUrl, searchUrl, provider) },
                            modifier = Modifier
                                .align(Alignment.End)
                                .testTag("settings_save_endpoint_button")
                        ) { Text(tr("保存", "Save")) }
                    }
                }
            }
            item {
                InfoBanner(
                    text = tr(
                        "模型和思考强度可以在对话输入框下方的按钮里随时切换，每个对话单独生效。",
                        "Switch the model and reasoning effort from the chips under the chat input; each chat keeps its own choice."
                    ),
                    tone = PillTone.NEUTRAL
                )
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = tr("清除 API Key？", "Clear API key?"),
            text = tr("清除后 DSH 将无法调用模型，直到你重新设置。", "DSH won't be able to call the model until you set a new key."),
            confirmLabel = tr("清除", "Clear"),
            destructive = true,
            onConfirm = { vm.saveApiKey("") },
            onDismiss = { confirmClear = false }
        )
    }
}
