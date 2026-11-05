package com.hukua.fundradar.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Index lookup used by the "添加指数" sheet.
 *
 * East Money's own suggest endpoint rejects non-browser clients, and the index
 * catalogue it serves is a *list*, not a search API. Tencent's smartbox is an
 * open, unauthenticated suggest endpoint that answers both names and codes and
 * marks index rows with the `ZS` type, so it is used as the primary search.
 *
 * Response shape (one row per hit, `^` separated):
 *
 * ```
 * v_hint="sz~399997~中证白酒~zzbj~ZS^sh~000300~沪深300~hs300~ZS"
 * ```
 *
 * `v_hint="N"` means no hit. Names arrive as `\uXXXX` escapes.
 */
object IndexSearchApi {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private const val SUGGEST_URL = "https://smartbox.gtimg.cn/s3/"

    /** Index rows only, de-duplicated by name. */
    suspend fun search(query: String): List<IndexEntry> = withContext(Dispatchers.IO) {
        val keyword = query.trim()
        if (keyword.isEmpty()) return@withContext emptyList()
        val body = httpGet(
            SUGGEST_URL + "?q=" + URLEncoder.encode(keyword, "UTF-8") + "&t=all"
        )
        parse(body)
    }

    /** Visible for tests: turns the smartbox body into quotable indices. */
    internal fun parse(raw: String): List<IndexEntry> {
        val hint = raw.substringAfter("v_hint=\"", "").substringBeforeLast("\"", "")
        if (hint.isBlank() || hint == "N") return emptyList()

        val seenNames = HashSet<String>()
        val result = ArrayList<IndexEntry>()
        hint.split('^').forEach { row ->
            if (row.isBlank()) return@forEach
            val parts = row.split('~')
            if (parts.size < 5) return@forEach
            // market ~ code ~ name ~ pinyin ~ type
            val type = parts[4].trim().uppercase()
            if (type != "ZS" && type != "ZS-A") return@forEach

            val code = parts[1].trim()
            val name = decodeEscapes(parts[2]).trim()
            if (code.isBlank() || name.isBlank()) return@forEach

            val market = when (parts[0].trim().lowercase()) {
                "sh" -> 1
                "sz" -> 0
                "bj" -> 0
                else -> return@forEach // hk / us rows are not quotable indices here
            }
            if (!seenNames.add(name)) return@forEach
            result += IndexEntry(code, market, name)
        }
        return result
    }

    private fun decodeEscapes(text: String): String {
        if (!text.contains("\\u")) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == '\\' && i + 5 < text.length && (text[i + 1] == 'u' || text[i + 1] == 'U')) {
                val code = text.substring(i + 2, i + 6).toIntOrNull(16)
                if (code != null) {
                    out.append(code.toChar())
                    i += 6
                    continue
                }
            }
            out.append(text[i])
            i++
        }
        return out.toString()
    }

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", "https://gu.qq.com/")
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Connection", "close")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("搜索服务返回 HTTP $status")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
