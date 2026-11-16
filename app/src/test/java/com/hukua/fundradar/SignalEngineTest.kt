package com.hukua.fundradar

import com.hukua.fundradar.data.Candle
import com.hukua.fundradar.data.KlineSeries
import com.hukua.fundradar.data.KlineSource
import com.hukua.fundradar.data.SignalAction
import com.hukua.fundradar.data.SignalEngine
import com.hukua.fundradar.data.SignalTrend
import com.hukua.fundradar.data.todayText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 信号引擎的形态测试：每组收盘序列都经过本地运行验证，
 * 目标信号恰好在最后一根触发（事件触发，不是状态常驻）。
 */
class SignalEngineTest {

    private fun series(closes: List<Double>, volumes: List<Double>? = null): KlineSeries {
        val candles = ArrayList<Candle>()
        var previous = closes.first()
        val calendar = Calendar.getInstance().apply { set(2026, 0, 1) }
        closes.forEachIndexed { index, close ->
            val date = String.format(
                "%04d-%02d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH)
            )
            candles += Candle(
                date = date,
                open = previous,
                close = close,
                high = maxOf(previous, close),
                low = minOf(previous, close),
                volume = volumes?.get(index) ?: 1000.0
            )
            previous = close
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return KlineSeries("000001", "测试指数", KlineSource.EXCHANGE, candles)
    }

    /** 固定在所有测试数据之后，保证最后一根都是「已收盘定型」的。 */
    private fun afterAllBars(): Calendar =
        Calendar.getInstance().apply { set(2027, 0, 1) }

    @Test
    fun flatSeriesHasNoSignalAndIsRange() {
        val report = SignalEngine.evaluate(series(List(80) { 100.0 }), now = afterAllBars())
        assertEquals(SignalTrend.RANGE, report.trend)
        assertTrue(report.signals.isEmpty())
        assertTrue(report.summary.contains("震荡"))
    }

    @Test
    fun shortHistoryReportsUnknown() {
        val report = SignalEngine.evaluate(series(List(20) { 100.0 }), now = afterAllBars())
        assertEquals(SignalTrend.UNKNOWN, report.trend)
        assertTrue(report.signals.isEmpty())
        assertTrue(report.note.isNotBlank())
    }

    @Test
    fun trendStartFiresOnFirstFullBullishBar() {
        val closes = List(50) { 100.0 } +
            (1..15).map { 100.0 - it * 0.6 } +
            listOf(91.2, 91.6, 92.0, 92.6, 93.2, 93.9, 94.7)
        val report = SignalEngine.evaluate(series(closes), now = afterAllBars())
        assertEquals(SignalTrend.UP, report.trend)
        val signal = report.signals.firstOrNull { it.title == "趋势启动" }
        assertTrue("应触发趋势启动，实际：${report.signals.map { it.title }}", signal != null)
        assertEquals(SignalAction.BUY, signal!!.action)
        assertTrue(signal.reason.contains("失效"))
    }

    @Test
    fun trendStartDoesNotRepeatWhileStateHolds() {
        // 趋势启动后再涨一天：状态还在，但事件已经发生过，不再重复报。
        val closes = List(50) { 100.0 } +
            (1..15).map { 100.0 - it * 0.6 } +
            listOf(91.2, 91.6, 92.0, 92.6, 93.2, 93.9, 94.7, 96.0)
        val report = SignalEngine.evaluate(series(closes), now = afterAllBars())
        assertTrue(report.signals.none { it.title == "趋势启动" })
    }

    @Test
    fun trendBreakFiresOnFirstFullBearishBar() {
        val closes = List(50) { 100.0 } +
            (1..15).map { 100.0 + it * 0.6 } +
            listOf(108.8, 108.4, 108.0, 107.4, 106.8, 106.1, 105.3)
        val report = SignalEngine.evaluate(series(closes), now = afterAllBars())
        assertEquals(SignalTrend.DOWN, report.trend)
        val signal = report.signals.firstOrNull { it.title == "趋势破坏" }
        assertTrue("应触发趋势破坏，实际：${report.signals.map { it.title }}", signal != null)
        assertEquals(SignalAction.SELL, signal!!.action)
    }

    @Test
    fun oversoldRepairFiresAfterReclaimingLowerBand() {
        val closes = List(60) { 100.0 } + listOf(94.0, 92.0, 91.0, 93.5, 94.2)
        val report = SignalEngine.evaluate(series(closes), now = afterAllBars())
        val signal = report.signals.firstOrNull { it.title == "超卖修复" }
        assertTrue("应触发超卖修复，实际：${report.signals.map { it.title }}", signal != null)
        assertEquals(SignalAction.BUY, signal!!.action)
    }

    @Test
    fun strongPullbackFiresOnlyWithRelativeStrength() {
        val closes = List(60) { 100.0 } +
            (1..10).map { 100.0 + it * 0.5 } +
            listOf(103.0, 101.5, 100.8, 101.6, 102.4)
        val withStrength = SignalEngine.evaluate(series(closes), relative20 = 3.0, now = afterAllBars())
        assertTrue(withStrength.signals.any { it.title == "强势回踩企稳" })
        val withoutStrength = SignalEngine.evaluate(series(closes), relative20 = null, now = afterAllBars())
        assertTrue(withoutStrength.signals.none { it.title == "强势回踩企稳" })
        val weak = SignalEngine.evaluate(series(closes), relative20 = -1.0, now = afterAllBars())
        assertTrue(weak.signals.none { it.title == "强势回踩企稳" })
    }

    @Test
    fun overheatPullbackNeedsHeavyVolume() {
        val closes = List(60) { 100.0 } + (1..10).map { 100.0 + it * 2.5 } + listOf(121.0)
        val heavy = List(70) { 1000.0 } + listOf(3000.0)
        val report = SignalEngine.evaluate(series(closes, heavy), now = afterAllBars())
        val signal = report.signals.firstOrNull { it.title == "过热回落" }
        assertTrue("应触发过热回落，实际：${report.signals.map { it.title }}", signal != null)
        assertEquals(SignalAction.SELL, signal!!.action)
        // 同样的形态但缩量：不报过热回落。
        val quiet = SignalEngine.evaluate(series(closes), now = afterAllBars())
        assertTrue(quiet.signals.none { it.title == "过热回落" })
    }

    @Test
    fun drawdownProtectionFiresOnceWhenThresholdCrossed() {
        val closes = List(40) { 100.0 } +
            (1..10).map { 100.0 + it * 2.0 } +
            listOf(117.0, 114.5, 112.0, 110.0, 108.5, 107.0)
        val report = SignalEngine.evaluate(series(closes), now = afterAllBars())
        val signal = report.signals.firstOrNull { it.title == "回撤保护" }
        assertTrue("应触发回撤保护，实际：${report.signals.map { it.title }}", signal != null)
        assertEquals(SignalAction.SELL, signal!!.action)
        // 再跌一天：阈值早已越过，不是「首次」，不再重复报。
        val next = SignalEngine.evaluate(series(closes + listOf(105.0)), now = afterAllBars())
        assertTrue(next.signals.none { it.title == "回撤保护" })
    }

    @Test
    fun macdGoldenCrossFiresOnceOnCrossDay() {
        // 趋势启动夹具的前 70 根：金叉恰好发生在最后一根（93.2）。
        val closes = (List(50) { 100.0 } +
            (1..15).map { 100.0 - it * 0.6 } +
            listOf(91.2, 91.6, 92.0, 92.6, 93.2, 93.9, 94.7)).take(70)
        val report = SignalEngine.evaluate(series(closes), now = afterAllBars())
        val signal = report.signals.firstOrNull { it.title == "MACD金叉" }
        assertTrue("应触发MACD金叉，实际：${report.signals.map { it.title }}", signal != null)
        assertEquals(SignalAction.BUY, signal!!.action)
        assertTrue(signal.reason.contains("零轴下方"))
        // 再涨一天：DIF 仍在 DEA 上方，不是新交叉，不再重复报。
        val next = SignalEngine.evaluate(series(closes + listOf(93.9)), now = afterAllBars())
        assertTrue(next.signals.none { it.title == "MACD金叉" })
    }

    @Test
    fun macdDeathCrossFiresOnceOnCrossDay() {
        // 趋势破坏夹具的前 70 根：死叉恰好发生在最后一根（106.8）。
        val closes = (List(50) { 100.0 } +
            (1..15).map { 100.0 + it * 0.6 } +
            listOf(108.8, 108.4, 108.0, 107.4, 106.8, 106.1, 105.3)).take(70)
        val report = SignalEngine.evaluate(series(closes), now = afterAllBars())
        val signal = report.signals.firstOrNull { it.title == "MACD死叉" }
        assertTrue("应触发MACD死叉，实际：${report.signals.map { it.title }}", signal != null)
        assertEquals(SignalAction.SELL, signal!!.action)
        assertTrue(signal.reason.contains("零轴上方"))
        val next = SignalEngine.evaluate(series(closes + listOf(106.1)), now = afterAllBars())
        assertTrue(next.signals.none { it.title == "MACD死叉" })
    }

    @Test
    fun macdBottomDivergenceFiresOnConfirmingCross() {
        // 快跌到 92 → 反弹 → 慢跌到更低的 89.6（DIF 低点抬高）→ 急涨，第 89 根金叉确认。
        val closes = List(60) { 100.0 } +
            (1..8).map { 100.0 - it * 1.0 } +
            (1..6).map { 92.0 + it * 0.8 } +
            (1..12).map { 96.8 - it * 0.6 } +
            listOf(91.1, 92.6, 94.1)
        val report = SignalEngine.evaluate(series(closes), now = afterAllBars())
        val signal = report.signals.firstOrNull { it.title == "MACD底背离" }
        assertTrue("应触发MACD底背离，实际：${report.signals.map { it.title }}", signal != null)
        assertEquals(SignalAction.BUY, signal!!.action)
        assertTrue(signal.reason.contains("失效"))
        // 确认日之后再涨一天：事件已发生，不再重复报。
        val next = SignalEngine.evaluate(series(closes + listOf(95.6)), now = afterAllBars())
        assertTrue(next.signals.none { it.title == "MACD底背离" })
    }

    @Test
    fun macdTopDivergenceFiresOnConfirmingCross() {
        // 快涨到 108 → 回落 → 慢涨到更高的 110.4（DIF 高点降低）→ 急跌，第 89 根死叉确认。
        val closes = List(60) { 100.0 } +
            (1..8).map { 100.0 + it * 1.0 } +
            (1..6).map { 108.0 - it * 0.8 } +
            (1..12).map { 103.2 + it * 0.6 } +
            listOf(108.9, 107.4, 105.9)
        val report = SignalEngine.evaluate(series(closes), now = afterAllBars())
        val signal = report.signals.firstOrNull { it.title == "MACD顶背离" }
        assertTrue("应触发MACD顶背离，实际：${report.signals.map { it.title }}", signal != null)
        assertEquals(SignalAction.SELL, signal!!.action)
        assertTrue(signal.reason.contains("失效"))
        val next = SignalEngine.evaluate(series(closes + listOf(104.4)), now = afterAllBars())
        assertTrue(next.signals.none { it.title == "MACD顶背离" })
    }

    @Test
    fun openBarIsExcludedFromSignals() {
        val flat = List(80) { 100.0 }
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 0)
        }
        // 盘中最后一根暴涨 20%：未定型，不能据此报信号。
        val withOpenBar = series(flat + listOf(120.0))
        val patched = withOpenBar.copy(
            candles = withOpenBar.candles.dropLast(1) +
                withOpenBar.candles.last().copy(date = todayText(now))
        )
        val report = SignalEngine.evaluate(patched, now = now)
        assertEquals(SignalTrend.RANGE, report.trend)
        assertTrue(report.signals.isEmpty())
    }

    @Test
    fun everySignalCarriesReasonAndInvalidation() {
        val closes = List(40) { 100.0 } +
            (1..10).map { 100.0 + it * 2.0 } +
            listOf(117.0, 114.5, 112.0, 110.0, 108.5, 107.0)
        val report = SignalEngine.evaluate(series(closes), now = afterAllBars())
        assertTrue(report.signals.isNotEmpty())
        report.signals.forEach { signal ->
            assertTrue(signal.reason.isNotBlank())
            assertTrue(signal.title.isNotBlank())
        }
    }
}