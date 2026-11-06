package com.hukua.fundradar.data

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

data class KlineSeries(
    val code: String,
    val name: String,
    val source: KlineSource,
    val candles: List<Candle>
) {
    val latest: Candle? get() = candles.lastOrNull()

    fun ma(period: Int): List<Double?> = movingAverage(candles.map { it.close }, period)

    /** Latest MA value per period; null when history is shorter than the period. */
    fun maValues(periods: List<Int> = MA_PERIODS): Map<Int, Double?> =
        periods.associateWith { period -> ma(period).lastOrNull() }

    /** 布林带，默认 (20, 2)。 */
    fun boll(period: Int = 20, multiplier: Double = 2.0): BollingerBands =
        bollingerBands(candles.map { it.close }, period, multiplier)

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
