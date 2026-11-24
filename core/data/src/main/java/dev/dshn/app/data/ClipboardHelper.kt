package dev.dshn.app.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

interface ClipboardHelper {
    fun copy(label: String, text: String, announce: Boolean = true)
}

class AndroidClipboardHelper(
    private val context: Context,
    private val bannerBus: StatusBannerBus
) : ClipboardHelper {
    override fun copy(label: String, text: String, announce: Boolean) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        if (announce) {
            bannerBus.showLocalized("已复制", "Copied")
        }
    }
}
