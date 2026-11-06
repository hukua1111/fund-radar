package com.hukua.fundradar.data

import java.util.Locale

/** Where an intraday estimate comes from. */
enum class EstimateSource {
    /**
     * 基金跟踪的标的指数 —— 默认口径。
     *
     * 场外基金按指数成分与权重复制组合，扣费与现金留存带来的偏差每天都
     * 会被净值消化；反而 ETF 的场内价格还夹着溢价/折价，用 ETF 估净值会
     * 多一层噪声。用户拿一年数据回测也确认指数更贴净值，所以指数优先。
     */
    INDEX,

    /**
     * 目标 ETF 的场内涨跌 —— 指数识别不出来时的第一备选。
     */
    ETF,

    /** Weighted move of the fund's top holdings, for funds without an index. */
    HOLDINGS,

    /** 什么都取不到时退回最近一次公布的净值涨跌（非实时）。 */
    NAV
}

/**
 * Intraday estimate for a fund.
 *
 * 场外基金一天只公布一个净值，估值的办法是找一个「盘中会动、又和它同源」的
 * 东西。按贴合程度排序：跟踪标的指数 → 目标 ETF → 前十大重仓股 → 最近净值。
 *
 * 指数排第一：场外基金就是照着指数复制组合的，长期看净值与指数的偏离只在
 * 费率量级；ETF 的场内价格还含溢价/折价，日内波动比净值多一层噪声。
 * 目标 ETF 只在指数识别不出来（名称对不上、目录里没有）时兜底。
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
    val coverage: Double = 0.0,
    /** 这只基金联接的目标 ETF（没有则为空），用来在界面上说清楚估值到底按谁算。 */
    val etfCode: String = "",
    val etfName: String = "",
    /** 目标 ETF 当日的涨跌，指数口径下作对照用；没有行情则为 null。 */
    val etfPercent: Double? = null,
    /** 降级原因，例如「目标 ETF 行情暂缺」。 */
    val note: String = ""
) {
    val sourceLabel: String
        get() = when (source) {
            EstimateSource.INDEX -> "按跟踪指数估算"
            EstimateSource.ETF -> "按目标 ETF 估算"
            EstimateSource.HOLDINGS -> "按重仓股估算"
            EstimateSource.NAV -> "最近公布净值"
        }

    /** 目标 ETF 的展示名，例如「煤炭ETF国泰 515220」。 */
    val etfLabel: String
        get() = listOf(etfName, etfCode).filter { it.isNotBlank() }.joinToString(" ")

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
            if (note.isNotBlank()) {
                append(" · ")
                append(note)
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
 * 依次尝试：跟踪标的指数 → 目标 ETF → 重仓股加权 → 最近公布的净值。
 *
 * 指数优先的理由写在 [EstimateSource.INDEX] 上：场外基金复制的是指数，不是
 * ETF 的场内价格。ETF 在这个顺序里只做兜底，指数识别不出来时才会用到。
 * 重仓股留给没有跟踪标的的主动基金 —— 指数基金的前十大只占四五成，加权
 * 出来的涨跌会系统性偏小。
 */
fun estimateFund(
    fund: Fund,
    position: FundPosition?,
    quotes: Map<String, RealtimeQuote>,
    /** 跟踪标的与它的实时行情。 */
    index: Pair<IndexEntry, RealtimeQuote>? = null
): Estimate? {
    val etfQuote = position?.etfCode
        ?.takeIf { it.isNotBlank() }
        ?.let { quotes[exchangeSymbol(it)] }

    // 1) 标的指数。有就先用它，不管有没有目标 ETF。
    if (index != null) {
        val (entry, quote) = index
        return Estimate(
            percent = quote.changePercent,
            nav = estimatedNav(fund.nav, quote.changePercent),
            source = EstimateSource.INDEX,
            basis = entry.name.ifBlank { entry.code },
            quoteTime = quote.time,
            etfCode = position?.etfCode.orEmpty(),
            etfName = position?.etfName.orEmpty(),
            etfPercent = etfQuote?.changePercent
        )
    }

    // 2) 指数没识别出来，退到目标 ETF，并把降级原因写清楚。
    if (etfQuote != null) {
        return Estimate(
            percent = etfQuote.changePercent,
            nav = estimatedNav(fund.nav, etfQuote.changePercent),
            source = EstimateSource.ETF,
            basis = listOf(
                position.etfName.ifBlank { etfQuote.name },
                position.etfCode
            ).filter { it.isNotBlank() }.joinToString(" "),
            quoteTime = etfQuote.time,
            etfCode = position.etfCode,
            etfName = position.etfName.ifBlank { etfQuote.name },
            etfPercent = etfQuote.changePercent,
            note = "标的指数未匹配，改用目标 ETF"
        )
    }

    val etfMissingNote = position?.etfCode
        ?.takeIf { it.isNotBlank() }
        ?.let { "目标ETF $it 行情暂缺" }
        .orEmpty()

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
                    coverage = priced.sumOf { it.first },
                    etfCode = position.etfCode,
                    etfName = position.etfName,
                    note = etfMissingNote
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
            quoteTime = fund.navDate,
            etfCode = position?.etfCode.orEmpty(),
            etfName = position?.etfName.orEmpty()
        )
    }
    return null
}

private fun estimatedNav(nav: Double, percent: Double): Double? =
    if (nav > 0) nav * (1 + percent / 100) else null
