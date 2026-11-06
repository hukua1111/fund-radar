package com.hukua.fundradar.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream

/**
 * The one place that speaks HTTP. Every upstream the app talks to is a public
 * page or JSON endpoint without an API key, so a plain [HttpURLConnection] and
 * a browser user agent are enough — no extra dependency, no TLS fingerprint
 * games.
 */
internal object Net {

    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /**
     * 天天基金的**移动端**接口只认移动端 UA：拿桌面 Chrome 的 UA 去请求
     * `fundmobapi` 会稳定返回 `ErrCode 61136403 网络繁忙`（不是真的繁忙，是
     * 端校验）。这个 UA 就是给那类接口用的。
     */
    const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    /**
     * GETs [url] and decodes it with [charset]. Tencent quotes answer in GBK,
     * everything else in UTF-8, hence the parameter.
     */
    suspend fun get(
        url: String,
        referer: String = "",
        charset: Charset = Charsets.UTF_8,
        readTimeoutMs: Int = 12_000,
        userAgent: String = UA
    ): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = readTimeoutMs
            requestMethod = "GET"
            setRequestProperty("User-Agent", userAgent)
            if (referer.isNotBlank()) setRequestProperty("Referer", referer)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("Connection", "close")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("服务器返回 HTTP $status")
            }
            val stream = connection.inputStream
            val decoded = if (connection.contentEncoding?.contains("gzip", true) == true) {
                GZIPInputStream(stream)
            } else {
                stream
            }
            decoded.bufferedReader(charset).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
