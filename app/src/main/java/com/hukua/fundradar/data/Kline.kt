package com.hukua.fundradar.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/** Where the candles came from. */
enum class KlineSource {
    /** Real exchange OHLC (场内 LOF / ETF). */
    EXCHANGE,

    /** Built from the daily NAV series of an off-exchange fund. */
    NAV
}

data class Candle(
    val date: String,
    val open: Double,
    val close: Double,
    val high: Double,
    val low: Double,
    val volume: Double = 0.0
) {
    val isUp: Boolean get() = close >= open

    val changePct: Double
        get() = if (open > 0) (close - open) / open * 100.0 else 0.0
}

/**
 * 布林带：中轨是 MA(period)，上下轨是中轨 ± multiplier 倍标准差。
 * 三条线都与输入等长，历史不足时对应位置是 null。
 */
data class BollingerBands(
    val period: Int = 20,
    val multiplier: Double = 2.0,
    val middle: List<Double?> = emptyList(),
    val upper: List<Double?> = emptyList(),
    val lower: List<Double?> = emptyList()
)

/**
 * MACD(12, 26, 9)：DIF 是快慢 EMA 差，DEA 是 DIF 的 EMA，柱状线是
 * 2 ×（DIF − DEA），与国内行情软件的画法一致。
 */
data class Macd(
    val fast: Int = 12,
    val slow: Int = 26,
    val signal: Int = 9,
    val dif: List<Double?> = emptyList(),
    val dea: List<Double?> = emptyList(),
    val hist: List<Double?> = emptyList()
) {
    val latestDif: Double? get() = dif.lastOrNull()
    val latestDea: Double? get() = dea.lastOrNull()
    val latestHist: Double? get() = hist.lastOrNull()

    /** 前一根柱子，用来判断今天是「红柱放大」还是「缩短」。 */
    val previousHist: Double? get() = if (hist.size >= 2) hist[hist.size - 2] else null
}

/**
 * 这条 K 线附带的指数元信息。目前只有中证官网会给，东财/新浪留空。
 *
 * [pe] 是官网字段 `peg` 的原值——名字叫 peg，实际是滚动市盈率（沪深300 约
 * 13、光伏约 33，与各家行情软件的 PE-TTM 对得上），这里按 PE 用。
 */
data class KlineMeta(
    val pe: Double? = null,
    /** 成分股数量。 */
    val constituents: Int? = null,
    /** 历史段来自哪家，界面上直接显示。 */
    val historySource: String = "",
    /** 当日那根来自哪家。 */
    val tailSource: String = "",
    /** 上游最新一根的日期，可能比今天早（官网是 T-1）。 */
    val asOf: String = ""
) {
    val isEmpty: Boolean
        get() = pe == null && constituents == null && historySource.isBlank()

    /** 「中证指数官网 + 东方财富补当日」这样的一行说明。 */
    val label: String
        get() = when {
            historySource.isBlank() && tailSource.isBlank() -> ""
            historySource.isBlank() -> tailSource
            tailSource.isBlank() -> historySource
            historySource == tailSource -> historySource
            else -> "$historySource + $tailSource"
        }
}

data class KlineSeries(
    val code: String,
    val name: String,
    val source: KlineSource,
    val candles: List<Candle>,
    val meta: KlineMeta = KlineMeta()
) {
    val latest: Candle? get() = candles.lastOrNull()

    /**
     * 最后一根是不是「今天还没收盘」的那根。
     *
     * 盘中每一跳都会改这根 K 线的收盘价，用它算出来的 MA/MACD/BOLL 会跟着抖，
     * AI 判断就会在同一天里来回翻。判断技术面时要把这根剔掉，只用定型的日线。
     */
    fun hasOpenBar(now: Calendar = Calendar.getInstance()): Boolean {
        val last = candles.lastOrNull() ?: return false
        // 按传入的时刻推「今天」，不在内部再取一次系统时间，否则测试和调用方
        // 会各算各的日期。
        if (last.date != todayText(now)) return false
        val minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return minutes < 15 * 60
    }

    /** 剔掉盘中未收盘那根之后的序列；已收盘就原样返回。 */
    fun completed(now: Calendar = Calendar.getInstance()): KlineSeries =
        if (hasOpenBar(now)) copy(candles = candles.dropLast(1)) else this

    /** 最近 [days] 根的平均成交量（不含最后一根，避免当日半天量拉低基准）。 */
    fun averageVolume(days: Int = 5): Double? {
        if (candles.size < days + 1) return null
        val window = candles.subList(candles.size - 1 - days, candles.size - 1)
        val sum = window.sumOf { it.volume }
        return if (sum <= 0.0) null else sum / days
    }

    /** 最后一根的成交量相对前 [days] 日均量的倍数。 */
    fun volumeRatio(days: Int = 5): Double? {
        val average = averageVolume(days) ?: return null
        val last = latest?.volume ?: return null
        if (average <= 0.0 || last <= 0.0) return null
        return last / average
    }

    fun ma(period: Int): List<Double?> = movingAverage(candles.map { it.close }, period)

    /** Latest MA value per period; null when history is shorter than the period. */
    fun maValues(periods: List<Int> = MA_PERIODS): Map<Int, Double?> =
        periods.associateWith { period -> ma(period).lastOrNull() }

    /** 布林带，默认 (20, 2)。 */
    fun boll(period: Int = 20, multiplier: Double = 2.0): BollingerBands =
        bollingerBands(candles.map { it.close }, period, multiplier)

    /** MACD，默认 (12, 26, 9)。 */
    fun macd(fast: Int = 12, slow: Int = 26, signal: Int = 9): Macd =
        macd(candles.map { it.close }, fast, slow, signal)

    /** Percent change over the last [days] trading days. */
    fun changeOver(days: Int): Double? {
        if (candles.size <= days) return null
        val from = candles[candles.size - 1 - days].close
        val to = candles.last().close
        return if (from > 0) (to - from) / from * 100.0 else null
    }

    companion object {
        val MA_PERIODS = listOf(5, 10, 20, 60)
    }
}

/**
 * Simple moving average aligned with [values]: index i holds the average of the
 * last [period] values ending at i, or null while there is not enough history.
 */
fun movingAverage(values: List<Double>, period: Int): List<Double?> {
    if (period <= 0) return values.map { null }
    var sum = 0.0
    return values.mapIndexed { index, value ->
        sum += value
        if (index >= period) sum -= values[index - period]
        if (index >= period - 1) sum / period else null
    }
}

/**
 * 布林带用滑动窗口一次算完：均值和平方均值同步累加，避免每个点重扫一遍
 * 窗口。方差取 `E(x²) - E(x)²`，并夹到 0 以上抵消浮点误差。
 */
fun bollingerBands(
    values: List<Double>,
    period: Int = 20,
    multiplier: Double = 2.0
): BollingerBands {
    if (period <= 0) {
        val empty = values.map { null }
        return BollingerBands(period, multiplier, empty, empty, empty)
    }

    val middle = ArrayList<Double?>(values.size)
    val upper = ArrayList<Double?>(values.size)
    val lower = ArrayList<Double?>(values.size)
    var sum = 0.0
    var sumSquares = 0.0

    values.forEachIndexed { index, value ->
        sum += value
        sumSquares += value * value
        if (index >= period) {
            val dropping = values[index - period]
            sum -= dropping
            sumSquares -= dropping * dropping
        }
        if (index >= period - 1) {
            val mean = sum / period
            val variance = (sumSquares / period - mean * mean).coerceAtLeast(0.0)
            val deviation = sqrt(variance) * multiplier
            middle += mean
            upper += mean + deviation
            lower += mean - deviation
        } else {
            middle += null
            upper += null
            lower += null
        }
    }
    return BollingerBands(period, multiplier, middle, upper, lower)
}

/**
 * MACD：先算 EMA(快) 与 EMA(慢)，DIF = 快 − 慢，DEA = DIF 的 EMA，
 * 柱 = 2 ×（DIF − DEA）。
 *
 * EMA 以第一个收盘价作种子逐个递推，所以序列头部会有「历史不足」的空档：
 * DIF 从第 slow 根起有效，DEA 再晚 signal − 1 根。空档给 null，画图时跳过。
 */
fun macd(
    values: List<Double>,
    fast: Int = 12,
    slow: Int = 26,
    signal: Int = 9
): Macd {
    if (values.isEmpty()) return Macd(fast, slow, signal)
    val fastEma = exponentialMovingAverage(values, fast)
    val slowEma = exponentialMovingAverage(values, slow)

    val rawDif = values.indices.map { fastEma[it] - slowEma[it] }
    val rawDea = exponentialMovingAverage(rawDif, signal)

    val dif = ArrayList<Double?>(values.size)
    val dea = ArrayList<Double?>(values.size)
    val hist = ArrayList<Double?>(values.size)
    values.indices.forEach { index ->
        val d = if (index >= slow - 1) rawDif[index] else null
        val e = if (index >= slow + signal - 2) rawDea[index] else null
        dif += d
        dea += e
        hist += if (d != null && e != null) (d - e) * 2 else null
    }
    return Macd(fast, slow, signal, dif, dea, hist)
}

/** 逐点 EMA：种子取第一个值，随后按 2 / (n + 1) 平滑。 */
private fun exponentialMovingAverage(values: List<Double>, period: Int): List<Double> {
    if (values.isEmpty()) return emptyList()
    val alpha = 2.0 / (period + 1)
    val result = ArrayList<Double>(values.size)
    var previous = values.first()
    values.forEach { value ->
        previous = if (result.isEmpty()) value else alpha * value + (1 - alpha) * previous
        result += previous
    }
    return result
}

/** 本地日期 `yyyy-MM-dd`，与上游返回的日期格式一致。 */
fun todayText(calendar: Calendar = Calendar.getInstance()): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)

/** Compact price text: 4 decimals for NAV-style values, 3 for exchange prices. */
fun priceText(value: Double): String =
    if (value >= 100) String.format(Locale.US, "%.2f", value)
    else String.format(Locale.US, "%.4f", value)

/** 成交量文本。上游给的是「手」，量级跨度很大，统一压成三位以内。 */
fun volumeText(value: Double): String = when {
    value <= 0.0 -> "—"
    value >= 1e8 -> String.format(Locale.US, "%.2f亿手", value / 1e8)
    value >= 1e4 -> String.format(Locale.US, "%.1f万手", value / 1e4)
    else -> String.format(Locale.US, "%.0f手", value)
}
