package com.hukua.fundradar

import com.hukua.fundradar.data.Candle
import com.hukua.fundradar.data.DcaAction
import com.hukua.fundradar.data.DcaEngine
import com.hukua.fundradar.data.DcaPlan
import com.hukua.fundradar.data.DcaVerdict
import com.hukua.fundradar.data.Fund
import com.hukua.fundradar.data.Holding
import com.hukua.fundradar.data.KlineSeries
import com.hukua.fundradar.data.KlineSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 定投引擎的形态测试：评分看趋势与位置，动作看「现在该不该继续扣」。
 *
 * 用固定收盘序列跑，断言用区间而不是精确分数 —— 分数会随着权重微调而变，
 * 但「单边下跌不该开始」「涨到止盈线又转空该停」这些结论必须稳定。
 */
class DcaEngineTest {

    private fun series(closes: List<Double>): KlineSeries {
        val candles = ArrayList<Candle>(closes.size)
        val calendar = Calendar.getInstance().apply { set(2026, 0, 1, 0, 0, 0) }
        var previous = closes.first()
        closes.forEach { close ->
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
                high = maxOf(previous, close) * 1.002,
                low = minOf(previous, close) * 0.998,
                volume = 1000.0
            )
            previous = close
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return KlineSeries("000905", "中证500", KlineSource.EXCHANGE, candles)
    }

    /** 固定在所有测试数据之后，保证最后一根都算「已收盘定型」。 */
    private fun afterAllBars(): Calendar = Calendar.getInstance().apply { set(2027, 0, 1) }

    private fun ramp(from: Double, to: Double, count: Int): List<Double> =
        List(count) { index -> from + (to - from) * index / (count - 1).toDouble() }

    private fun fund(nav: Double = 1.5): Fund = Fund(code = "161725", name = "测试指数基金", nav = nav)

    @Test
    fun steadyUptrendCanStartEvenNearHigh() {
        val report = DcaEngine.evaluate(
            fund = fund(),
            series = series(ramp(100.0, 130.0, 260)),
            now = afterAllBars()
        )
        assertTrue("温和上涨不该是「暂不适合」", report.verdict != DcaVerdict.POOR)
        assertEquals(DcaAction.START, report.action)
        assertTrue(report.score in 45..85)
        assertTrue(report.percentile!! > 0.9)
    }

    @Test
    fun straightDowntrendIsNotStarted() {
        val report = DcaEngine.evaluate(
            fund = fund(),
            series = series(ramp(200.0, 100.0, 260)),
            now = afterAllBars()
        )
        assertEquals("长期均线下行时应暂停", DcaAction.PAUSE, report.action)
        assertTrue(report.actionReason.contains("120 日均线") || report.actionReason.contains("60 日均线"))
        assertTrue(report.percentile!! < 0.1)
    }

    @Test
    fun profitPastTargetInDowntrendStops() {
        val report = DcaEngine.evaluate(
            fund = fund(nav = 15.0),
            series = series(ramp(200.0, 100.0, 260)),
            holding = Holding(shares = 100.0, cost = 1000.0),
            plan = DcaPlan(monthlyAmount = 1000.0, day = 8, startMonth = "2025-01", targetProfitPct = 30.0),
            now = afterAllBars()
        )
        assertEquals(DcaAction.STOP, report.action)
        assertEquals(50.0, report.profitPercent!!, 0.01)
        assertTrue(report.actionReason.contains("止盈"))
    }

    @Test
    fun profitPastTargetButTrendUpTakesProfit() {
        val report = DcaEngine.evaluate(
            fund = fund(nav = 15.0),
            series = series(ramp(100.0, 130.0, 260)),
            holding = Holding(shares = 100.0, cost = 1000.0),
            plan = DcaPlan(monthlyAmount = 1000.0, day = 8, startMonth = "2025-01", targetProfitPct = 30.0),
            now = afterAllBars()
        )
        assertEquals(DcaAction.TAKE_PROFIT, report.action)
        assertEquals(50.0, report.profitPercent!!, 0.01)
    }

    @Test
    fun fastRallyCutsTheNextInstalment() {
        val closes = ramp(100.0, 100.0, 230) + ramp(100.0, 145.0, 30)
        val report = DcaEngine.evaluate(
            fund = fund(),
            series = series(closes),
            now = afterAllBars()
        )
        assertEquals(DcaAction.REDUCE, report.action)
        assertTrue(report.factors.any { it.name == "短期热度" && it.score <= 3 })
    }

    @Test
    fun shortHistoryIsCalledOutInsteadOfGuessed() {
        val report = DcaEngine.evaluate(
            fund = fund(),
            series = series(ramp(100.0, 110.0, 40)),
            now = afterAllBars()
        )
        assertEquals(0, report.score)
        assertTrue(report.actionReason.contains("60"))
        assertTrue(report.note.isNotBlank())
    }

    @Test
    fun planCountsInstalmentsFromStartMonth() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 18, 12, 0, 0) }
        val plan = DcaPlan(monthlyAmount = 1000.0, day = 8, startMonth = "2025-01")
        assertEquals(23, plan.periods(now))
        assertEquals(23_000.0, plan.invested(now), 0.01)
    }

    @Test
    fun planBeforePaydayDoesNotCountThisMonth() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 3, 12, 0, 0) }
        val plan = DcaPlan(monthlyAmount = 500.0, day = 8, startMonth = "2026-11")
        assertEquals(0, plan.periods(now))
        assertEquals(0.0, plan.invested(now), 0.01)
    }

    @Test
    fun planWithoutAmountIsIgnored() {
        val plan = DcaPlan(monthlyAmount = 0.0, startMonth = "2020-01")
        assertTrue(!plan.enabled)
        assertEquals(0, plan.periods())
        assertEquals("未设定投计划", plan.scheduleText())
    }
}
