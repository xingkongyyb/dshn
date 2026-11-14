package dev.dshn.app.data

import android.content.Context
import dev.dshn.app.security.KeystoreCrypto
import dev.dshn.app.security.SecretCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
enum class PermissionTier(val envValue: String, val displayName: String) {
    WORKSPACE_WRITE("workspace-write", "Workspace Write"),
    READ_ONLY("read-only", "Read Only"),
    DANGER_FULL_ACCESS("danger-full-access", "Full Access (Bash Enabled)")
}

@Serializable
enum class AppThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
enum class AppLocaleMode { SYSTEM, EN, ZH }

@Serializable
enum class AdbTransportMode { AUTO, PLAIN_TCP, TLS }

@Serializable
data class AppSettings(
    val permissionMode: PermissionTier = PermissionTier.WORKSPACE_WRITE,
    val selectedModel: String = "deepseek-v4-flash",
    val reasoningEffort: String = "medium",
    val extraWorkspaceBindHostPath: String? = null,
    val bridgeEnabled: Boolean = false,
    val allowAccessibilityRead: Boolean = false,
    val allowAccessibilityControl: Boolean = false,
    val allowAdbBridge: Boolean = false,
    val requirePerCallConfirmation: Boolean = true,
    val wakeLockDuringTurn: Boolean = true,
    val autoRestartRuntime: Boolean = true,
    val themeMode: AppThemeMode = AppThemeMode.LIGHT,
    val localeMode: AppLocaleMode = AppLocaleMode.SYSTEM,
    val selectedSessionId: String? = null,
    val lastAdbEndpoint: String? = null,
    val adbTransportMode: AdbTransportMode = AdbTransportMode.AUTO
)

/**
 * Persists app preferences in filesDir/security/settings.json and API key encrypted with
 * AndroidKeyStore in filesDir/security/secrets.enc (synced to root/.dsh/api_key for container use).
 */
class SettingsStore(
    filesDir: File,
    private val cipher: SecretCipher = KeystoreCrypto
) {
    constructor(context: Context) : this(context.filesDir)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val rootFilesDir = File(filesDir.absolutePath.replaceFirst("/data/user/0/", "/data/data/"))
    private val securityDir = File(rootFilesDir, "security")
    private val settingsFile = File(securityDir, "settings.json")
    private val secretsFile = File(securityDir, "secrets.enc")
    private val containerApiKeyFile = File(rootFilesDir, "linux/ubuntu/root/.dsh/api_key")

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _hasApiKey = MutableStateFlow(computeHasApiKey())
    val hasApiKey: StateFlow<Boolean> = _hasApiKey.asStateFlow()

    fun current(): AppSettings = _settings.value

    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val next = transform(_settings.value)
        _settings.value = next
        saveSettings(next)
        return next
    }

    fun getApiKey(): String? {
        if (secretsFile.exists()) {
            val fromKeystore = runCatching {
                String(cipher.decrypt(secretsFile.readBytes()), Charsets.UTF_8).trim()
            }.getOrNull()
            if (!fromKeystore.isNullOrBlank()) return fromKeystore
        }
        if (containerApiKeyFile.exists()) {
            val fromContainer = runCatching { containerApiKeyFile.readText().trim() }.getOrNull()
            if (!fromContainer.isNullOrBlank()) return fromContainer
        }
        return null
    }

    fun setApiKey(rawKey: String) {
        val trimmed = rawKey.trim()
        ensureSecurityDir()
        if (trimmed.isEmpty()) {
            if (secretsFile.exists()) secretsFile.delete()
            if (containerApiKeyFile.exists()) containerApiKeyFile.delete()
        } else {
            val blob = cipher.encrypt(trimmed.toByteArray(Charsets.UTF_8))
            atomicWriteBytes(secretsFile, blob)
            setOwnerOnlyPermissions(secretsFile)

            containerApiKeyFile.parentFile?.mkdirs()
            atomicWriteText(containerApiKeyFile, trimmed)
            setOwnerOnlyPermissions(containerApiKeyFile)
        }
        _hasApiKey.value = computeHasApiKey()
    }

    fun ensureContainerApiKeySynced() {
        if (containerApiKeyFile.exists() && containerApiKeyFile.readText().isNotBlank()) {
            setOwnerOnlyPermissions(containerApiKeyFile)
            return
        }
        val key = getApiKey() ?: return
        containerApiKeyFile.parentFile?.mkdirs()
        atomicWriteText(containerApiKeyFile, key)
        setOwnerOnlyPermissions(containerApiKeyFile)
    }

    private fun computeHasApiKey(): Boolean {
        if (secretsFile.exists() && secretsFile.length() > 0) return true
        return containerApiKeyFile.exists() && runCatching {
            containerApiKeyFile.readText().isNotBlank()
        }.getOrDefault(false)
    }

    private fun loadSettings(): AppSettings {
        if (!settingsFile.exists()) return AppSettings()
        return runCatching {
            json.decodeFromString<AppSettings>(settingsFile.readText(Charsets.UTF_8))
        }.getOrDefault(AppSettings())
    }

    private fun saveSettings(value: AppSettings) {
        ensureSecurityDir()
        atomicWriteText(settingsFile, json.encodeToString(value))
        setOwnerOnlyPermissions(settingsFile)
    }

    private fun ensureSecurityDir() {
        if (!securityDir.exists()) securityDir.mkdirs()
        securityDir.setReadable(false, false)
        securityDir.setReadable(true, true)
        securityDir.setWritable(false, false)
        securityDir.setWritable(true, true)
        securityDir.setExecutable(false, false)
        securityDir.setExecutable(true, true)
    }

    private fun setOwnerOnlyPermissions(file: File) {
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
        file.setExecutable(false, false)
    }

    private fun atomicWriteBytes(target: File, data: ByteArray) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(target)) {
            target.writeBytes(data)
            tmp.delete()
        }
    }

    private fun atomicWriteText(target: File, text: String) {
        atomicWriteBytes(target, text.toByteArray(Charsets.UTF_8))
    }
}
