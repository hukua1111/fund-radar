package com.hukua.fundradar.data

import java.util.Calendar
import java.util.Locale
import kotlin.math.sqrt

/** 定投结论：这个行业主题指数值不值得按计划买。 */
enum class DcaVerdict(val label: String) {
    GOOD("适合定投"),
    WATCH("谨慎观察"),
    POOR("暂不适合")
}

/** 定投动作：现在该做什么。 */
enum class DcaAction(val label: String) {
    START("可以开始定投"),
    CONTINUE("继续定投"),
    REDUCE("单期金额减半"),
    PAUSE("暂停定投"),
    TAKE_PROFIT("分批止盈"),
    STOP("停止定投并考虑卖出")
}

/** 评分里的一项，[score] 满分 [max]。 */
data class DcaFactor(
    val name: String,
    val score: Int,
    val max: Int,
    val detail: String
)

/**
 * 一个指数的定投判断结果。
 *
 * [score] 是「值不值得按计划买」的中长期评分（0–100），[action] 是「现在该做什么」，
 * 两者刻意分开：一个长期适合定投的行业，短期过热时也该减半；一个长期一般的行业，
 * 深跌之后也可能值得小额开始。混成一个结论会互相打架。
 */
data class DcaReport(
    val score: Int = 0,
    val verdict: DcaVerdict = DcaVerdict.WATCH,
    val action: DcaAction = DcaAction.PAUSE,
    val actionReason: String = "",
    val factors: List<DcaFactor> = emptyList(),
    val trend: SignalTrend = SignalTrend.UNKNOWN,
    val asOf: String = "",
    val summary: String = "",
    val note: String = "",
    val indexName: String = "",
    val close: Double = 0.0,
    /** 现价在近一年收盘价区间里的位置，0 = 最低，1 = 最高。 */
    val percentile: Double? = null,
    /** 距近一年最高收盘的回撤（%）。 */
    val drawdown: Double? = null,
    /** 年化波动率（%）。 */
    val volatility: Double? = null,
    /** 按计划回放出来的定投结果，没设计划时为 null。 */
    val simulation: DcaSimulation? = null,
    /** 这个指数的短线信号，来自 [SignalEngine]，可为空。 */
    val signal: SignalReport? = null,
    val live: Boolean = false
) {
    val profitPercent: Double? get() = simulation?.takeIf { it.started }?.profitPct
}

/**
 * 定投判断引擎：只吃指数日线，本地计算，不联网、不调 AI。
 *
 * 与 [SignalEngine] 的分工：信号页回答「这根 K 线今天发生了什么」，定投页回答
 * 「这个行业还值不值得按计划买、现在该不该继续扣」。定投看的是中长期趋势、位置、
 * 波动与热度，短线的金叉死叉只作为「加减速」的佐证。
 *
 * 五档评分固定 100 分，每一档都给出依据文字，界面上直接摆出来 —— 只给一个分数
 * 用户没法复核，也就没法建立信任。
 */
object DcaEngine {

    /** 收盘偏离 MA20 超过这个百分比才算走出震荡，与 [SignalEngine] 同口径。 */
    const val TREND_BAND_PCT = 0.5

    /** 低于这个分数就不建议开始 / 继续定投。 */
    const val POOR_SCORE = 45

    /** 到这个分数才算「适合」；中间地带是「谨慎观察」。 */
    const val GOOD_SCORE = 70

    fun evaluate(
        entry: IndexEntry,
        series: KlineSeries,
        plan: DcaPlan = DcaPlan(),
        signal: SignalReport? = null,
        now: Calendar = Calendar.getInstance()
    ): DcaReport {
        // 盘中那根每跳一次指标就变一次，定投判断用定型的日线，避免同一小时内改口。
        val candles = series.completed(now = now).candles
        val closes = candles.map { it.close }
        val live = series.hasOpenBar(now = now)
        val asOf = candles.lastOrNull()?.date.orEmpty()
        val indexName = entry.name.ifBlank { series.name.ifBlank { entry.displayName } }

        if (closes.size < 60) {
            return DcaReport(
                score = 0,
                verdict = DcaVerdict.WATCH,
                action = DcaAction.PAUSE,
                actionReason = "可用日线只有 ${closes.size} 根，定投判断至少要 60 根",
                trend = signal?.trend ?: SignalTrend.UNKNOWN,
                asOf = asOf,
                note = "指数历史太短，先看「信号」页的短线信号，等日线攒够 60 根再看定投",
                indexName = indexName,
                signal = signal,
                live = live
            )
        }

        val close = closes.last()
        val ma20 = avg(closes, 20)
        val ma60 = avg(closes, 60)
        val ma120 = avg(closes, 120)
        val ma120Prev = if (closes.size >= 140) avg(closes.dropLast(20), 120) else null

        val dev20 = if (ma20 != null && ma20 > 0) (close / ma20 - 1.0) * 100.0 else 0.0
        val dev120 = if (ma120 != null && ma120 > 0) (close / ma120 - 1.0) * 100.0 else 0.0
        val slope120 = if (ma120 != null && ma120Prev != null) {
            (ma120 / ma120Prev - 1.0) * 100.0
        } else {
            null
        }

        // ---- 1. 长期趋势（30）--------------------------------------------
        val (trendScore, trendDetail) = when {
            ma120 == null ->
                15 to "历史不足 120 根，长期趋势还看不出来"

            close >= ma120 && (slope120 ?: 0.0) > 0.5 ->
                30 to "收盘在 120 日均线上方 ${num(dev120, 1)}%，均线仍在上行"

            close >= ma120 ->
                23 to "收盘在 120 日均线上方 ${num(dev120, 1)}%，但均线已经走平"

            (slope120 ?: 0.0) > 0.5 ->
                20 to "收盘跌破 120 日均线 ${num(-dev120, 1)}%，长期均线仍向上，属于上升趋势中的回调"

            (slope120 ?: 0.0) > -0.5 ->
                12 to "收盘在 120 日均线下方 ${num(-dev120, 1)}%，长期均线走平"

            else ->
                5 to "收盘在 120 日均线下方 ${num(-dev120, 1)}%，长期均线向下"
        }

        // ---- 2. 位置与回撤（25）------------------------------------------
        val window = closes.takeLast(minOf(closes.size, 250))
        val low = window.minOrNull() ?: close
        val high = window.maxOrNull() ?: close
        val percentile = if (high - low > 1e-9) (close - low) / (high - low) else 0.5
        val drawdown = if (high > 0) (high - close) / high * 100.0 else 0.0
        val pctText = num(percentile * 100.0, 0) + "%"

        val (posScore, posDetail) = when {
            percentile <= 0.2 ->
                25 to "现价在近一年 ${pctText} 分位（低位），每期成本有优势"

            percentile <= 0.4 ->
                21 to "现价在近一年 ${pctText} 分位（偏低），下跌时能多拿份额"

            percentile <= 0.6 ->
                16 to "现价在近一年 ${pctText} 分位（中位），主要靠时间摊成本"

            percentile <= 0.8 ->
                10 to "现价在近一年 ${pctText} 分位（偏高），定投性价比一般"

            else ->
                4 to "现价在近一年 ${pctText} 分位（高位），现在开始要拉长期数"
        }

        // ---- 3. 波动（20）------------------------------------------------
        val returns = closes.takeLast(251).zipWithNext { a, b -> if (a > 0) (b - a) / a else 0.0 }
        val mean = if (returns.isEmpty()) 0.0 else returns.average()
        val variance = if (returns.isEmpty()) {
            0.0
        } else {
            returns.sumOf { (it - mean) * (it - mean) } / returns.size
        }
        val volatility = sqrt(variance) * sqrt(252.0) * 100.0
        val volText = num(volatility, 1) + "%"

        val (volScore, volDetail) = when {
            volatility in 18.0..35.0 ->
                20 to "年化波动 ${volText}，有波动才有摊薄空间，适合按计划投"

            volatility in 12.0..35.0 || volatility in 35.0..45.0 ->
                15 to "年化波动 ${volText}，波动适中"

            volatility < 12.0 ->
                9 to "年化波动 ${volText}，波动偏小，定投的摊薄效果有限"

            volatility <= 60.0 ->
                9 to "年化波动 ${volText}，波动偏大，单期金额要控制"

            else ->
                5 to "年化波动 ${volText}，波动过大，行业风险集中"
        }

        // ---- 4. 短期热度（15）--------------------------------------------
        val (heatScore, heatDetail) = when {
            dev20 > 8.0 ->
                3 to "较 20 日均线高 ${num(dev20, 1)}%，短期偏热，不适合追加"

            dev20 > 3.0 ->
                11 to "较 20 日均线高 ${num(dev20, 1)}%，略偏强"

            dev20 >= -5.0 ->
                15 to "贴着 20 日均线（${num(dev20, 1)}%），位置适中"

            dev20 >= -12.0 ->
                12 to "较 20 日均线低 ${num(-dev20, 1)}%，回调中，继续投能摊低成本"

            else ->
                8 to "较 20 日均线低 ${num(-dev20, 1)}%，超跌，但要防趋势继续走弱"
        }

        // ---- 5. 历史长度（10）--------------------------------------------
        val (histScore, histDetail) = when {
            closes.size >= 250 ->
                10 to "可用日线 ${closes.size} 根，覆盖一个完整年度"

            closes.size >= 120 ->
                6 to "可用日线 ${closes.size} 根，覆盖约半年"

            else ->
                3 to "可用日线 ${closes.size} 根，样本偏短"
        }

        val score = trendScore + posScore + volScore + heatScore + histScore
        val verdict = when {
            score >= GOOD_SCORE -> DcaVerdict.GOOD
            score >= 50 -> DcaVerdict.WATCH
            else -> DcaVerdict.POOR
        }

        // ---- 现在该做什么 -------------------------------------------------
        val rsi14 = rsi(closes)
        val trend = signal?.trend ?: when {
            ma20 == null || ma60 == null -> SignalTrend.UNKNOWN
            dev20 > TREND_BAND_PCT && ma20 > ma60 -> SignalTrend.UP
            dev20 < -TREND_BAND_PCT && ma20 < ma60 -> SignalTrend.DOWN
            else -> SignalTrend.RANGE
        }

        // 定投收益按计划回放：从起始日一期一期买到今天，不是另记一套持仓账。
        val simulation = DcaSimulator.run(candles, plan, now)
        val profitPct = simulation?.takeIf { it.started }?.profitPct
        val target = plan.target
        val overheat = dev20 > 10.0 && (rsi14 ?: 0.0) > 72.0
        val broken = trend == SignalTrend.DOWN && ma60 != null && close < ma60
        val longDown = ma120 != null && close < ma120 && (slope120 ?: 0.0) < -0.5
        val deepDrawdown = drawdown >= 25.0 && trend == SignalTrend.DOWN
        val cadence = plan.cadenceText()

        val (action, reason) = when {
            profitPct != null && profitPct >= target && trend == SignalTrend.DOWN ->
                DcaAction.STOP to "计划已浮盈 ${num(profitPct, 1)}%，达到 ${num(target, 0)}% 止盈线，" +
                    "指数同时转空；先停扣，再分批卖出，别把浮盈坐成过山车"

            profitPct != null && profitPct >= target ->
                DcaAction.TAKE_PROFIT to "计划已浮盈 ${num(profitPct, 1)}%，达到 ${num(target, 0)}% 止盈线；" +
                    "建议分批落袋，定投可以继续但把单期金额降下来"

            overheat ->
                DcaAction.REDUCE to "较 20 日均线高 ${num(dev20, 1)}%，RSI ${num(rsi14 ?: 0.0, 0)}，" +
                    "短期过热；这一期减半，等回落到均线附近再恢复"

            broken ->
                DcaAction.PAUSE to "指数跌破 60 日均线且趋势偏空；先暂停扣款，" +
                    "等重新站上 60 日线再恢复，避免下跌途中一路加仓"

            // 位置低不等于能买：120 日均线还压着向下时，行业多半仍在下行通道。
            longDown ->
                DcaAction.PAUSE to "价格在 120 日均线下方且均线仍向下（${num(slope120 ?: 0.0, 1)}%），" +
                    "行业还在下行通道；等均线走平再开始或恢复"

            score < POOR_SCORE ->
                DcaAction.PAUSE to "定投评分 ${score} 分（${verdict.label}），长期趋势和位置都不占优；" +
                    "现在不适合开始或继续"

            deepDrawdown ->
                DcaAction.PAUSE to "距近一年高点已回撤 ${num(drawdown, 1)}%，但趋势仍向下；" +
                    "左侧定投容易越买越亏，等企稳"

            simulation?.started == true ->
                DcaAction.CONTINUE to "趋势没破坏、位置也没过热，按 $cadence ${money(plan.amount)} 继续；" +
                    "已投 ${simulation.periods} 期，浮盈 ${num(simulation.profitPct, 1)}%"

            else ->
                DcaAction.START to "评分 ${score} 分（${verdict.label}），现价在近一年 ${pctText} 分位；" +
                    "可以先按 $cadence ${money(plan.amount)} 小额开始"

        }

        val summary = buildString {
            append("收盘 ${num(close, 2)}")
            if (ma20 != null) append(" · 较 MA20 ${num(dev20, 2)}%")
            append(" · ${trend.label}")
        }

        return DcaReport(
            score = score,
            verdict = verdict,
            action = action,
            actionReason = reason,
            factors = listOf(
                DcaFactor("长期趋势", trendScore, 30, trendDetail),
                DcaFactor("位置与回撤", posScore, 25, posDetail),
                DcaFactor("波动水平", volScore, 20, volDetail),
                DcaFactor("短期热度", heatScore, 15, heatDetail),
                DcaFactor("历史长度", histScore, 10, histDetail)
            ),
            trend = trend,
            asOf = asOf,
            summary = summary,
            indexName = indexName,
            close = close,
            percentile = percentile,
            drawdown = drawdown,
            volatility = volatility,
            simulation = simulation,
            signal = signal,
            live = live
        )
    }

    private fun avg(values: List<Double>, period: Int): Double? =
        if (values.size < period) null else values.takeLast(period).average()

    /** RSI(14)，Wilder 平滑，与行情软件同款。 */
    private fun rsi(closes: List<Double>, period: Int = 14): Double? {
        if (closes.size <= period) return null
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val diff = closes[i] - closes[i - 1]
            if (diff >= 0) gain += diff else loss -= diff
        }
        var avgGain = gain / period
        var avgLoss = loss / period
        for (i in period + 1 until closes.size) {
            val diff = closes[i] - closes[i - 1]
            avgGain = (avgGain * (period - 1) + maxOf(diff, 0.0)) / period
            avgLoss = (avgLoss * (period - 1) + maxOf(-diff, 0.0)) / period
        }
        if (avgLoss <= 1e-12) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - 100.0 / (1.0 + rs)
    }

    private fun num(value: Double, digits: Int): String =
        String.format(Locale.US, "%.${digits}f", value)
}
