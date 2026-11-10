package com.hukua.fundradar.data

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 中证指数公司官网的历史日 K（`csindex-home/perf/index-perf`）。
 *
 * ## 为什么把它提成历史段主源
 * 这是编制方自己发布的接口，一次能给到指数上市以来全部数据（沪深300 可回溯
 * 到 2004 年底，6027 根）。东财 `push2his` 对 930/931 段只保留约 1426 根
 * （5.9 年），再往前就没了，而且不返回 PE。
 *
 * 更关键的两点：
 * 1. **同日 OHLC 与东财逐位一致**（000300/000001/931151 实测最大差 0.0000），
 *    所以历史段取官网、当日那根取东财，拼接处不会出现台阶；
 * 2. `tradingVol` 除以 100 正好等于东财的「手」，成交量口径也一致。
 *
 * ## 边界
 * 只覆盖 000/930/931 段（沪市与中证自编）。**深市 399 段一律返回空**
 * （399001/399006/399303 实测 0 行），这类标的仍走东财。
 *
 * ## 时延
 * 官网是 T-1：当天收盘后到次日更新前，最新一行仍是上一个交易日。所以调用方
 * 必须再用东财补当日那根，不能只靠这里。实测连发 30 次 0 失败、p50 156ms。
 */
internal object CsIndexApi {

    const val LABEL = "中证指数官网"

    private const val REFERER = "https://www.csindex.com.cn/"
    private const val ENDPOINT = "https://www.csindex.com.cn/csindex-home/perf/index-perf"

    /** 官网只认沪市（1）与中证（2）；深市 399 段没有收录。 */
    fun supports(market: Int): Boolean = market == 1 || market == 2

    /**
     * 日线，最多 [bars] 根，按日期升序。拿不到返回 null，由调用方回落东财。
     */
    suspend fun fetchDaily(code: String, bars: Int): KlineSeries? {
        if (code.isBlank() || bars <= 0) return null
        val url = "$ENDPOINT?indexCode=$code&startDate=${startDateOf(bars)}&endDate=${endDate()}"
        val body = runCatching {
            Net.get(url, referer = REFERER, readTimeoutMs = 12_000, connectTimeoutMs = 6_000)
        }.getOrNull() ?: return null
        return parse(body, code, bars)
    }

    /** 往回多要四成日历日，避开长假把窗口撑得不够。 */
    private fun startDateOf(bars: Int): String {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -(bars * 3 / 2 + 30))
        return SimpleDateFormat("yyyyMMdd", Locale.US).format(calendar.time)
    }

    private fun endDate(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    /** `20261109` → `2026-11-09`，与东财、缓存里的格式对齐。 */
    internal fun isoDate(raw: String): String? {
        if (raw.length != 8) return null
        return "${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6, 8)}"
    }

    /** 官网把缺值写成 null，`optDouble` 会给出 NaN，这里统一成 null。 */
    private fun num(json: JSONObject?, key: String): Double? {
        if (json == null || json.isNull(key)) return null
        val value = json.optDouble(key, Double.NaN)
        return value.takeUnless { it.isNaN() }
    }

    /** Visible for tests: `{"data":[{"tradeDate":"20261109","open":…}]}` */
    internal fun parse(body: String, code: String, bars: Int): KlineSeries? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val rows = root.optJSONArray("data") ?: return null
        if (rows.length() == 0) return null

        val candles = ArrayList<Candle>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val date = isoDate(row.optString("tradeDate")) ?: continue
            val close = num(row, "close") ?: continue
            if (close <= 0.0) continue
            val open = num(row, "open") ?: close
            candles += Candle(
                date = date,
                open = open,
                close = close,
                high = num(row, "high") ?: maxOf(open, close),
                low = num(row, "low") ?: minOf(open, close),
                // 官网给的是股数，东财给的是手；除以 100 后两者逐位一致。
                volume = (num(row, "tradingVol") ?: 0.0) / 100.0
            )
        }
        if (candles.isEmpty()) return null

        val last = rows.optJSONObject(rows.length() - 1)
        val meta = KlineMeta(
            pe = num(last, "peg"),
            constituents = num(last, "consNumber")?.toInt(),
            historySource = LABEL,
            asOf = candles.last().date
        )

        return KlineSeries(
            code = code,
            name = last?.optString("indexNameCn").orEmpty(),
            source = KlineSource.EXCHANGE,
            candles = if (candles.size > bars) candles.takeLast(bars) else candles,
            meta = meta
        )
    }
}
