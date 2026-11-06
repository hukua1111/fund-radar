package com.hukua.fundradar.data

import java.util.Locale

/** Where an intraday estimate comes from. */
enum class EstimateSource {
    /** The ETF a feeder fund holds — the most direct read of today's move. */
    ETF,

    /** Weighted move of the fund's top holdings, for funds without an ETF. */
    HOLDINGS,

    /** 有跟踪标的但拿不到持仓时，用标的指数的实时涨跌代替。 */
    INDEX,

    /** 什么都取不到时退回最近一次公布的净值涨跌（非实时）。 */
    NAV
}

/**
 * Intraday estimate for a fund.
 *
 * A feeder fund (ETF 联接) publishes only one NAV per day, but the ETF it holds
 * trades all session, so the ETF's change is a good stand-in for the fund's.
 * For direct index funds there is no ETF, so the top holdings are weighted by
 * their published share of the portfolio instead.
 *
 * Everything here is an estimate, never a quote of the fund itself.
 */
data class Estimate(
    /** Estimated change in percent, e.g. -3.14. */
    val percent: Double,
    /** NAV scaled by [percent]; null when the last NAV is unknown. */
    val nav: Double?,
    val source: EstimateSource,
    /** What was used: "煤炭ETF国泰 515220" / "10 只重仓股". */
    val basis: String,
    /** Quote timestamp from the exchange, already formatted as `11-05 16:14`. */
    val quoteTime: String = "",
    /** Share of the portfolio covered by the weighted holdings, in percent. */
    val coverage: Double = 0.0
) {
    val sourceLabel: String
        get() = when (source) {
            EstimateSource.ETF -> "按目标 ETF 估算"
            EstimateSource.HOLDINGS -> "按重仓股估算"
            EstimateSource.INDEX -> "按标的指数估算"
            EstimateSource.NAV -> "最近公布净值"
        }

    /** 卡片上的小标题：真·盘中估算才叫「估算涨跌」。 */
    val valueLabel: String
        get() = if (source == EstimateSource.NAV) "净值涨跌" else "估算涨跌"

    val isRealtime: Boolean get() = source != EstimateSource.NAV

    /** One-line footnote under the card. */
    val footnote: String
        get() = buildString {
            append(sourceLabel)
            if (basis.isNotBlank()) {
                append(" · ")
                append(basis)
            }
            if (source == EstimateSource.HOLDINGS && coverage > 0) {
                append("（合计 ")
                append(String.format(Locale.US, "%.1f", coverage))
                append("%）")
            }
            if (quoteTime.isNotBlank()) {
                append(" · ")
                append(quoteTime)
            }
            if (!isRealtime) {
                append("（非实时）")
            }
        }
}

/**
 * Exchange symbol for a quote request: `sh515220`, `sz159819`, `hk00700`.
 *
 * [marketHint] comes from the position feed (0 = 深圳, 1 = 上海, 116 = 港股,
 * 105/106/107 = 美股). Stock codes alone cannot tell 北交所 from 深市, nor
 * 港股 from 深市 — `00700` and `000700` both look like six/five digit numbers.
 */
fun exchangeSymbol(code: String, marketHint: Int = -1): String {
    val normalized = code.trim()
    if (normalized.isEmpty()) return ""
    if (normalized.startsWith("sh") || normalized.startsWith("sz") ||
        normalized.startsWith("bj") || normalized.startsWith("hk") ||
        normalized.startsWith("us")
    ) {
        return normalized
    }
    val market = when (marketHint) {
        0 -> "sz"
        1 -> "sh"
        116 -> "hk"
        105, 106, 107 -> "us"
        else -> when {
            normalized.length == 5 && normalized.all { it.isDigit() } -> "hk"
            normalized.startsWith("5") || normalized.startsWith("6") ||
                normalized.startsWith("9") -> "sh"
            normalized.startsWith("4") || normalized.startsWith("8") -> "bj"
            else -> "sz"
        }
    }
    return market + normalized
}

/**
 * Weighted average move of a set of holdings.
 *
 * Weights are the published share of the portfolio (0–100). The result is the
 * average move of what the estimates cover, which is the best guess available
 * for the whole fund. Returns null when nothing could be priced.
 */
fun weightedChange(rows: List<Pair<Double, Double>>): Double? {
    val usable = rows.filter { it.first > 0 }
    val totalWeight = usable.sumOf { it.first }
    if (usable.isEmpty() || totalWeight <= 0) return null
    val weighted = usable.sumOf { it.first * it.second }
    return weighted / totalWeight
}

/**
 * Builds the estimate for one fund from its position and a batch of quotes.
 *
 * 依次尝试：目标 ETF 的涨跌 → 跟踪标的指数的涨跌 → 重仓股加权 → 最近公布的净值。
 *
 * 指数的优先级排在重仓股前面：场外指数基金的前十大重仓通常只占四五成，
 * 加权出来的涨跌会系统性偏小；跟踪标的指数才是它真正复制的东西。重仓股
 * 留给没有跟踪标的的主动基金，以及指数一时解析不出来的情况。
 */
fun estimateFund(
    fund: Fund,
    position: FundPosition?,
    quotes: Map<String, RealtimeQuote>,
    /** 跟踪标的与它的实时行情。 */
    index: Pair<IndexEntry, RealtimeQuote>? = null
): Estimate? {
    val etf = position?.etfCode
        ?.takeIf { it.isNotBlank() }
        ?.let { quotes[exchangeSymbol(it)] }
    if (etf != null) {
        return Estimate(
            percent = etf.changePercent,
            nav = estimatedNav(fund.nav, etf.changePercent),
            source = EstimateSource.ETF,
            basis = listOf(
                position.etfName.ifBlank { etf.name },
                position.etfCode
            ).filter { it.isNotBlank() }.joinToString(" "),
            quoteTime = etf.time
        )
    }

    if (index != null) {
        val (entry, quote) = index
        return Estimate(
            percent = quote.changePercent,
            nav = estimatedNav(fund.nav, quote.changePercent),
            source = EstimateSource.INDEX,
            basis = entry.name.ifBlank { entry.code },
            quoteTime = quote.time
        )
    }

    if (position != null) {
        val priced = position.stocks.mapNotNull { stock ->
            val quote = quotes[exchangeSymbol(stock.code, stock.market)] ?: return@mapNotNull null
            Triple(stock.weight, quote.changePercent, quote.time)
        }
        if (priced.isNotEmpty()) {
            val percent = weightedChange(priced.map { it.first to it.second })
            if (percent != null) {
                val times = priced.map { it.third }.filter { it.isNotBlank() }.distinct()
                return Estimate(
                    percent = percent,
                    nav = estimatedNav(fund.nav, percent),
                    source = EstimateSource.HOLDINGS,
                    basis = "${priced.size} 只重仓股",
                    quoteTime = if (times.size == 1) times.first() else "盘中",
                    coverage = priced.sumOf { it.first }
                )
            }
        }
    }

    // 最后的兜底：用基金自己最近一次公布的净值涨跌，至少不是空白。
    if (fund.nav > 0) {
        return Estimate(
            percent = fund.dayGrowth,
            nav = fund.nav,
            source = EstimateSource.NAV,
            basis = "",
            quoteTime = fund.navDate
        )
    }
    return null
}

private fun estimatedNav(nav: Double, percent: Double): Double? =
    if (nav > 0) nav * (1 + percent / 100) else null
