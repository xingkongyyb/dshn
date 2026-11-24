package dev.dshn.app.data

import dev.dshn.app.security.SecretCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StatusBannerBusTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    private val passthroughCipher = object : SecretCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext
        override fun decrypt(ciphertext: ByteArray): ByteArray = ciphertext
    }

    @Test
    fun emitsLocalizedBannersAndDismissesByIdOnlyWhenMatching() {
        val store = SettingsStore(tmp.newFolder("bus-files"), passthroughCipher)
        store.update { it.copy(localeMode = AppLocaleMode.ZH) }
        val bus = StatusBannerBus(store)

        bus.showLocalized("中文提示", "English notice", BannerAction.OPEN_LOGS)
        val first = bus.banner.value!!
        assertEquals("中文提示", first.text)
        assertEquals(BannerAction.OPEN_LOGS, first.action)

        // Switching to EN updates localization for subsequent banners
        store.update { it.copy(localeMode = AppLocaleMode.EN) }
        bus.showLocalized("中文提示2", "English notice 2", BannerAction.RESTART_RUNTIME)
        val second = bus.banner.value!!
        assertEquals("English notice 2", second.text)
        assertEquals(BannerAction.RESTART_RUNTIME, second.action)

        // Dismissing stale id does not clear the newer banner
        bus.dismiss(first.id)
        assertEquals(second.id, bus.banner.value?.id)

        // Dismissing matching id clears banner
        bus.dismiss(second.id)
        assertNull(bus.banner.value)
    }
}
