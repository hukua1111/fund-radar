package com.hukua.fundradar.data

import java.util.Locale

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

/** Compact price text: 4 decimals for NAV-style values, 3 for exchange prices. */
fun priceText(value: Double): String =
    if (value >= 100) String.format(Locale.US, "%.2f", value)
    else String.format(Locale.US, "%.4f", value)
