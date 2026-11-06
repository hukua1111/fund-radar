package com.hukua.fundradar

import com.hukua.fundradar.data.Candle
import com.hukua.fundradar.data.KlineSeries
import com.hukua.fundradar.data.KlineSource
import com.hukua.fundradar.data.bollingerBands
import com.hukua.fundradar.data.movingAverage
import com.hukua.fundradar.data.priceText
import com.hukua.fundradar.data.volumeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KlineTest {

    private fun series(closes: List<Double>): KlineSeries {
        val candles = closes.mapIndexed { index, close ->
            val open = if (index == 0) close else closes[index - 1]
            Candle(
                date = "2026-01-${(index % 28 + 1).toString().padStart(2, '0')}",
                open = open,
                close = close,
                high = maxOf(open, close),
                low = minOf(open, close)
            )
        }
        return KlineSeries("161725", "白酒", KlineSource.NAV, candles)
    }

    @Test
    fun movingAverageIsAlignedWithInput() {
        val values = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        assertEquals(listOf(null, null, 2.0, 3.0, 4.0), movingAverage(values, 3))
    }

    @Test
    fun movingAverageHandlesShortSeries() {
        val values = listOf(1.0, 2.0)
        assertEquals(listOf(null, null), movingAverage(values, 5))
        assertTrue(movingAverage(values, 5).all { it == null })
    }

    @Test
    fun movingAverageMatchesManualCalculation() {
        val values = (1..30).map { it.toDouble() }
        val ma = movingAverage(values, 20)
        // Last 20 values are 11..30, average 20.5
        assertEquals(20.5, ma.last()!!, 1e-9)
        assertNull(ma[18])
        assertEquals((1.0 + 20.0) / 2.0, ma[19]!!, 1e-9)
    }

    @Test
    fun latestMovingAveragesUseFullHistory() {
        val data = series((1..80).map { it.toDouble() })
        val values = data.maValues()
        assertEquals(setOf(5, 10, 20, 60), values.keys)
        assertEquals((76.0 + 80.0) / 2.0, values[5]!!, 1e-9)
        assertEquals((61.0 + 80.0) / 2.0, values[20]!!, 1e-9)
        assertEquals(80.0 - 29.5, values[60]!!, 1e-9)
    }

    @Test
    fun shortHistoryLeavesLongAveragesEmpty() {
        val data = series((1..10).map { it.toDouble() })
        val values = data.maValues()
        assertEquals(null, values[20])
        assertEquals(null, values[60])
        assertEquals(8.0, values[5]!!, 1e-9)
    }

    @Test
    fun changeOverComparesWithEarlierClose() {
        val data = series(listOf(1.0, 1.0, 1.0, 1.0, 1.0, 1.1))
        assertEquals(10.0, data.changeOver(5)!!, 1e-9)
        assertNull(data.changeOver(50))
    }

    @Test
    fun candleBodyFollowsPreviousClose() {
        val data = series(listOf(1.0, 1.2, 1.1))
        val second = data.candles[1]
        assertEquals(1.0, second.open, 1e-9)
        assertEquals(1.2, second.close, 1e-9)
        assertTrue(second.isUp)
        assertEquals(false, data.candles[2].isUp)
    }

    @Test
    fun priceTextKeepsEnoughDecimals() {
        assertEquals("0.5926", priceText(0.5926))
        assertEquals("128.40", priceText(128.4))
    }

    @Test
    fun bollingerMiddleIsTheMovingAverage() {
        val values = (1..40).map { it.toDouble() }
        val band = bollingerBands(values, 20, 2.0)
        assertEquals(values.size, band.middle.size)
        assertEquals(movingAverage(values, 20), band.middle)
        assertNull(band.upper[18])
        assertNull(band.lower[18])
        assertTrue(band.upper.drop(19).all { it != null })
    }

    @Test
    fun bollingerMatchesManualStandardDeviation() {
        val values = (1..20).map { it.toDouble() }
        val band = bollingerBands(values, 20, 2.0)
        val mean = 10.5
        val deviation = kotlin.math.sqrt(values.sumOf { (it - mean) * (it - mean) } / 20.0)
        assertEquals(mean, band.middle.last()!!, 1e-9)
        assertEquals(mean + 2 * deviation, band.upper.last()!!, 1e-9)
        assertEquals(mean - 2 * deviation, band.lower.last()!!, 1e-9)
        assertTrue(band.upper.last()!! > band.middle.last()!!)
        assertTrue(band.lower.last()!! < band.middle.last()!!)
    }

    @Test
    fun bollingerCollapsesOnFlatSeries() {
        val values = List(30) { 3.5 }
        val band = bollingerBands(values, 20, 2.0)
        assertEquals(3.5, band.middle.last()!!, 1e-9)
        assertEquals(3.5, band.upper.last()!!, 1e-9)
        assertEquals(3.5, band.lower.last()!!, 1e-9)
    }

    @Test
    fun bollingerRespectsMultiplier() {
        val values = (1..20).map { it.toDouble() }
        val narrow = bollingerBands(values, 20, 1.0)
        val wide = bollingerBands(values, 20, 2.0)
        val narrowWidth = narrow.upper.last()!! - narrow.lower.last()!!
        val wideWidth = wide.upper.last()!! - wide.lower.last()!!
        assertEquals(narrowWidth * 2, wideWidth, 1e-9)
    }

    @Test
    fun seriesBollUsesClosingPrices() {
        val data = series((1..30).map { it.toDouble() })
        val band = data.boll()
        assertEquals(20, band.period)
        assertEquals(2.0, band.multiplier, 1e-9)
        assertEquals((11.0 + 30.0) / 2.0, band.middle.last()!!, 1e-9)
    }

    @Test
    fun volumeTextScalesByMagnitude() {
        assertEquals("—", volumeText(0.0))
        assertEquals("860手", volumeText(860.0))
        assertEquals("12.3万手", volumeText(123_000.0))
        assertEquals("1.20亿手", volumeText(120_000_000.0))
    }
}
