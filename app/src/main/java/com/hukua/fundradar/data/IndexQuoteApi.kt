package com.hukua.fundradar.data

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 指数的实时行情。
 *
 * 腾讯的 `qt.gtimg.cn` 只认 `000xxx`（沪市）和 `399xxx`（深市）两类指数代码，
 * 中证自己发的 `930xxx` / `931xxx`（例如 931994 电网设备主题）在腾讯那边
 * 直接返回 `v_pv_none_match`，新浪也一样。这类指数只在东财的行情接口里有，
 * 所以指数行情单独走东财的批量接口（见 [EmQuoteHosts]），一次请求问一批。
 */
object IndexQuoteApi {

    /** 一批问多少个，URL 控制在 2 KB 以内。 */
    private const val BATCH = 40

    private const val FIELDS = "f2,f3,f12,f13,f14,f124"
    private const val UT = "fa5fd1943c7b386f172d6893dbfba10b"
    private const val REFERER = "https://quote.eastmoney.com/"

    /** 按 secid（`1.000300`、`2.931994`）取行情。 */
    suspend fun fetch(entries: Collection<IndexEntry>): Map<String, RealtimeQuote> {
        val secids = entries.map { it.secid }.filter { it.isNotBlank() }.distinct()
        if (secids.isEmpty()) return emptyMap()

        val result = LinkedHashMap<String, RealtimeQuote>()
        secids.chunked(BATCH).forEach { chunk ->
            val body = request(chunk) ?: return@forEach
            result.putAll(parse(body))
        }
        return result
    }

    private suspend fun request(secids: List<String>): String? {
        val path = "/api/qt/ulist.np/get?secids=" + secids.joinToString(",") +
            "&fields=$FIELDS&fltt=2&ut=$UT"
        return EmQuoteHosts.get(path, referer = REFERER, readTimeoutMs = 8_000)
    }

    /**
     * Visible for tests. A reply looks like:
     *
     * ```json
     * {"rc":0,"data":{"total":2,"diff":[
     *   {"f2":2417.74,"f3":-1.01,"f12":"931994","f13":2,"f14":"电网设备主题","f124":1793938240}
     * ]}}
     * ```
     *
     * `f2` 最新价、`f3` 涨跌幅（相对昨收）、`f13` 市场（0 深 / 1 沪 / 2 中证）、
     * `f124` 行情时间（秒）。
     */
    internal fun parse(body: String): Map<String, RealtimeQuote> {
        val cleaned = body.trimStart('\uFEFF', '\r', '\n', ' ')
        val root = runCatching { JSONObject(cleaned) }.getOrNull() ?: return emptyMap()
        val rows = root.optJSONObject("data")?.optJSONArray("diff") ?: return emptyMap()

        val result = LinkedHashMap<String, RealtimeQuote>()
        for (index in 0 until rows.length()) {
            val item = rows.optJSONObject(index) ?: continue
            val code = item.optString("f12").trim()
            if (code.isEmpty() || code == "-") continue
            val market = item.optInt("f13", -1)
            if (market < 0) continue
            val price = item.optDouble("f2", Double.NaN)
            if (price.isNaN() || price <= 0.0) continue
            val percent = item.optDouble("f3", 0.0).takeUnless { it.isNaN() } ?: 0.0
            val name = item.optString("f14").takeUnless { it == "-" }.orEmpty()
            val stamp = item.optLong("f124", 0L)

            result["$market.$code"] = RealtimeQuote(
                symbol = "$market.$code",
                name = name,
                price = price,
                prevClose = if (percent > -100.0) price / (1 + percent / 100.0) else 0.0,
                changePercent = percent,
                time = timeText(stamp)
            )
        }
        return result
    }

    /** 秒级时间戳 → `11-06 12:05`；拿不到时间就留空。 */
    private fun timeText(epochSeconds: Long): String {
        if (epochSeconds <= 0L) return ""
        return SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(epochSeconds * 1000))
    }
}
