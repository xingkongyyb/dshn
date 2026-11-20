package dev.dshn.app.data

import dev.dshn.app.security.RootPolicyMode
import dev.dshn.app.security.SecretCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsStoreTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    private val xorCipher = object : SecretCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    @Test
    fun deviceBridgeDefaultsAreAllOff_andApiKeyEncrypts() {
        val filesDir = tmp.newFolder("files")
        val store = SettingsStore(filesDir, xorCipher)
        val defaults = store.current()

        assertFalse(defaults.bridgeEnabled)
        assertFalse(defaults.allowAccessibilityRead)
        assertFalse(defaults.allowAccessibilityControl)
        assertFalse(defaults.allowAdbBridge)
        assertFalse(defaults.allowRootBridge)
        assertEquals(RootPolicyMode.ALLOWLIST_ONLY, defaults.rootPolicyMode)
        assertEquals(30, defaults.rootSessionTtlMinutes)
        assertEquals("deepseek-flash", defaults.selectedModel)
        assertTrue(defaults.requirePerCallConfirmation)
        assertEquals(AppThemeMode.LIGHT, defaults.themeMode)
        assertFalse(store.hasApiKey.value)

        store.setApiKey("sk-test-12345")
        assertTrue(store.hasApiKey.value)
        assertEquals("sk-test-12345", store.getApiKey())
        val encBytes = File(filesDir, "security/secrets.enc").readBytes()
        assertFalse(String(encBytes, Charsets.UTF_8).contains("sk-test-12345"))
        assertEquals("sk-test-12345", File(filesDir, "linux/ubuntu/root/.dsh/api_key").readText())
    }

    @Test
    fun themeModePersistsAcrossStoreReloads() {
        val filesDir = tmp.newFolder("files-theme")
        val store = SettingsStore(filesDir, xorCipher)
        assertEquals(AppThemeMode.LIGHT, store.current().themeMode)

        store.update { it.copy(themeMode = AppThemeMode.DARK) }
        val reloadedDark = SettingsStore(filesDir, xorCipher)
        assertEquals(AppThemeMode.DARK, reloadedDark.current().themeMode)

        reloadedDark.update { it.copy(themeMode = AppThemeMode.SYSTEM) }
        val reloadedSystem = SettingsStore(filesDir, xorCipher)
        assertEquals(AppThemeMode.SYSTEM, reloadedSystem.current().themeMode)
    }

    @Test
    fun setupWizardProgressPersistsAcrossReloads() {
        val filesDir = tmp.newFolder("files-wizard")
        val store = SettingsStore(filesDir, xorCipher)
        assertFalse(store.current().setupWizardStarted)
        assertEquals(0, store.current().setupWizardStep)

        store.update { it.copy(setupWizardStarted = true, setupWizardStep = 3) }
        val reloaded = SettingsStore(filesDir, xorCipher)
        assertTrue(reloaded.current().setupWizardStarted)
        assertEquals(3, reloaded.current().setupWizardStep)
        assertFalse(reloaded.current().onboardingCompleted)
    }

    @Test
    fun setApiKeyFailureDoesNotWritePlaintextContainerCopy() {
        val filesDir = tmp.newFolder("files-fail-cipher")
        val failingCipher = object : SecretCipher {
            override fun encrypt(plaintext: ByteArray): ByteArray = throw IllegalStateException("Keystore invalidated")
            override fun decrypt(ciphertext: ByteArray): ByteArray = throw IllegalStateException("Keystore invalidated")
        }
        val store = SettingsStore(filesDir, failingCipher)
        val res = runCatching { store.setApiKey("sk-secret-value") }
        assertTrue(res.isFailure)
        assertFalse(store.hasApiKey.value)
        assertFalse(File(filesDir, "linux/ubuntu/root/.dsh/api_key").exists())
    }
}
