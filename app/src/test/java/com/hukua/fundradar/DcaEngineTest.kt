package com.hukua.fundradar

import com.hukua.fundradar.data.Candle
import com.hukua.fundradar.data.DcaAction
import com.hukua.fundradar.data.DcaEngine
import com.hukua.fundradar.data.DcaFrequency
import com.hukua.fundradar.data.DcaPlan
import com.hukua.fundradar.data.DcaSimulator
import com.hukua.fundradar.data.DcaVerdict
import com.hukua.fundradar.data.IndexEntry
import com.hukua.fundradar.data.KlineSeries
import com.hukua.fundradar.data.KlineSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 定投引擎的形态测试：评分看行业趋势与位置，动作看「现在该不该继续扣」，
 * 回放看「按这个节奏真的买，会买到多少期、成本多少」。
 *
 * 用固定收盘序列跑，断言用区间而不是精确分数 —— 分数会随权重微调而变，
 * 但「单边下跌不该开始」「涨到止盈线又转空该停」「按月就是一月一期」这些
 * 结论必须稳定。
 */
class DcaEngineTest {

    private val entry = IndexEntry("399997", 0, "中证白酒")

    private fun dates(count: Int): List<String> {
        val calendar = Calendar.getInstance().apply { set(2026, 0, 1, 0, 0, 0) }
        return List(count) {
            val text = String.format(
                "%04d-%02d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH)
            )
            calendar.add(Calendar.DAY_OF_YEAR, 1)
            text
        }
    }

    private fun series(closes: List<Double>): KlineSeries {
        val days = dates(closes.size)
        var previous = closes.first()
        val candles = closes.mapIndexed { index, close ->
            Candle(
                date = days[index],
                open = previous,
                close = close,
                high = maxOf(previous, close) * 1.002,
                low = minOf(previous, close) * 0.998,
                volume = 1000.0
            ).also { previous = close }
        }
        return KlineSeries("399997", "中证白酒", KlineSource.EXCHANGE, candles)
    }

    /** 固定在所有测试数据之后，保证最后一根都算「已收盘定型」。 */
    private fun afterAllBars(): Calendar = Calendar.getInstance().apply { set(2027, 6, 1) }

    private fun ramp(from: Double, to: Double, count: Int): List<Double> =
        List(count) { index -> from + (to - from) * index / (count - 1).toDouble() }

    private fun monthly(start: String = "2026-01-01", target: Double = 30.0) = DcaPlan(
        amount = 1000.0,
        frequency = DcaFrequency.MONTHLY,
        dayOfMonth = 8,
        startDate = start,
        targetProfitPct = target
    )

    @Test
    fun steadyUptrendCanStartEvenNearHigh() {
        val report = DcaEngine.evaluate(
            entry = entry,
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
            entry = entry,
            series = series(ramp(200.0, 100.0, 260)),
            now = afterAllBars()
        )
        assertEquals("长期均线下行时应暂停", DcaAction.PAUSE, report.action)
        assertTrue(report.actionReason.contains("120 日均线") || report.actionReason.contains("60 日均线"))
        assertTrue(report.percentile!! < 0.1)
    }

    @Test
    fun profitPastTargetInDowntrendStops() {
        // 长期横盘后拉一波，再阴跌：成本压在低位，浮盈仍在，但趋势已经转空。
        val closes = List(100) { 100.0 } + ramp(100.0, 200.0, 100) + ramp(200.0, 150.0, 120)
        val report = DcaEngine.evaluate(
            entry = entry,
            series = series(closes),
            plan = monthly(target = 10.0),
            now = afterAllBars()
        )
        assertEquals(DcaAction.STOP, report.action)
        assertTrue(report.profitPercent!! > 10.0)
        assertTrue(report.actionReason.contains("止盈"))
    }

    @Test
    fun profitPastTargetButTrendUpTakesProfit() {
        val report = DcaEngine.evaluate(
            entry = entry,
            series = series(ramp(100.0, 160.0, 260)),
            plan = monthly(target = 15.0),
            now = afterAllBars()
        )
        assertEquals(DcaAction.TAKE_PROFIT, report.action)
        assertTrue(report.profitPercent!! > 15.0)
    }

    @Test
    fun fastRallyCutsTheNextInstalment() {
        val closes = ramp(100.0, 100.0, 230) + ramp(100.0, 145.0, 30)
        val report = DcaEngine.evaluate(
            entry = entry,
            series = series(closes),
            now = afterAllBars()
        )
        assertEquals(DcaAction.REDUCE, report.action)
        assertTrue(report.factors.any { it.name == "短期热度" && it.score <= 3 })
    }

    @Test
    fun shortHistoryIsCalledOutInsteadOfGuessed() {
        val report = DcaEngine.evaluate(
            entry = entry,
            series = series(ramp(100.0, 110.0, 40)),
            now = afterAllBars()
        )
        assertEquals(0, report.score)
        assertTrue(report.actionReason.contains("60"))
        assertTrue(report.note.isNotBlank())
    }

    // -------------------------------------------------------------- 定投回放

    @Test
    fun dailyPlanBuysEveryTradingDay() {
        // 10 根日线、从第一根起每日投 —— 十期，投入一万。
        val sim = DcaSimulator.run(
            candles = series(ramp(100.0, 110.0, 10)).candles,
            plan = DcaPlan(
                amount = 1000.0,
                frequency = DcaFrequency.DAILY,
                startDate = "2026-01-01"
            ),
            now = afterAllBars()
        )
        assertNotNull(sim)
        assertEquals(10, sim!!.periods)
        assertEquals(10_000.0, sim.invested, 0.01)
    }

    @Test
    fun weeklyPlanBuysEverySevenDays() {
        // 2026-01-01 是周四，选「每周一」：首期顺延到 1 月 5 日，之后每 7 天一期。
        val sim = DcaSimulator.run(
            candles = series(ramp(100.0, 110.0, 30)).candles,
            plan = DcaPlan(
                amount = 500.0,
                frequency = DcaFrequency.WEEKLY,
                dayOfWeek = 1,
                startDate = "2026-01-01"
            ),
            now = afterAllBars()
        )
        assertEquals(4, sim!!.periods)
        assertEquals("2026-01-05", sim.firstBuy)
        assertEquals(2_000.0, sim.invested, 0.01)
    }

    @Test
    fun monthlyPlanFollowsTheDayOfMonth() {
        val sim = DcaSimulator.run(
            candles = series(ramp(100.0, 110.0, 90)).candles,
            plan = monthly(),
            now = afterAllBars()
        )
        assertEquals(3, sim!!.periods)
        assertEquals("2026-01-08", sim.firstBuy)
        assertEquals("2026-03-08", sim.lastBuy)
    }

    @Test
    fun simulationSkipsPeriodsBeforeStartDate() {
        val sim = DcaSimulator.run(
            candles = series(ramp(100.0, 110.0, 90)).candles,
            plan = monthly(start = "2026-02-01"),
            now = afterAllBars()
        )
        assertEquals(2, sim!!.periods)
        assertEquals("2026-02-08", sim.firstBuy)
    }

    @Test
    fun planWithoutAmountIsIgnored() {
        val plan = DcaPlan(amount = 0.0, startDate = "2020-01-01")
        assertTrue(!plan.enabled)
        assertEquals("未设定投计划", plan.scheduleText())
        assertEquals(null, DcaSimulator.run(series(ramp(100.0, 110.0, 30)).candles, plan))
    }

    @Test
    fun yearlyAmountFollowsTheCadence() {
        val daily = DcaPlan(amount = 100.0, frequency = DcaFrequency.DAILY, startDate = "2026-01-01")
        val weekly = DcaPlan(amount = 100.0, frequency = DcaFrequency.WEEKLY, startDate = "2026-01-01")
        val month = DcaPlan(amount = 100.0, frequency = DcaFrequency.MONTHLY, startDate = "2026-01-01")
        assertEquals(24_400.0, daily.yearlyAmount(), 0.01)
        assertEquals(5_200.0, weekly.yearlyAmount(), 0.01)
        assertEquals(1_200.0, month.yearlyAmount(), 0.01)
    }
}
