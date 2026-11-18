package com.hukua.fundradar.data

import java.util.Calendar
import java.util.Locale

/**
 * 按计划回放出来的定投结果。
 *
 * 用户不需要手工记账：给定「从哪天开始、多久一期、每期多少」，拿指数的日线就能
 * 一期一期地模拟买入（当天不是交易日就顺延到下一个交易日），得到真实的期数、
 * 累计投入、持有份额和当前市值 —— 这就是「我的持仓」在指数口径下的样子。
 *
 * [maxLossPct] 是过程中最难看的那一天：投入一万、市值只剩七千，就是 -30%。
 * 定投能不能拿住，往往取决于这个数字，而不是终点收益。
 */
data class DcaSimulation(
    val periods: Int = 0,
    val invested: Double = 0.0,
    val units: Double = 0.0,
    val value: Double = 0.0,
    val profit: Double = 0.0,
    val profitPct: Double = 0.0,
    val maxLossPct: Double = 0.0,
    val firstBuy: String = "",
    val lastBuy: String = "",
    /** 下一期的日期，还没开始投的时候就是第一期。 */
    val nextDue: String = "",
    /** 最后一个可用的收盘价。 */
    val lastClose: Double = 0.0
) {
    val started: Boolean get() = periods > 0

    /** 「已投 12 期 · 投入 12,000 元 · 市值 13,480 元」。 */
    fun investedText(): String =
        if (started) "已投 $periods 期 · 投入 ${money(invested)}"
        else "还没有扣过款" + if (nextDue.isNotBlank()) "（下一期 $nextDue）" else ""
}

/** 定投回放器：纯函数，只吃日线和计划，不联网。 */
object DcaSimulator {

    fun run(
        candles: List<Candle>,
        plan: DcaPlan,
        now: Calendar = Calendar.getInstance()
    ): DcaSimulation? {
        if (!plan.enabled) return null
        val start = plan.start ?: return null
        if (candles.isEmpty()) return null

        val today = dayOnly(now)
        var due = plan.firstDue() ?: return null
        var invested = 0.0
        var units = 0.0
        var periods = 0
        var firstBuy = ""
        var lastBuy = ""
        var worst = 0.0

        // 只看到今天为止已经定型的日线；盘中那根不参与，避免收益每分钟抖一次。
        val usable = ArrayList<Candle>(candles.size)
        candles.forEach { candle ->
            val day = parseDayText(candle.date) ?: return@forEach
            if (day.after(today)) return@forEach
            usable += candle
        }

        usable.forEach { candle ->
            val day = parseDayText(candle.date) ?: return@forEach
            if (day.before(start)) return@forEach
            if (day.before(due)) return@forEach
            if (candle.close <= 0.0) return@forEach

            invested += plan.amount
            units += plan.amount / candle.close
            periods += 1
            if (firstBuy.isEmpty()) firstBuy = candle.date
            lastBuy = candle.date

            val floating = (units * candle.close - invested) / invested * 100.0
            if (floating < worst) worst = floating

            due = plan.nextDueAfter(day)
        }

        val lastClose = usable.lastOrNull()?.close ?: 0.0
        val value = units * lastClose
        val profit = if (periods > 0) value - invested else 0.0
        val profitPct = if (invested > 0) profit / invested * 100.0 else 0.0

        return DcaSimulation(
            periods = periods,
            invested = invested,
            units = units,
            value = value,
            profit = profit,
            profitPct = profitPct,
            maxLossPct = worst,
            firstBuy = firstBuy,
            lastBuy = lastBuy,
            nextDue = dayText(due),
            lastClose = lastClose
        )
    }

    internal fun num(value: Double, digits: Int): String =
        String.format(Locale.US, "%.${digits}f", value)
}
