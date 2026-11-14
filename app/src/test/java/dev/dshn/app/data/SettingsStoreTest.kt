package dev.dshn.app.data

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
}
