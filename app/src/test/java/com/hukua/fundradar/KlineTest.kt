package com.hukua.fundradar

import com.hukua.fundradar.data.Candle
import com.hukua.fundradar.data.KlineSeries
import com.hukua.fundradar.data.KlineSource
import com.hukua.fundradar.data.movingAverage
import com.hukua.fundradar.data.priceText
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
}
