package dev.tubik.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class Codec(val title: String, val mime: String) {
    VP9("VP9 (до 4K, рекомендую)", "video/x-vnd.on2.vp9"),
    AVC("H.264 (до 1080p, грузит слабые ТВ меньше всего)", "video/avc"),
    AV1("AV1 (только если ТВ умеет аппаратно)", "video/av01"),
}

object SbCategories {
    val all = linkedMapOf(
        "sponsor" to "Спонсорская реклама",
        "selfpromo" to "Самопиар",
        "interaction" to "Просьбы подписаться",
        "intro" to "Заставка",
        "outro" to "Концовка / титры",
        "preview" to "Превью / пересказ",
        "music_offtopic" to "Не-музыка в клипах",
        "filler" to "Отвлечённая болтовня",
    )
    val defaults = setOf("sponsor", "selfpromo", "interaction")
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("tubik", Context.MODE_PRIVATE)

    private val _cookies = MutableStateFlow(sp.getString(KEY_COOKIES, null))
    val cookiesFlow: StateFlow<String?> = _cookies
    var cookies: String?
        get() = _cookies.value
        set(v) {
            sp.edit().putString(KEY_COOKIES, v).apply()
            _cookies.value = v
        }

    /** 0 = авто, иначе максимальная высота кадра. */
    var maxQuality: Int
        get() = sp.getInt("max_quality", 1080)
        set(v) = sp.edit().putInt("max_quality", v).apply()

    var codec: Codec
        get() = runCatching { Codec.valueOf(sp.getString("codec", Codec.VP9.name)!!) }.getOrDefault(Codec.VP9)
        set(v) = sp.edit().putString("codec", v.name).apply()

    var sponsorBlockEnabled: Boolean
        get() = sp.getBoolean("sb_enabled", true)
        set(v) = sp.edit().putBoolean("sb_enabled", v).apply()

    var sponsorBlockCategories: Set<String>
        get() = sp.getStringSet("sb_categories", SbCategories.defaults) ?: SbCategories.defaults
        set(v) = sp.edit().putStringSet("sb_categories", v).apply()

    var autoplay: Boolean
        get() = sp.getBoolean("autoplay", true)
        set(v) = sp.edit().putBoolean("autoplay", v).apply()

    var updateCheck: Boolean
        get() = sp.getBoolean("update_check", true)
        set(v) = sp.edit().putBoolean("update_check", v).apply()

    var skippedVersion: Long
        get() = sp.getLong("skipped_version", 0)
        set(v) = sp.edit().putLong("skipped_version", v).apply()

    companion object {
        private const val KEY_COOKIES = "yt_cookies"
    }
}
