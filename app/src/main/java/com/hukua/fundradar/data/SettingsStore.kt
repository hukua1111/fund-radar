package com.hukua.fundradar.data

import android.content.Context

/**
 * Trims a user typed base URL and adds the missing scheme.
 *
 * A scheme-less value used to be handed to `URL()` as-is and blow up with an
 * unhelpful message. LAN boxes (`192.168.x.x`, `127.0.0.1`, `localhost`) and
 * bare IPs are nearly always plain http; host names are assumed https.
 */
internal fun normalizeBaseUrl(raw: String): String {
    var url = raw.trim()
    if (url.isEmpty()) return ""

    if (!url.startsWith("http://", ignoreCase = true) &&
        !url.startsWith("https://", ignoreCase = true)
    ) {
        url = (if (isLocalHost(url)) "http://" else "https://") + url
    }
    return url.trimEnd('/')
}

/** True for `localhost`, `127.0.0.1` and bare IPv4 / IPv6 literals. */
private fun isLocalHost(url: String): Boolean {
    val host = url.substringBefore('/')
        .substringBefore('?')
        .substringAfterLast('@')
        .substringBefore(':')
        .trim()
    if (host.isEmpty()) return false
    if (host.equals("localhost", ignoreCase = true)) return true
    return host.none { it.isLetter() } // 160.202.238.171 / [::1] / 192.168.1.9
}

/** AI + push + schedule configuration, stored locally. */
data class AiConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val barkUrl: String = "",
    val dailyEnabled: Boolean = false,
    val hour: Int = 14,
    val minute: Int = 35
) {
    val configured: Boolean
        get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()

    val barkConfigured: Boolean
        get() = barkUrl.isNotBlank()

    /** Accepts both `https://host/v1` and a full `.../chat/completions` URL. */
    fun endpoint(): String {
        val url = normalizeBaseUrl(baseUrl)
        if (url.isEmpty()) return ""
        return if (url.endsWith("/chat/completions")) url else "$url/chat/completions"
    }

    fun timeText(): String = "%02d:%02d".format(hour, minute)

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-chat"
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("fund_radar_settings", Context.MODE_PRIVATE)

    fun load(): AiConfig = AiConfig(
        baseUrl = prefs.getString(KEY_BASE_URL, AiConfig.DEFAULT_BASE_URL).orEmpty(),
        apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
        model = prefs.getString(KEY_MODEL, AiConfig.DEFAULT_MODEL).orEmpty(),
        barkUrl = prefs.getString(KEY_BARK, "").orEmpty(),
        dailyEnabled = prefs.getBoolean(KEY_DAILY, false),
        hour = prefs.getInt(KEY_HOUR, 14),
        minute = prefs.getInt(KEY_MINUTE, 35)
    )

    fun save(config: AiConfig) {
        prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_API_KEY, config.apiKey)
            .putString(KEY_MODEL, config.model)
            .putString(KEY_BARK, config.barkUrl)
            .putBoolean(KEY_DAILY, config.dailyEnabled)
            .putInt(KEY_HOUR, config.hour)
            .putInt(KEY_MINUTE, config.minute)
            .apply()
    }

    private companion object {
        const val KEY_BASE_URL = "ai_base_url"
        const val KEY_API_KEY = "ai_api_key"
        const val KEY_MODEL = "ai_model"
        const val KEY_BARK = "bark_url"
        const val KEY_DAILY = "daily_enabled"
        const val KEY_HOUR = "daily_hour"
        const val KEY_MINUTE = "daily_minute"
    }
}
