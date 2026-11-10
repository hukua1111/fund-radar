package com.hukua.fundradar.data

import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 指数实时行情的来源，界面上用来告诉用户当前吃的是哪家的数据。 */
enum class IndexSource(val label: String) {
    EASTMONEY("东方财富"),
    TENCENT("腾讯")
}

/** 一次取行情的整体结果：拿到什么、走的哪家、有没有整体性故障。 */
data class IndexQuoteBatch(
    val quotes: Map<String, RealtimeQuote> = emptyMap(),
    val source: IndexSource = IndexSource.EASTMONEY,
    val error: String? = null,
    /** 主源正在冷却，这一轮直接走了备用源。 */
    val degraded: Boolean = false
)

/**
 * 指数的实时行情：东方财富为主，腾讯兜底。
 *
 * ## 为什么主用东财
 * 基金跟踪的指数里有大量中证指数公司自编的代码（`930xxx` / `931xxx`，如
 * 931994 电网设备主题）。实测下来，腾讯 `qt.gtimg.cn` 与新浪 `hq.sinajs.cn`
 * 只认交易所挂牌的 `000xxx`（沪）和 `399xxx`（深），这些代码直接返回空；
 * 中证官网接口要签名、雪球要登录态、同花顺要额外的 waf 校验。**能免费、
 * 无 key、批量拿到 930/931 的只有东财**，所以主源只能是它。
 *
 * 东财的问题是 `push2.eastmoney.com` 这一族主机在部分网络下会被掐连接
 * （实测连发 40 次有约 15% 返回空，不同分片主机表现不一样），所以：
 * - 每次只给主源 3.5 秒预算，最多试两台主机（见 [EmQuoteHosts]），
 *   超了就立刻走备用，不能把一轮 5 秒的刷新拖死；
 * - 主源连续失败后冷却 60 秒，冷却期内全部指数都能用腾讯表达时直接走腾讯；
 * - 备用源拿不到的 930/931 保持上一轮的值，界面照常显示，不闪成「—」。
 *
 * ## 速度实测（本机 40 次连发）
 * 腾讯 33–45ms、0 失败；东财 45–160ms、单主机偶发空回复。腾讯快一倍多，
 * 但它认不出中证自编指数，只能当备用。
 */
object IndexQuoteApi {

    /** 一批问多少个，URL 控制在 2 KB 以内。 */
    private const val BATCH = 40

    /** 主源的整轮预算，超过就走备用，别让界面等。 */
    private const val PRIMARY_BUDGET_MS = 3_500L

    /** 单台主机的读超时，两台加起来仍在上面的预算内。 */
    private const val PRIMARY_READ_TIMEOUT_MS = 1_500

    /** 主源整体失败后的冷却时间。 */
    private const val PRIMARY_COOLDOWN_MS = 60_000L

    private const val FIELDS = "f2,f3,f12,f13,f14,f124"
    private const val UT = "fa5fd1943c7b386f172d6893dbfba10b"
    private const val REFERER = "https://quote.eastmoney.com/"

    /** 主源冷却到什么时候（毫秒时间戳）。 */
    @Volatile
    private var primaryDownUntil = 0L

    /** 按 secid（`1.000300`、`2.931994`）取行情，主备自动切换。 */
    suspend fun fetchBatch(entries: Collection<IndexEntry>): IndexQuoteBatch {
        val cleaned = entries.distinctBy { it.secid }.filter { it.secid.isNotBlank() }
        if (cleaned.isEmpty()) return IndexQuoteBatch()

        val now = System.currentTimeMillis()
        // 冷却期内，如果这批指数腾讯全都能给，就不必再去撞主源。
        if (now < primaryDownUntil && cleaned.all { tencentSymbol(it) != null }) {
            val fallback = fromTencent(cleaned)
            if (fallback.isNotEmpty()) {
                return IndexQuoteBatch(fallback, IndexSource.TENCENT, degraded = true)
            }
        }

        val primary = fromEastMoney(cleaned.map { it.secid })
        if (primary.isNotEmpty()) {
            primaryDownUntil = 0L
            val missing = cleaned.filter { it.secid !in primary }
            val filled = if (missing.isEmpty()) primary else primary + fromTencent(missing)
            return IndexQuoteBatch(filled, IndexSource.EASTMONEY)
        }

        primaryDownUntil = now + PRIMARY_COOLDOWN_MS
        val fallback = fromTencent(cleaned)
        return IndexQuoteBatch(
            quotes = fallback,
            source = IndexSource.TENCENT,
            degraded = true,
            error = if (fallback.isEmpty()) "行情源暂时没有响应，稍后自动重试" else null
        )
    }

    /** 只要行情表的老接口，调用方不关心走的是哪家。 */
    suspend fun fetch(entries: Collection<IndexEntry>): Map<String, RealtimeQuote> =
        fetchBatch(entries).quotes

    // ------------------------------------------------------------ 主：东财

    private suspend fun fromEastMoney(secids: List<String>): Map<String, RealtimeQuote> {
        val result = LinkedHashMap<String, RealtimeQuote>()
        withTimeoutOrNull(PRIMARY_BUDGET_MS) {
            secids.chunked(BATCH).forEach { chunk ->
                val body = request(chunk) ?: return@forEach
                result.putAll(parse(body))
            }
        }
        return result
    }

    private suspend fun request(secids: List<String>): String? {
        val path = "/api/qt/ulist.np/get?secids=" + secids.joinToString(",") +
            "&fields=$FIELDS&fltt=2&ut=$UT"
        return EmQuoteHosts.get(
            path,
            referer = REFERER,
            readTimeoutMs = PRIMARY_READ_TIMEOUT_MS,
            maxHosts = 2,
            allowHttp = false
        )
    }

    // ------------------------------------------------------------ 备：腾讯

    /**
     * 腾讯只认 `sh000300` / `sz399006` 这类交易所代码；中证自编代码返回空，
     * 所以这批里能给的先给，给不了的不出现在结果里。
     */
    internal suspend fun fromTencent(entries: Collection<IndexEntry>): Map<String, RealtimeQuote> {
        val bySymbol = entries.mapNotNull { entry ->
            tencentSymbol(entry)?.let { it to entry.secid }
        }.toMap()
        if (bySymbol.isEmpty()) return emptyMap()

        val raw = QuoteApi.fromTencent(bySymbol.keys)
        if (raw.isEmpty()) return emptyMap()
        return raw.mapNotNull { (symbol, quote) ->
            bySymbol[symbol]?.let { secid -> secid to quote.copy(symbol = secid) }
        }.toMap()
    }

    /** `1.000300` → `sh000300`，`0.399006` → `sz399006`；中证（market=2）没有。 */
    internal fun tencentSymbol(entry: IndexEntry): String? = when (entry.market) {
        1 -> "sh${entry.code}"
        0 -> "sz${entry.code}"
        else -> null
    }

    // ------------------------------------------------------------- parsing

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
