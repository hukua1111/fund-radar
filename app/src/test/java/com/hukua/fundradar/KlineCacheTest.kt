package com.hukua.fundradar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hukua.fundradar.data.Candle
import com.hukua.fundradar.data.KlineSeries
import com.hukua.fundradar.data.KlineSource
import com.hukua.fundradar.data.KlineStore
import com.hukua.fundradar.data.mergeCandles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * K 线本地缓存：写进去能读回来、增量只补新日期、条数有上限。
 * 这是「不再每次拉 250 根」的地基，错一次就会画出重复或倒序的蜡烛。
 */
@RunWith(RobolectricTestRunner::class)
class KlineCacheTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun candle(date: String, close: Double) = Candle(
        date = date,
        open = close,
        close = close,
        high = close + 0.1,
        low = close - 0.1,
        volume = 1000.0
    )

    @Test
    fun cacheSurvivesRoundTrip() {
        val store = KlineStore(context)
        val secid = "0.399997"
        store.clear(secid)
        val series = KlineSeries(
            code = "399997",
            name = "中证白酒",
            source = KlineSource.EXCHANGE,
            candles = listOf(candle("2026-11-03", 1.0), candle("2026-11-04", 1.1))
        )

        store.write(secid, series, 1_700_000_000_000L)
        val read = requireNotNull(store.read(secid))
        assertEquals(2, read.series.candles.size)
        assertEquals("中证白酒", read.series.name)
        assertEquals(1.1, read.series.candles[1].close, 1e-9)
        assertEquals(1_700_000_000_000L, read.fetchedAt)
    }

    @Test
    fun emptyCacheReturnsNull() {
        val store = KlineStore(context)
        store.clear("9.999999")
        assertNull(store.read("9.999999"))
    }

    @Test
    fun mergeOnlyCountsNewDates() {
        val old = listOf(candle("2026-11-03", 1.0), candle("2026-11-04", 1.1))
        val fresh = listOf(
            candle("2026-11-04", 1.15), // 已存在，只覆盖
            candle("2026-11-05", 1.2),
            candle("2026-11-06", 1.25)
        )

        val (merged, added) = mergeCandles(old, fresh)
        assertEquals(2, added)
        assertEquals(4, merged.size)
        assertEquals(listOf("2026-11-03", "2026-11-04", "2026-11-05", "2026-11-06"),
            merged.map { it.date })
        // 同一天以新数据为准
        assertEquals(1.15, merged[1].close, 1e-9)
    }

    @Test
    fun mergeIsStableWhenNothingNew() {
        val old = listOf(candle("2026-11-03", 1.0), candle("2026-11-04", 1.1))
        val (merged, added) = mergeCandles(old, listOf(candle("2026-11-04", 1.1)))
        assertEquals(0, added)
        assertEquals(2, merged.size)
        assertTrue(merged.zipWithNext().all { (a, b) -> a.date < b.date })
    }

    @Test
    fun mergeDropsTheOldestBeyondCap() {
        val old = (1..10).map { candle("2026-10-" + it.toString().padStart(2, '0'), it.toDouble()) }
        val fresh = listOf(candle("2026-11-01", 11.0))
        val (merged, added) = mergeCandles(old, fresh, maxBars = 5)
        assertEquals(1, added)
        assertEquals(5, merged.size)
        assertEquals("2026-11-01", merged.last().date)
        assertNotNull(merged.first())
    }

    @Test
    fun emptyFreshKeepsCache() {
        val old = listOf(candle("2026-11-03", 1.0))
        val (merged, added) = mergeCandles(old, emptyList())
        assertEquals(0, added)
        assertEquals(1, merged.size)
    }
}
