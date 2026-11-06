package com.hukua.fundradar.data

import java.util.Locale

/** One exchange quote, as delivered by Tencent's public `qt.gtimg.cn` feed. */
data class RealtimeQuote(
    val symbol: String,
    val name: String,
    val price: Double,
    val prevClose: Double,
    val changePercent: Double,
    /** `11-05 16:14` — when the exchange last printed this. */
    val time: String
)

/**
 * Tencent's quote endpoint (`qt.gtimg.cn/q=sh515220,sz159819`) answers a whole
 * batch in one GBK request, needs no key and is not rate limited like the
 * eastmoney quote hosts. That makes it the right source for the intraday
 * estimate, where a dozen codes are asked for at once.
 */
object QuoteApi {

    /** Quotes per request; keeps the URL short and the reply easy to parse. */
    private const val BATCH = 40

    private const val REFERER = "https://gu.qq.com/"
    private val GBK = charset("GBK")

    suspend fun fetch(symbols: Collection<String>): Map<String, RealtimeQuote> {
        val cleaned = symbols.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleaned.isEmpty()) return emptyMap()

        val result = LinkedHashMap<String, RealtimeQuote>()
        cleaned.chunked(BATCH).forEach { chunk ->
            val body = fetchChunk(chunk) ?: return@forEach
            result.putAll(parseQuotes(body))
        }
        return result
    }

    /** 先走 HTTPS；个别网络环境会拦掉，退回 HTTP 再来一次。 */
    private suspend fun fetchChunk(chunk: List<String>): String? {
        val path = "q=" + chunk.joinToString(",")
        val https = runCatching {
            Net.get("https://qt.gtimg.cn/$path", referer = REFERER, charset = GBK)
        }.getOrNull()
        if (!https.isNullOrBlank()) return https
        return runCatching {
            Net.get("http://qt.gtimg.cn/$path", referer = REFERER, charset = GBK)
        }.getOrNull()
    }

    /**
     * Visible for tests. A reply looks like:
     *
     * ```
     * v_sh515220="1~煤炭ETF国泰~515220~1.172~1.210~1.195~…~20261105161438~-0.038~-3.14~…";
     * ```
     *
     * Fields 1 name, 3 price, 4 previous close, 30 timestamp, 32 change %.
     */
    internal fun parseQuotes(body: String): Map<String, RealtimeQuote> {
        val result = LinkedHashMap<String, RealtimeQuote>()
        Regex("""v_([a-z]{2}[A-Za-z0-9.]+)="([^"]*)"""").findAll(body).forEach { match ->
            val symbol = match.groupValues[1]
            val parts = match.groupValues[2].split('~')
            if (parts.size < 33) return@forEach

            val price = parts[3].toDoubleOrNull() ?: return@forEach
            val prevClose = parts[4].toDoubleOrNull() ?: return@forEach
            if (price <= 0.0) return@forEach

            val percent = parts[32].toDoubleOrNull()
                ?: if (prevClose > 0) (price - prevClose) / prevClose * 100 else 0.0

            result[symbol] = RealtimeQuote(
                symbol = symbol,
                name = parts[1].trim(),
                price = price,
                prevClose = prevClose,
                changePercent = percent,
                time = timeText(parts[30])
            )
        }
        return result
    }

    /** `20261105161438` → `11-05 16:14`; 港股是 `2026/11/06 11:04:37`。 */
    private fun timeText(raw: String): String {
        val text = raw.trim()
        if (text.length >= 12 && text.all { it.isDigit() }) {
            return String.format(
                Locale.US,
                "%s-%s %s:%s",
                text.substring(4, 6),
                text.substring(6, 8),
                text.substring(8, 10),
                text.substring(10, 12)
            )
        }
        val match = Regex("""\d{4}[/-](\d{2})[/-](\d{2})[ T](\d{2}):(\d{2})""").find(text)
            ?: return text
        return String.format(
            Locale.US,
            "%s-%s %s:%s",
            match.groupValues[1],
            match.groupValues[2],
            match.groupValues[3],
            match.groupValues[4]
        )
    }
}
