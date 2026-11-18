package com.hukua.fundradar

import com.hukua.fundradar.data.AiAdvisor
import com.hukua.fundradar.data.Candle
import com.hukua.fundradar.data.DcaEngine
import com.hukua.fundradar.data.DcaPlan
import com.hukua.fundradar.data.DcaPrompt
import com.hukua.fundradar.data.DcaStance
import com.hukua.fundradar.data.Fund
import com.hukua.fundradar.data.Holding
import com.hukua.fundradar.data.KlineSeries
import com.hukua.fundradar.data.KlineSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 定投提示词与 AI 回复解析。
 *
 * 提示词必须把「计划」和「我的成本」带进去 —— 少了这两块，模型只能对着 K 线说
 * 空话，「已经投了几期、赚了多少、止盈线在哪」这些才是定投决策的依据。
 */
class DcaPromptTest {

    private fun at(year: Int, month: Int, day: Int): Calendar =
        Calendar.getInstance().apply {
            set(year, month - 1, day, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }

    private fun series(): KlineSeries {
        val calendar = at(2026, 1, 1)
        val candles = ArrayList<Candle>(240)
        repeat(240) { index ->
            val close = 100.0 + index * 0.2 + if (index % 7 == 0) 1.5 else 0.0
            val date = String.format(
                "%04d-%02d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH)
            )
            candles += Candle(
                date = date,
                open = close - 0.4,
                close = close,
                high = close + 0.6,
                low = close - 0.8,
                volume = 1200.0
            )
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return KlineSeries("000905", "中证500", KlineSource.EXCHANGE, candles)
    }

    private val fund = Fund(
        code = "161725",
        name = "招商中证白酒",
        type = "指数型",
        nav = 1.2345,
        navDate = "2026-09-01",
        riskLevel = 4,
        trackIndex = "中证白酒指数"
    )

    private val plan = DcaPlan(
        monthlyAmount = 1000.0,
        day = 8,
        startMonth = "2025-03",
        targetProfitPct = 30.0
    )

    private val holding = Holding(shares = 8000.0, cost = 9000.0)

    @Test
    fun promptCarriesPlanAndCost() {
        val series = series()
        val report = DcaEngine.evaluate(
            fund = fund,
            series = series,
            holding = holding,
            plan = plan,
            now = at(2027, 1, 1)
        )
        val text = DcaPrompt.build(
            fund = fund,
            series = series,
            report = report,
            holding = holding,
            plan = plan,
            now = at(2027, 1, 1)
        ).render()

        assertTrue(text.contains("【我的定投计划】"))
        assertTrue(text.contains("已投"))
        assertTrue(text.contains("累计投入"))
        assertTrue(text.contains("【我的持仓】"))
        assertTrue(text.contains("成本单价"))
        assertTrue(text.contains("浮动盈亏"))
        assertTrue(text.contains("stance"))
        assertTrue(text.contains("CONTINUE"))
        assertTrue(text.contains("止盈线"))
    }

    @Test
    fun promptSaysSoWhenNothingIsFilled() {
        val series = series()
        val report = DcaEngine.evaluate(fund = fund, series = series, now = at(2027, 1, 1))
        val text = DcaPrompt.build(
            fund = fund,
            series = series,
            report = report,
            holding = null,
            plan = DcaPlan(),
            now = at(2027, 1, 1)
        ).render()

        assertTrue(text.contains("还没填持仓"))
        assertTrue(text.contains("尚未设定投计划"))
    }

    @Test
    fun parsesStanceFromFencedJson() {
        val content = """
            ```json
            {"stance":"PAUSE","confidence":66,"reason":"跌破 60 日均线","risk":"继续下行","next":"先停扣"}
            ```
        """.trimIndent()
        val advice = AiAdvisor.parseDcaAdvice(content, "prompt")
        assertEquals(DcaStance.PAUSE, advice.stance)
        assertEquals(66, advice.confidence)
        assertEquals("跌破 60 日均线", advice.reason)
        assertEquals("先停扣", advice.next)
        assertEquals("prompt", advice.prompt)
        assertTrue(advice.parsed)
    }

    @Test
    fun unparsableReplyStaysUnknown() {
        val advice = AiAdvisor.parseDcaAdvice("模型今天不想说话")
        assertEquals(DcaStance.UNKNOWN, advice.stance)
        assertEquals(0, advice.confidence)
        assertTrue(!advice.parsed)
    }
}
