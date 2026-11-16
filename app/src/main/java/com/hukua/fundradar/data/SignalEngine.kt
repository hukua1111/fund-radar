package com.hukua.fundradar.data

import java.util.Calendar
import kotlin.math.abs

/** 信号方向。红涨绿跌的配色由 UI 层按国内习惯处理。 */
enum class SignalAction(val label: String) {
    BUY("买入"),
    SELL("卖出")
}

/**
 * 一条可执行的买卖提示：结论 + 触发依据。
 * 只描述「数据上发生了什么」，不替用户做决定。
 */
data class TradeSignal(
    val action: SignalAction,
    /** 信号名，如「趋势启动」。 */
    val title: String,
    /** 触发依据与失效条件，直接显示给用户。 */
    val reason: String
)

/** 当前趋势档位，与 AI 判断的 trend 定义保持同一口径。 */
enum class SignalTrend(val label: String) {
    UP("偏多"),
    RANGE("震荡"),
    DOWN("偏空"),
    UNKNOWN("数据不足")
}

/** 一只基金对应跟踪指数的一份信号报告。 */
data class SignalReport(
    val trend: SignalTrend,
    /** 指标基准日：最后一根已收盘定型的日线。 */
    val asOf: String,
    val signals: List<TradeSignal> = emptyList(),
    /** 一句话现状：收盘、相对 MA20、趋势档位。 */
    val summary: String = "",
    /** 数据不足 / 加载失败时的说明。 */
    val note: String = ""
) {
    val hasBuy: Boolean get() = signals.any { it.action == SignalAction.BUY }
    val hasSell: Boolean get() = signals.any { it.action == SignalAction.SELL }
}

/**
 * 本地规则信号引擎：只吃跟踪指数的**已收盘日线**，不调 AI、不联网。
 *
 * 与 [AiPrompt] 的关系：两者共用同一套指标（MA / MACD / BOLL / 量比）与
 * 趋势档位口径，但互不依赖 —— AI 判断要配置接口才能用，这里保证没配 AI
 * 也有信号可看；AI 结论与本地信号不一致时两边都摆出来，由用户自己决定。
 *
 * 设计原则（沿用 README 里消融实验的教训）：
 * - 信号都是**事件触发**（今天首次满足、昨天不满足），不是「处于某状态就天天报」；
 * - 每条信号必须写清触发依据和失效条件，不给没有理由的结论；
 * - 不含费率、赎回期、申赎时点 —— 这些由用户自己判断。
 */
object SignalEngine {

    /** 收盘偏离 MA20 超过这个百分比才算走出震荡，与 AiPrompt 一致。 */
    const val TREND_BAND_PCT = 0.5

    /** 收盘高于 MA20 这个幅度即视为过热区。 */
    const val OVERHEAT_DEVIATION_PCT = 8.0

    /** 过热回落需要的量比门槛。 */
    const val OVERHEAT_VOLUME_RATIO = 1.3

    /** 距近 [DRAWDOWN_WINDOW] 日高点回撤超过这个幅度触发回撤保护。 */
    const val DRAWDOWN_PCT = 10.0
    const val DRAWDOWN_WINDOW = 60

    /** MACD 柱从第 34 根起才有效，再留几根余量。 */
    private const val MIN_BARS = 35

    fun evaluate(
        raw: KlineSeries,
        /** 标的近 20 日相对沪深300 的超额（百分点），拿不到传 null。 */
        relative20: Double? = null,
        now: Calendar = Calendar.getInstance()
    ): SignalReport {
        val series = raw.completed(now = now)
        val candles = series.candles
        val latest = candles.lastOrNull()
            ?: return SignalReport(SignalTrend.UNKNOWN, "", note = "没有可用的日线数据")
        if (candles.size < MIN_BARS) {
            return SignalReport(
                SignalTrend.UNKNOWN,
                latest.date,
                note = "历史日线不足 $MIN_BARS 根（现有 ${candles.size} 根），指标还不可靠"
            )
        }

        val closes = candles.map { it.close }
        val ma5 = movingAverage(closes, 5)
        val ma10 = movingAverage(closes, 10)
        val ma20 = movingAverage(closes, 20)
        val macdLine = macd(closes)
        val hist = macdLine.hist
        val dif = macdLine.dif
        val dea = macdLine.dea
        val boll = bollingerBands(closes)

        val last = candles.size - 1
        val prev = last - 1

        fun upState(i: Int): Boolean {
            val m5 = ma5[i] ?: return false
            val m10 = ma10[i] ?: return false
            val m20 = ma20[i] ?: return false
            val h = hist[i] ?: return false
            return m20 > 0 && candles[i].close > m20 * (1 + TREND_BAND_PCT / 100) && m5 > m10 && h > 0
        }

        fun downState(i: Int): Boolean {
            val m5 = ma5[i] ?: return false
            val m10 = ma10[i] ?: return false
            val m20 = ma20[i] ?: return false
            val h = hist[i] ?: return false
            return m20 > 0 && candles[i].close < m20 * (1 - TREND_BAND_PCT / 100) && m5 < m10 && h < 0
        }

        val vsMa20 = ma20[last]?.takeIf { it > 0 }?.let { (latest.close - it) / it * 100 }
        val trend = when {
            upState(last) -> SignalTrend.UP
            downState(last) -> SignalTrend.DOWN
            else -> SignalTrend.RANGE
        }

        val signals = ArrayList<TradeSignal>()

        // ---- 买入 1：趋势启动（今天首次齐备，昨天还不满足） ----
        if (upState(last) && !upState(prev)) {
            val flipped = ArrayList<String>()
            val m20Prev = ma20[prev]
            if (m20Prev == null || candles[prev].close <= m20Prev * (1 + TREND_BAND_PCT / 100)) {
                flipped += "收盘站上MA20超${TREND_BAND_PCT}%"
            }
            if (ma5[prev] == null || ma10[prev] == null || ma5[prev]!! <= ma10[prev]!!) {
                flipped += "MA5上穿MA10"
            }
            if (hist[prev] == null || hist[prev]!! <= 0) flipped += "MACD柱转红"
            signals += TradeSignal(
                SignalAction.BUY,
                "趋势启动",
                "今日新满足：${flipped.joinToString("、")}。" +
                    "收盘 ${priceText(latest.close)}，相对MA20 ${pctText(vsMa20)}，趋势条件齐备。" +
                    "失效：收盘跌回MA20下方。"
            )
        }

        // ---- 买入 2：超卖修复（近3根内曾跌破下轨，今日收回带内 + 绿柱连续两根缩短） ----
        val lowerLast = boll.lower[last]
        val h0 = hist[last]
        val h1 = hist[prev]
        val h2 = if (last >= 2) hist[last - 2] else null
        val recentlyBelow = (1..3).any { back ->
            val i = last - back
            val lower = boll.lower.getOrNull(i)
            i >= 0 && lower != null && candles[i].close < lower
        }
        if (lowerLast != null && h0 != null && h1 != null && h2 != null &&
            recentlyBelow && latest.close > lowerLast &&
            h0 < 0 && abs(h0) < abs(h1) && abs(h1) < abs(h2)
        ) {
            signals += TradeSignal(
                SignalAction.BUY,
                "超卖修复",
                "近3日曾收于布林下轨外，今日收回带内（下轨 ${priceText(lowerLast)}），" +
                    "MACD绿柱连续两根缩短，下跌动能衰竭。属左侧信号，仓位宜轻。失效：再收破下轨。"
            )
        }

        // ---- 买入 3：强势回踩企稳（20日强于沪深300 + 今日回踩MA20后收在其上） ----
        val m20 = ma20[last]
        if (relative20 != null && relative20 > 0 && m20 != null && m20 > 0 &&
            latest.low <= m20 && latest.close > m20 && latest.close >= latest.open
        ) {
            signals += TradeSignal(
                SignalAction.BUY,
                "强势回踩企稳",
                "近20日强于沪深300 " + String.format(java.util.Locale.US, "%+.2f", relative20) +
                    " 个百分点，今日最低 ${priceText(latest.low)} " +
                    "回踩MA20（${priceText(m20)}）后收在其上。失效：收盘跌破MA20。"
            )
        }

        // ---- 买入 4：MACD金叉（DIF 今日上穿 DEA，昨天还在下方） ----
        val dLast = dif[last]
        val eLast = dea[last]
        val dPrev = dif[prev]
        val ePrev = dea[prev]
        if (dLast != null && eLast != null && dPrev != null && ePrev != null &&
            dLast > eLast && dPrev <= ePrev
        ) {
            val zone = if (eLast < 0) {
                "零轴下方金叉，属低位反转信号，左侧属性强，仓位宜轻"
            } else {
                "零轴上方金叉，属强势中继信号"
            }
            signals += TradeSignal(
                SignalAction.BUY,
                "MACD金叉",
                "DIF（${priceText(dLast)}）今日上穿 DEA（${priceText(eLast)}），$zone。" +
                    "失效：DIF 重新跌回 DEA 下方（死叉）。"
            )
        }

        // ---- 买入 5：MACD底背离（价格新低但 DIF 低点抬高，今日金叉确认） ----
        // 用绿柱段（hist<0 的连续区间）划分两段下跌：刚结束的绿柱段 vs 上一段。
        if (h0 != null && h1 != null && h0 > 0 && h1 < 0) {
            val greenRuns = signRuns(hist, last, negative = true)
            val cur = greenRuns.lastOrNull()
            val old = greenRuns.getOrNull(greenRuns.size - 2)
            if (cur != null && old != null && cur.last() == prev) {
                val priceLowCur = cur.minOf { closes[it] }
                val priceLowOld = old.minOf { closes[it] }
                val difLowCur = cur.mapNotNull { dif[it] }.minOrNull()
                val difLowOld = old.mapNotNull { dif[it] }.minOrNull()
                if (difLowCur != null && difLowOld != null &&
                    priceLowCur < priceLowOld && difLowCur > difLowOld && difLowCur < 0
                ) {
                    signals += TradeSignal(
                        SignalAction.BUY,
                        "MACD底背离",
                        "指数创新低（${priceText(priceLowOld)} → ${priceText(priceLowCur)}），" +
                            "但 DIF 低点抬高（${priceText(difLowOld)} → ${priceText(difLowCur)}），" +
                            "下跌动能背离，今日金叉确认。左侧反转信号，可能背离后再背离。" +
                            "失效：收盘跌破前低 ${priceText(priceLowCur)}。"
                    )
                }
            }
        }

        // ---- 卖出 1：趋势破坏（今天首次齐备空头条件） ----
        if (downState(last) && !downState(prev)) {
            val flipped = ArrayList<String>()
            val m20Prev = ma20[prev]
            if (m20Prev == null || candles[prev].close >= m20Prev * (1 - TREND_BAND_PCT / 100)) {
                flipped += "收盘跌破MA20超${TREND_BAND_PCT}%"
            }
            if (ma5[prev] == null || ma10[prev] == null || ma5[prev]!! >= ma10[prev]!!) {
                flipped += "MA5下穿MA10"
            }
            if (hist[prev] == null || hist[prev]!! >= 0) flipped += "MACD柱转绿"
            signals += TradeSignal(
                SignalAction.SELL,
                "趋势破坏",
                "今日新满足：${flipped.joinToString("、")}。" +
                    "收盘 ${priceText(latest.close)}，相对MA20 ${pctText(vsMa20)}，空头条件齐备。" +
                    "失效：收盘重新站上MA20。"
            )
        }

        // ---- 卖出 2：过热回落（偏离MA20过大或收于上轨外 + 放量回落） ----
        val upper = boll.upper[last]
        val ratio = series.volumeRatio()
        val overheated = (vsMa20 != null && vsMa20 >= OVERHEAT_DEVIATION_PCT) ||
            (upper != null && latest.close > upper)
        if (overheated && latest.close < latest.open && ratio != null && ratio >= OVERHEAT_VOLUME_RATIO) {
            val where = if (vsMa20 != null && vsMa20 >= OVERHEAT_DEVIATION_PCT) {
                "收盘高于MA20 ${pctText(vsMa20)}"
            } else {
                "收于布林上轨（${priceText(upper!!)}）上方"
            }
            signals += TradeSignal(
                SignalAction.SELL,
                "过热回落",
                "$where，今日放量回落（量比 ${String.format(java.util.Locale.US, "%.2f", ratio)}）。" +
                    "短线过热兑现提示，强势趋势中可能只是回踩。失效：重新放量创出新高。"
            )
        }

        // ---- 卖出 3：回撤保护（距近60日高点回撤首次超过阈值，且在MA20下方） ----
        val window = closes.takeLast(DRAWDOWN_WINDOW)
        val peak = window.max()
        val prevWindow = closes.dropLast(1).takeLast(DRAWDOWN_WINDOW)
        val prevPeak = prevWindow.maxOrNull()
        if (peak > 0 && m20 != null && latest.close < m20) {
            val drawdown = (peak - latest.close) / peak * 100
            val prevDrawdown = if (prevPeak != null && prevPeak > 0) {
                (prevPeak - candles[prev].close) / prevPeak * 100
            } else {
                0.0
            }
            if (drawdown >= DRAWDOWN_PCT && prevDrawdown < DRAWDOWN_PCT) {
                signals += TradeSignal(
                    SignalAction.SELL,
                    "回撤保护",
                    "收盘较近${DRAWDOWN_WINDOW}日高点 ${priceText(peak)} 回撤 " +
                        String.format(java.util.Locale.US, "%.1f%%", drawdown) +
                        "（首次超过${DRAWDOWN_PCT.toInt()}%），且位于MA20下方。" +
                        "控制回撤提示，是否执行结合自身成本判断。"
                )
            }
        }

        // ---- 卖出 4：MACD死叉（DIF 今日下穿 DEA，昨天还在上方） ----
        if (dLast != null && eLast != null && dPrev != null && ePrev != null &&
            dLast < eLast && dPrev >= ePrev
        ) {
            val zone = if (eLast > 0) {
                "零轴上方死叉，高位动能转弱，回落风险加大"
            } else {
                "零轴下方死叉，弱势延续"
            }
            signals += TradeSignal(
                SignalAction.SELL,
                "MACD死叉",
                "DIF（${priceText(dLast)}）今日下穿 DEA（${priceText(eLast)}），$zone。" +
                    "失效：DIF 重新站上 DEA（金叉）。"
            )
        }

        // ---- 卖出 5：MACD顶背离（价格新高但 DIF 高点降低，今日死叉确认） ----
        if (h0 != null && h1 != null && h0 < 0 && h1 > 0) {
            val redRuns = signRuns(hist, last, negative = false)
            val cur = redRuns.lastOrNull()
            val old = redRuns.getOrNull(redRuns.size - 2)
            if (cur != null && old != null && cur.last() == prev) {
                val priceHighCur = cur.maxOf { closes[it] }
                val priceHighOld = old.maxOf { closes[it] }
                val difHighCur = cur.mapNotNull { dif[it] }.maxOrNull()
                val difHighOld = old.mapNotNull { dif[it] }.maxOrNull()
                if (difHighCur != null && difHighOld != null &&
                    priceHighCur > priceHighOld && difHighCur < difHighOld && difHighCur > 0
                ) {
                    signals += TradeSignal(
                        SignalAction.SELL,
                        "MACD顶背离",
                        "指数创新高（${priceText(priceHighOld)} → ${priceText(priceHighCur)}），" +
                            "但 DIF 高点降低（${priceText(difHighOld)} → ${priceText(difHighCur)}），" +
                            "上涨动能背离，今日死叉确认。强势趋势中可能只是中继整理。" +
                            "失效：收盘突破前高 ${priceText(priceHighCur)}。"
                    )
                }
            }
        }

        val summary = "收盘 ${priceText(latest.close)} · 相对MA20 ${pctText(vsMa20)} · ${trend.label}"
        return SignalReport(trend, latest.date, signals, summary)
    }

    /**
     * 把 MACD 柱按符号切成连续区间（只统计 [upTo] 及之前、值非空的柱子）。
     * 底背离/顶背离用「上一段 vs 刚结束的一段」比较价格与 DIF 的极值。
     */
    private fun signRuns(hist: List<Double?>, upTo: Int, negative: Boolean): List<List<Int>> {
        val runs = ArrayList<List<Int>>()
        var current = ArrayList<Int>()
        for (i in 0..upTo) {
            val h = hist[i]
            val match = h != null && h != 0.0 && (if (negative) h < 0 else h > 0)
            if (match) {
                current += i
            } else if (current.isNotEmpty()) {
                runs += current
                current = ArrayList()
            }
        }
        if (current.isNotEmpty()) runs += current
        return runs
    }

    private fun pctText(value: Double?): String =
        value?.let { String.format(java.util.Locale.US, "%+.2f%%", it) } ?: "无"
}