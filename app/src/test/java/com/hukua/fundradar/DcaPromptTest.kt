package com.hukua.fundradar

import com.hukua.fundradar.data.AiAdvisor
import com.hukua.fundradar.data.Candle
import com.hukua.fundradar.data.DcaEngine
import com.hukua.fundradar.data.DcaFrequency
import com.hukua.fundradar.data.DcaPlan
import com.hukua.fundradar.data.DcaPrompt
import com.hukua.fundradar.data.DcaStance
import com.hukua.fundradar.data.IndexEntry
import com.hukua.fundradar.data.KlineSeries
import com.hukua.fundradar.data.KlineSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 定投提示词与 AI 回复解析。
 *
 * 提示词必须把「计划」和「按计划回放出来的持仓」带进去 —— 少了这两块，模型只能
 * 对着 K 线说空话，「多久投一次、已经投了几期、赚了多少、止盈线在哪」才是定投
 * 决策的依据。
 */
class DcaPromptTest {

    private val entry = IndexEntry("399997", 0, "中证白酒")

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
        return KlineSeries("399997", "中证白酒", KlineSource.EXCHANGE, candles)
    }

    private val plan = DcaPlan(
        amount = 1000.0,
        frequency = DcaFrequency.MONTHLY,
        dayOfMonth = 8,
        startDate = "2026-03-01",
        targetProfitPct = 30.0,
        note = "场外另有一笔 2 万的底仓"
    )

    @Test
    fun promptCarriesPlanAndReplayedPosition() {
        val series = series()
        val report = DcaEngine.evaluate(
            entry = entry,
            series = series,
            plan = plan,
            now = at(2027, 1, 1)
        )
        val text = DcaPrompt.build(
            entry = entry,
            series = series,
            report = report,
            plan = plan,
            now = at(2027, 1, 1)
        ).render()

        assertTrue(text.contains("【定投标的】"))
        assertTrue(text.contains("中证白酒"))
        assertTrue(text.contains("【我的定投计划】"))
        assertTrue(text.contains("每月 8 日"))
        assertTrue(text.contains("止盈线"))
        assertTrue(text.contains("【按计划回放出来的持仓】"))
        assertTrue(text.contains("已投"))
        assertTrue(text.contains("累计投入"))
        assertTrue(text.contains("浮动盈亏"))
        assertTrue("补充说明要原样带上", text.contains("场外另有一笔 2 万的底仓"))
        assertTrue(text.contains("stance"))
        assertTrue(text.contains("CONTINUE"))
    }

    @Test
    fun promptSaysSoWhenNoPlanYet() {
        val series = series()
        val report = DcaEngine.evaluate(entry = entry, series = series, now = at(2027, 1, 1))
        val text = DcaPrompt.build(
            entry = entry,
            series = series,
            report = report,
            plan = DcaPlan(),
            now = at(2027, 1, 1)
        ).render()

        assertTrue(text.contains("尚未设定投计划"))
        assertTrue(text.contains("没有补充"))
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
