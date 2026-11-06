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

    suspend fun fetch(symbols: Collection<String>): Map<String, RealtimeQuote> {
        val cleaned = symbols.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleaned.isEmpty()) return emptyMap()

        val result = LinkedHashMap<String, RealtimeQuote>()
        cleaned.chunked(BATCH).forEach { chunk ->
            val body = runCatching {
                Net.get(
                    url = "https://qt.gtimg.cn/q=" + chunk.joinToString(","),
                    referer = "https://gu.qq.com/",
                    charset = charset("GBK")
                )
            }.getOrNull() ?: return@forEach
            result.putAll(parseQuotes(body))
        }
        return result
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
        Regex("""v_([a-z]{2}\d{6})="([^"]*)"""").findAll(body).forEach { match ->
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

    /** `20261105161438` → `11-05 16:14`; anything unexpected is passed through. */
    private fun timeText(raw: String): String {
        val digits = raw.trim()
        if (digits.length < 12 || digits.any { !it.isDigit() }) return digits
        return String.format(
            Locale.US,
            "%s-%s %s:%s",
            digits.substring(4, 6),
            digits.substring(6, 8),
            digits.substring(8, 10),
            digits.substring(10, 12)
        )
    }
}
