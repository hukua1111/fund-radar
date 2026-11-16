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
    /** 指标基准日：最后一根日线；盘中就是今天（未收盘）。 */
    val asOf: String,
    val signals: List<TradeSignal> = emptyList(),
    /** 一句话现状：收盘、相对 MA20、趋势档位。 */
    val summary: String = "",
    /** 数据不足 / 加载失败时的说明。 */
    val note: String = "",
    /** 最后一根是盘中未收盘的实时 bar：信号收盘前可能变化或消失。 */
    val live: Boolean = false
) {
    val hasBuy: Boolean get() = signals.any { it.action == SignalAction.BUY }
    val hasSell: Boolean get() = signals.any { it.action == SignalAction.SELL }
}

/**
 * 本地规则信号引擎：只吃跟踪指数的日线，不调 AI、不联网。
 *
 * **实时口径**：盘中未收盘那根也参与计算（[SignalReport.live] 标记），
 * 信号更灵敏，但收盘前可能变化或消失；15:00 后自动切换为定型结果。
 * 盘中量比按已交易时间折算成全天口径。AI 判断（[AiPrompt]）仍只用
 * 已收盘日线，两边口径不同、互不依赖，结论不一致时都摆出来由用户决定。
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

    /** 放量突破 / 支撑跌破回看的收盘价窗口（不含当日）。 */
    const val BREAKOUT_WINDOW = 60

    /** 放量突破需要的量比门槛。 */
    const val BREAKOUT_VOLUME_RATIO = 1.5

    /** RSI(14) 超卖 / 超买分界。 */
    const val RSI_OVERSOLD = 30.0
    const val RSI_OVERBOUGHT = 70.0

    /** KDJ 金叉只认 D 低于此值的低位区，死叉只认 D 高于此值的高位区。 */
    const val KDJ_LOW = 25.0
    const val KDJ_HIGH = 75.0

    /** 吞没形态要求的位置：收盘相对 MA20 的偏离百分比。 */
    const val ENGULF_LOW_PCT = -3.0
    const val ENGULF_HIGH_PCT = 5.0

    /** MACD 柱从第 34 根起才有效，再留几根余量。 */
    private const val MIN_BARS = 35

    fun evaluate(
        raw: KlineSeries,
        /** 标的近 20 日相对沪深300 的超额（百分点），拿不到传 null。 */
        relative20: Double? = null,
        now: Calendar = Calendar.getInstance()
    ): SignalReport {
        // 实时口径：盘中未收盘那根也参与计算，信号更灵敏，但收盘前可能变化。
        val series = raw
        val live = raw.hasOpenBar(now)
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
        val rsi14 = rsi(closes)
        val kdjLine = kdj(candles)

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

        // ---- 买入 6：放量突破（收盘创近60日收盘新高 + 量比确认，昨天还没突破） ----
        val priorHigh = closes.subList(maxOf(0, last - BREAKOUT_WINDOW), last).maxOrNull()
        val priorLow = closes.subList(maxOf(0, last - BREAKOUT_WINDOW), last).minOrNull()
        val prevPriorHigh = closes.subList(maxOf(0, prev - BREAKOUT_WINDOW), prev).maxOrNull()
        val prevPriorLow = closes.subList(maxOf(0, prev - BREAKOUT_WINDOW), prev).minOrNull()
        // 量比：盘中按已交易时间折算成全天口径，否则上午的量永远显得缩量。
        val lastRatio = if (live) liveVolumeRatio(series, now) else series.volumeRatio()
        if (priorHigh != null && latest.close > priorHigh &&
            (prevPriorHigh == null || candles[prev].close <= prevPriorHigh) &&
            lastRatio != null && lastRatio >= BREAKOUT_VOLUME_RATIO
        ) {
            signals += TradeSignal(
                SignalAction.BUY,
                "放量突破",
                "收盘 ${priceText(latest.close)} 创近${BREAKOUT_WINDOW}日收盘新高" +
                    "（前高 ${priceText(priorHigh)}），量比 " +
                    String.format(java.util.Locale.US, "%.2f", lastRatio) +
                    "（≥$BREAKOUT_VOLUME_RATIO）确认，有量配合的突破。" +
                    "失效：收盘跌回 ${priceText(priorHigh)} 下方（假突破）。"
            )
        }

        // ---- 买入 7：RSI超卖修复（RSI14 今日收回 30 上方，昨天还在超卖区） ----
        val rsiLast = rsi14[last]
        val rsiPrev = rsi14[prev]
        if (rsiLast != null && rsiPrev != null &&
            rsiLast > RSI_OVERSOLD && rsiPrev <= RSI_OVERSOLD
        ) {
            signals += TradeSignal(
                SignalAction.BUY,
                "RSI超卖修复",
                "RSI(14) 从超卖区回升至 ${oscText(rsiLast)}（昨日 ${oscText(rsiPrev)} ≤${RSI_OVERSOLD.toInt()}），" +
                    "抛压衰竭。左侧信号，超卖可以更深。失效：RSI 重新跌回 ${RSI_OVERSOLD.toInt()} 下方。"
            )
        }

        // ---- 买入 8：KDJ低位金叉（K 上穿 D 且 D 在低位区） ----
        val kLast = kdjLine.k[last]
        val dKLast = kdjLine.d[last]
        val kPrev = kdjLine.k[prev]
        val dKPrev = kdjLine.d[prev]
        if (kLast != null && dKLast != null && kPrev != null && dKPrev != null &&
            kLast > dKLast && kPrev <= dKPrev && dKLast < KDJ_LOW
        ) {
            signals += TradeSignal(
                SignalAction.BUY,
                "KDJ低位金叉",
                "K（${oscText(kLast)}）今日上穿 D（${oscText(dKLast)}），D 低于 ${KDJ_LOW.toInt()} 的低位区，" +
                    "短线超卖后的转折尝试。KDJ 灵敏、假信号多，宜与趋势类信号互相印证。" +
                    "失效：K 重新跌回 D 下方。"
            )
        }

        // ---- 买入 9：低位看涨吞没（偏离MA20达-3%以下，今日阳线实体吞没昨日阴线） ----
        val prevCandle = candles[prev]
        if (vsMa20 != null && vsMa20 <= ENGULF_LOW_PCT &&
            prevCandle.close < prevCandle.open &&
            latest.close > latest.open &&
            latest.close >= prevCandle.open && latest.open <= prevCandle.close
        ) {
            signals += TradeSignal(
                SignalAction.BUY,
                "低位看涨吞没",
                "收盘相对MA20 ${pctText(vsMa20)} 的低位，今日阳线（${priceText(latest.open)} → " +
                    "${priceText(latest.close)}）吞没昨日阴线（${priceText(prevCandle.open)} → " +
                    "${priceText(prevCandle.close)}），买方单日反扑。" +
                    "失效：收盘跌破今日开盘 ${priceText(latest.open)}。"
            )
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
        val ratio = lastRatio
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

        // ---- 卖出 6：支撑跌破（收盘创近60日收盘新低，昨天还没跌破） ----
        if (priorLow != null && latest.close < priorLow &&
            (prevPriorLow == null || candles[prev].close >= prevPriorLow)
        ) {
            signals += TradeSignal(
                SignalAction.SELL,
                "支撑跌破",
                "收盘 ${priceText(latest.close)} 跌破近${BREAKOUT_WINDOW}日收盘低点" +
                    "（${priceText(priorLow)}），支撑失效，下方缺少近期筹码参照。" +
                    "失效：收盘重新站上 ${priceText(priorLow)}。"
            )
        }

        // ---- 卖出 7：RSI超买回落（RSI14 今日跌回 70 下方，昨天还在超买区） ----
        if (rsiLast != null && rsiPrev != null &&
            rsiLast < RSI_OVERBOUGHT && rsiPrev >= RSI_OVERBOUGHT
        ) {
            signals += TradeSignal(
                SignalAction.SELL,
                "RSI超买回落",
                "RSI(14) 从超买区回落至 ${oscText(rsiLast)}（昨日 ${oscText(rsiPrev)} ≥${RSI_OVERBOUGHT.toInt()}），" +
                    "上冲动能降温。强势趋势中 RSI 可长期钝化，未必立刻下跌。" +
                    "失效：RSI 重新站上 ${RSI_OVERBOUGHT.toInt()}。"
            )
        }

        // ---- 卖出 8：KDJ高位死叉（K 下穿 D 且 D 在高位区） ----
        if (kLast != null && dKLast != null && kPrev != null && dKPrev != null &&
            kLast < dKLast && kPrev >= dKPrev && dKLast > KDJ_HIGH
        ) {
            signals += TradeSignal(
                SignalAction.SELL,
                "KDJ高位死叉",
                "K（${oscText(kLast)}）今日下穿 D（${oscText(dKLast)}），D 高于 ${KDJ_HIGH.toInt()} 的高位区，" +
                    "短线过热后的回落尝试。KDJ 灵敏、假信号多，宜与趋势类信号互相印证。" +
                    "失效：K 重新站上 D。"
            )
        }

        // ---- 卖出 9：高位看跌吞没（偏离MA20达+5%以上，今日阴线实体吞没昨日阳线） ----
        if (vsMa20 != null && vsMa20 >= ENGULF_HIGH_PCT &&
            prevCandle.close > prevCandle.open &&
            latest.close < latest.open &&
            latest.open >= prevCandle.close && latest.close <= prevCandle.open
        ) {
            signals += TradeSignal(
                SignalAction.SELL,
                "高位看跌吞没",
                "收盘相对MA20 ${pctText(vsMa20)} 的高位，今日阴线（${priceText(latest.open)} → " +
                    "${priceText(latest.close)}）吞没昨日阳线（${priceText(prevCandle.open)} → " +
                    "${priceText(prevCandle.close)}），卖方单日反扑。" +
                    "失效：收盘突破今日开盘 ${priceText(latest.open)}。"
            )
        }

        val priceLabel = if (live) "现价" else "收盘"
        val summary = "$priceLabel ${priceText(latest.close)} · 相对MA20 ${pctText(vsMa20)} · ${trend.label}"
        return SignalReport(trend, latest.date, signals, summary, live = live)
    }

    /**
     * 盘中量比：把当日已成交量按「已交易分钟数」折算成全天口径，再除以
     * 前 5 日均量。与行情软件的量比定义一致，上午半天的量不会被误判成缩量。
     */
    private fun liveVolumeRatio(series: KlineSeries, now: Calendar): Double? {
        val average = series.averageVolume() ?: return null
        val volume = series.latest?.volume ?: return null
        if (average <= 0.0 || volume <= 0.0) return null
        val minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val morning = (minutes - (9 * 60 + 30)).coerceIn(0, 120)
        val afternoon = (minutes - 13 * 60).coerceIn(0, 120)
        val elapsed = (morning + afternoon).coerceAtLeast(1)
        return (volume / elapsed) / (average / 240.0)
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

    /** 摆动指标（RSI / K / D）数值文本。 */
    private fun oscText(value: Double): String =
        String.format(java.util.Locale.US, "%.1f", value)
}