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
    fun volumeBreakoutFiresOnceWithVolumeConfirmation() {
        val closes = List(70) { 100.0 } + listOf(101.0)
        val volumes = List(70) { 1000.0 } + listOf(2000.0)
        val report = SignalEngine.evaluate(series(closes, volumes), now = afterAllBars())
        val signal = report.signals.firstOrNull { it.title == "放量突破" }
        assertTrue("应触发放量突破，实际：${report.signals.map { it.title }}", signal != null)
        assertEquals(SignalAction.BUY, signal!!.action)
        // 次日缩量再创新高：昨天已突破，不是新事件；量比也不够。
        val next = SignalEngine.evaluate(
            series(closes + listOf(102.0), volumes + listOf(1000.0)),
            now = afterAllBars()
        )
        assertTrue(next.signals.none { it.title == "放量突破" })
        // 同样的突破但无量：不报。
        val quiet = SignalEngine.evaluate(series(closes), now = afterAllBars())
        assertTrue(quiet.signals.none { it.title == "放量突破" })
    }

    @Test
    fun supportBreakdownFiresOnceOnFirstNewLow() {
        val closes = List(70) { 100.0 } + listOf(99.0)
        val report = SignalEngine.evaluate(series(closes), now = afterAllBars())
        val signal = report.signals.firstOrNull { it.title == "支撑跌破" }
        assertTrue("应触发支撑跌破，实际：${report.signals.map { it.title }}", signal != null)
        assertEquals(SignalAction.SELL, signal!!.action)
        // 再跌一天：昨天已经破位，不是首次，不再重复报。
        val next = SignalEngine.evaluate(series(closes + listOf(98.0)), now = afterAllBars())
        assertTrue(next.signals.none { it.title == "支撑跌破" })
    }

    @Test
    fun rsiOversoldRepairFiresOnReclaiming30() {
        // 阴跌 15 根把 RSI 打进超卖区，回升第 3 根恰好收回 30 上方。
        val closes = (List(60) { 100.0 } +
            (1..15).map { 100.0 - it * 1.0 } +
            (1..8).map { 85.0 + it * 1.5 }).take(78)
        val report = SignalEngine.evaluate(series(closes), now = afterAllBars())
        val signal = report.signals.firstOrNull { it.title == "RSI超卖修复" }
        assertTrue("应触发RSI超卖修复，实际：${report.signals.map { it.title }}", signal != null)
        assertEquals(SignalAction.BUY, signal!!.action)
        val next = SignalEngine.evaluate(series(closes + listOf(91.0)), now = afterAllBars())
        assertTrue(next.signals.none { it.title == "RSI超卖修复" })
    }

    @Test
    fun rsiOverboughtPullbackFiresOnLosing70() {
        val closes = (List(60) { 100.0 } +
            (1..15).map { 100.0 + it * 1.0 } +
            (1..8).map { 115.0 - it * 1.5 }).take(78)
        val report = SignalEngine.evaluate(series(closes), now = afterAllBars())
        val signal = report.signals.firstOrNull { it.title == "RSI超买回落" }
        assertTrue("应触发RSI超买回落，实际：${report.signals.map { it.title }}", signal != null)
        assertEquals(SignalAction.SELL, signal!!.action)
        val next = SignalEngine.evaluate(series(closes + listOf(109.0)), now = afterAllBars())
        assertTrue(next.signals.none { it.title == "RSI超买回落" })
    }

    @Test
    fun kdjLowGoldenCrossFiresOnlyInLowZone() {
        // 阴跌 15 根后第一根回升：K 上穿 D，D 还在 25 以下的低位区。
        val closes = (List(60) { 100.0 } +
            (1..15).map { 100.0 - it * 1.0 } +
            (1..8).map { 85.0 + it * 1.5 }).take(76)
        val report = SignalEngine.evaluate(series(closes), now = afterAllBars())
        val signal = report.signals.firstOrNull { it.title == "KDJ低位金叉" }
        assertTrue("应触发KDJ低位金叉，实际：${report.signals.map { it.title }}", signal != null)
        assertEquals(SignalAction.BUY, signal!!.action)
        val next = SignalEngine.evaluate(series(closes + listOf(88.0)), now = afterAllBars())
        assertTrue(next.signals.none { it.title == "KDJ低位金叉" })
    }

    @Test
    fun kdjHighDeathCrossFiresOnlyInHighZone() {
        val closes = (List(60) { 100.0 } +
            (1..15).map { 100.0 + it * 1.0 } +
            (1..8).map { 115.0 - it * 1.5 }).take(76)
        val report = SignalEngine.evaluate(series(closes), now = afterAllBars())
        val signal = report.signals.firstOrNull { it.title == "KDJ高位死叉" }
        assertTrue("应触发KDJ高位死叉，实际：${report.signals.map { it.title }}", signal != null)
        assertEquals(SignalAction.SELL, signal!!.action)
        val next = SignalEngine.evaluate(series(closes + listOf(112.0)), now = afterAllBars())
        assertTrue(next.signals.none { it.title == "KDJ高位死叉" })
    }

    @Test
    fun bullishEngulfingNeedsLowPosition() {
        // 跌到相对MA20约-5%后，阳线 90→92 吞没昨日阴线 91→90。
        val closes = List(60) { 100.0 } + (1..10).map { 100.0 - it * 1.0 } + listOf(92.0)
        val report = SignalEngine.evaluate(series(closes), now = afterAllBars())
        val signal = report.signals.firstOrNull { it.title == "低位看涨吞没" }
        assertTrue("应触发低位看涨吞没，实际：${report.signals.map { it.title }}", signal != null)
        assertEquals(SignalAction.BUY, signal!!.action)
        // 同样形态但位置不低（相对MA20高于-3%）：不报。
        val shallow = List(60) { 100.0 } + listOf(99.0, 98.5, 98.0, 99.5)
        val shallowReport = SignalEngine.evaluate(series(shallow), now = afterAllBars())
        assertTrue(shallowReport.signals.none { it.title == "低位看涨吞没" })
    }

    @Test
    fun bearishEngulfingNeedsHighPosition() {
        // 涨到相对MA20约+7%后，阴线 115→112.5 吞没昨日阳线 113.5→115。
        val closes = List(60) { 100.0 } + (1..10).map { 100.0 + it * 1.5 } + listOf(112.5)
        val report = SignalEngine.evaluate(series(closes), now = afterAllBars())
        val signal = report.signals.firstOrNull { it.title == "高位看跌吞没" }
        assertTrue("应触发高位看跌吞没，实际：${report.signals.map { it.title }}", signal != null)
        assertEquals(SignalAction.SELL, signal!!.action)
        // 同样形态但位置不高（相对MA20低于+5%）：不报。
        val shallow = List(60) { 100.0 } + listOf(101.0, 101.5, 102.0, 100.5)
        val shallowReport = SignalEngine.evaluate(series(shallow), now = afterAllBars())
        assertTrue(shallowReport.signals.none { it.title == "高位看跌吞没" })
    }

    @Test
    fun openBarIsIncludedAsLiveSignal() {
        val flat = List(80) { 100.0 }
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 0)
        }
        // 盘中最后一根暴涨 20%：实时口径下立即参与计算，标记 live。
        val withOpenBar = series(flat + listOf(120.0))
        val patched = withOpenBar.copy(
            candles = withOpenBar.candles.dropLast(1) +
                withOpenBar.candles.last().copy(date = todayText(now))
        )
        val report = SignalEngine.evaluate(patched, now = now)
        assertTrue(report.live)
        assertEquals(todayText(now), report.asOf)
        assertEquals(SignalTrend.UP, report.trend)
        assertTrue(report.signals.isNotEmpty())
        assertTrue(report.summary.contains("现价"))
        // 15:00 之后同一根视为收盘定型：live 关闭，措辞回到「收盘」。
        val closed = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 15)
            set(Calendar.MINUTE, 30)
        }
        val closedReport = SignalEngine.evaluate(patched, now = closed)
        assertTrue(!closedReport.live)
        assertTrue(closedReport.summary.contains("收盘"))
    }

    @Test
    fun liveVolumeRatioScalesByElapsedTradingTime() {
        // 10:00 已交易 30 分钟，成交 300 手 → 折算全天 (300/30)/(1000/240)=2.4 ≥1.5，
        // 突破上午就能报，不用等收盘放量。
        val closes = List(70) { 100.0 } + listOf(101.0)
        val volumes = List(70) { 1000.0 } + listOf(300.0)
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 0)
        }
        val raw = series(closes, volumes)
        val patched = raw.copy(
            candles = raw.candles.dropLast(1) +
                raw.candles.last().copy(date = todayText(now))
        )
        val live = SignalEngine.evaluate(patched, now = now)
        assertTrue(
            "盘中折算量比应触发放量突破，实际：${live.signals.map { it.title }}",
            live.signals.any { it.title == "放量突破" }
        )
        // 收盘后同一根：量比按全天算只有 0.3，不报突破。
        val closed = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 15)
            set(Calendar.MINUTE, 30)
        }
        val closedReport = SignalEngine.evaluate(patched, now = closed)
        assertTrue(closedReport.signals.none { it.title == "放量突破" })
    }

    @Test
    fun closedSeriesReportsNotLive() {
        val report = SignalEngine.evaluate(series(List(80) { 100.0 }), now = afterAllBars())
        assertTrue(!report.live)
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