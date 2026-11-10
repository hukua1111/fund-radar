package com.hukua.fundradar.data

import org.json.JSONArray

/**
 * 历史日 K 的备用源：新浪财经 `CN_MarketDataService.getKLineData`。
 *
 * 只当东财 `push2his` 整体不可用时才走这里，原因有两个：
 * 1. 新浪只认交易所挂牌的指数（`sh000300` / `sz399006`），基金跟踪的
 *    `930xxx` / `931xxx` 中证自编指数一律返回 `(null)`；
 * 2. 返回里没有成交额，成交量口径也和东财不完全一致（同一天 399006 的
 *    成交量差 2.6%），所以它能画形态，但不能替代东财做数据源。
 *
 * 好处是同一交易日的 OHLC 与东财逐位吻合（只差小数精度），够兜底。
 */
internal object SinaKlineApi {

    const val LABEL = "新浪财经"

    private const val REFERER = "https://finance.sina.com.cn/"

    /**
     * 日线，最多 [limit] 根。中证自编代码返回 null —— 调用方按「备用也拿不到」处理。
     */
    suspend fun fetchIndexKline(secid: String, limit: Int): KlineSeries? {
        val symbol = symbolOf(secid) ?: return null
        val url = "https://quotes.sina.cn/cn/api/jsonp_v2.php/var%20_k=/" +
            "CN_MarketDataService.getKLineData?symbol=$symbol&scale=240&ma=no&datalen=$limit"

        val body = runCatching { Net.get(url, referer = REFERER, readTimeoutMs = 6_000) }
            .getOrNull() ?: return null
        return parse(body, secid)
    }

    /** `1.000300` → `sh000300`，`0.399006` → `sz399006`；中证（market=2）没有。 */
    private fun symbolOf(secid: String): String? {
        val market = secid.substringBefore('.').toIntOrNull() ?: return null
        val code = secid.substringAfter('.', "")
        if (code.isBlank()) return null
        return when (market) {
            1 -> "sh$code"
            0 -> "sz$code"
            else -> null
        }
    }

    /** Visible for tests: `var _k=([{"day":"2026-11-05","open":"2493.729",…}]);` */
    internal fun parse(body: String, secid: String): KlineSeries? {
        val start = body.indexOf('[')
        val end = body.lastIndexOf(']')
        if (start < 0 || end <= start) return null

        val rows = runCatching { JSONArray(body.substring(start, end + 1)) }.getOrNull()
            ?: return null
        if (rows.length() == 0) return null

        val candles = ArrayList<Candle>(rows.length())
        for (i in 0 until rows.length()) {
            val item = rows.optJSONObject(i) ?: continue
            val date = item.optString("day")
            val open = item.optString("open").toDoubleOrNull() ?: continue
            val close = item.optString("close").toDoubleOrNull() ?: continue
            if (date.isBlank() || open <= 0.0 || close <= 0.0) continue
            candles += Candle(
                date = date,
                open = open,
                close = close,
                high = item.optString("high").toDoubleOrNull() ?: maxOf(open, close),
                low = item.optString("low").toDoubleOrNull() ?: minOf(open, close),
                // 新浪给的是股数，东财是手；这里统一按「手」存，量柱只比高低，
                // 口径差一点不影响形态。
                volume = (item.optString("volume").toDoubleOrNull() ?: 0.0) / 100.0
            )
        }
        if (candles.isEmpty()) return null
        return KlineSeries(
            code = secid.substringAfter('.'),
            name = "",
            source = KlineSource.EXCHANGE,
            candles = candles,
            meta = KlineMeta(
                historySource = LABEL,
                asOf = candles.last().date
            )
        )
    }
}
