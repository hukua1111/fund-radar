package com.hukua.fundradar.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** 一笔买入或卖出。 */
enum class TradeKind { BUY, SELL }

/**
 * 一条交易记录，只记「份额 × 成交净值」。手续费、分红再投不单独建模：用户
 * 直接填实际成交金额对应的份额和净值即可，误差会体现在成本里。
 */
data class Trade(
    /** 毫秒时间戳，同时用作列表里的 key。 */
    val id: Long,
    val kind: TradeKind,
    val shares: Double,
    val nav: Double,
    val at: Long,
    val note: String = ""
) {
    /** 成交金额。 */
    val amount: Double get() = shares * nav

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind.name)
        put("shares", shares)
        put("nav", nav)
        put("at", at)
        put("note", note)
    }

    companion object {
        fun fromJson(json: JSONObject): Trade = Trade(
            id = json.optLong("id", 0L),
            kind = if (json.optString("kind") == TradeKind.SELL.name) {
                TradeKind.SELL
            } else {
                TradeKind.BUY
            },
            shares = json.optDouble("shares", 0.0),
            nav = json.optDouble("nav", 0.0),
            at = json.optLong("at", 0L),
            note = json.optString("note")
        )
    }
}

/**
 * 一只基金的持仓：份额、成本、已实现盈亏，以及最近的买卖记录。
 *
 * 成本按**摊薄成本**维护（卖出时按当前均价扣减，不重算历史），这也是基金
 * App 里最常见的一种口径。
 */
data class Holding(
    /** 持有份额。 */
    val shares: Double = 0.0,
    /** 持仓成本（元），含手续费的实际投入。 */
    val cost: Double = 0.0,
    /** 已经卖出部分的累计盈亏（元）。 */
    val realized: Double = 0.0,
    /** 最新的在前。 */
    val trades: List<Trade> = emptyList()
) {
    val isEmpty: Boolean get() = shares <= EPS && cost <= 0.005

    /** 成本单价 = 成本 / 份额。 */
    val avgCost: Double get() = if (shares > EPS) cost / shares else 0.0

    fun marketValue(nav: Double): Double = if (nav > 0) shares * nav else 0.0

    /** 浮动盈亏 = 市值 - 成本。 */
    fun profit(nav: Double): Double = marketValue(nav) - cost

    /** 浮动收益率（%），没有成本时为 null。 */
    fun profitPercent(nav: Double): Double? =
        if (cost > 0.005) profit(nav) / cost * 100.0 else null

    /**
     * 今日盈亏：用盘中估算的涨跌幅反推昨收市值。
     * 市值 × p / (100 + p)，p 为涨跌幅（%）。
     */
    fun todayProfit(nav: Double, percent: Double): Double? {
        if (shares <= EPS || nav <= 0) return null
        val denominator = 100.0 + percent
        if (denominator <= 0.0) return null
        return marketValue(nav) * percent / denominator
    }

    /** 直接改写份额与成本（用户照着自己的持仓页填），已实现盈亏与记录保留。 */
    fun withBase(shares: Double, cost: Double): Holding = copy(
        shares = shares.coerceAtLeast(0.0),
        cost = cost.coerceAtLeast(0.0)
    )

    /** 记一笔交易并更新份额与成本。 */
    fun applied(trade: Trade): Holding = when (trade.kind) {
        TradeKind.BUY -> copy(
            shares = shares + trade.shares,
            cost = cost + trade.amount,
            trades = (listOf(trade) + trades).take(MAX_TRADES)
        )

        TradeKind.SELL -> {
            // 多卖的份额按实际持有封顶，记录里也只留真正卖掉的那部分。
            val sold = minOf(trade.shares, shares).coerceAtLeast(0.0)
            val costOut = avgCost * sold
            val remaining = (shares - sold).coerceAtLeast(0.0)
            copy(
                shares = if (remaining <= EPS) 0.0 else remaining,
                cost = if (remaining <= EPS) 0.0 else (cost - costOut).coerceAtLeast(0.0),
                realized = realized + (sold * trade.nav - costOut),
                trades = (listOf(trade.copy(shares = sold)) + trades).take(MAX_TRADES)
            )
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("shares", shares)
        put("cost", cost)
        put("realized", realized)
        val rows = JSONArray()
        trades.forEach { rows.put(it.toJson()) }
        put("trades", rows)
    }

    companion object {
        /** 小于这个份额就当作清仓，避免浮点误差留下 0.0000001 份。 */
        const val EPS = 1e-6

        /** 每只基金最多留这么多条记录。 */
        const val MAX_TRADES = 100

        fun fromJson(json: JSONObject): Holding {
            val trades = ArrayList<Trade>()
            val rows = json.optJSONArray("trades")
            if (rows != null) {
                for (index in 0 until rows.length()) {
                    rows.optJSONObject(index)?.let { trades += Trade.fromJson(it) }
                }
            }
            return Holding(
                shares = json.optDouble("shares", 0.0),
                cost = json.optDouble("cost", 0.0),
                realized = json.optDouble("realized", 0.0),
                trades = trades
            )
        }
    }
}

// --------------------------------------------------------------- formatting

/** 12,345.67 —— 金额，不带货币符号。 */
fun amountText(value: Double): String = String.format(Locale.US, "%,.2f", value)

/** +12,345.67 / -123.40 —— 带符号的盈亏。 */
fun signedAmountText(value: Double): String {
    val sign = if (value > 0) "+" else if (value < 0) "-" else ""
    return sign + String.format(Locale.US, "%,.2f", kotlin.math.abs(value))
}

/** 1,234.5678 —— 份额保留四位，对齐基金公司口径。 */
fun sharesText(value: Double): String = String.format(Locale.US, "%,.4f", value)
