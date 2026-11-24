package dev.dshn.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Optional follow-up action attached to a global snackbar message. */
enum class BannerAction {
    RESTART_RUNTIME,
    OPEN_LOGS,
    OPEN_MODEL_SETTINGS,
    OPEN_ENVIRONMENT
}

data class StatusBannerUi(
    val text: String,
    val action: BannerAction? = null,
    val id: Long = System.nanoTime()
)

/**
 * Shared reactive snackbar event bus injected into coordinators and feature ViewModels
 * so any feature can emit localized notifications without coupling to a root ViewModel.
 */
class StatusBannerBus(
    private val settingsStore: SettingsStore
) {
    private val _banner = MutableStateFlow<StatusBannerUi?>(null)
    val banner: StateFlow<StatusBannerUi?> = _banner.asStateFlow()

    fun isZh(): Boolean = resolveIsZhLocale(settingsStore.current().localeMode)

    fun text(zh: String, en: String): String = if (isZh()) zh else en

    fun show(text: String, action: BannerAction? = null) {
        _banner.value = StatusBannerUi(text, action)
    }

    fun showLocalized(zh: String, en: String, action: BannerAction? = null) {
        show(text(zh, en), action)
    }

    fun dismiss(id: Long? = null) {
        val cur = _banner.value ?: return
        if (id == null || cur.id == id) {
            _banner.value = null
        }
    }
}
