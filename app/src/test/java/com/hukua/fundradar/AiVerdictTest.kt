package com.hukua.fundradar

import com.hukua.fundradar.data.AiAdvisor
import com.hukua.fundradar.data.SwingAction
import org.junit.Assert.assertEquals
import org.junit.Test

class AiVerdictTest {

    @Test
    fun parsesPlainJson() {
        val verdict = AiAdvisor.parseVerdict(
            """{"action":"BUY","confidence":72,"reason":"MA5 上穿 MA20","risk":"注意回撤"}"""
        )
        assertEquals(SwingAction.BUY, verdict.action)
        assertEquals(72, verdict.confidence)
        assertEquals("MA5 上穿 MA20", verdict.reason)
        assertEquals("注意回撤", verdict.risk)
    }

    @Test
    fun parsesFencedJsonWithProse() {
        val raw = """
            好的，分析如下：
            ```json
            {"action":"SELL","confidence":65,"reason":"跌破MA20","risk":"反弹风险"}
            ```
            以上仅供参考。
        """.trimIndent()
        val verdict = AiAdvisor.parseVerdict(raw)
        assertEquals(SwingAction.SELL, verdict.action)
        assertEquals(65, verdict.confidence)
        assertEquals("跌破MA20", verdict.reason)
    }

    @Test
    fun acceptsChineseActionWords() {
        assertEquals(SwingAction.BUY, AiAdvisor.parseVerdict("""{"action":"买入"}""").action)
        assertEquals(SwingAction.SELL, AiAdvisor.parseVerdict("""{"action":"卖出"}""").action)
        assertEquals(SwingAction.HOLD, AiAdvisor.parseVerdict("""{"action":"观望"}""").action)
    }

    @Test
    fun clampsConfidence() {
        assertEquals(100, AiAdvisor.parseVerdict("""{"action":"BUY","confidence":180}""").confidence)
        assertEquals(0, AiAdvisor.parseVerdict("""{"action":"BUY","confidence":-5}""").confidence)
    }

    @Test
    fun fallsBackToRawTextWhenNotJson() {
        val verdict = AiAdvisor.parseVerdict("现在不适合买入，建议继续观望")
        assertEquals(SwingAction.UNKNOWN, verdict.action)
        assertEquals("现在不适合买入，建议继续观望", verdict.reason)
    }

    @Test
    fun actionLabelsAreChinese() {
        assertEquals("波段买入", SwingAction.BUY.label)
        assertEquals("波段卖出", SwingAction.SELL.label)
        assertEquals("观望", SwingAction.HOLD.label)
    }
}
