package com.hukua.fundradar

import com.hukua.fundradar.data.AccountLine
import com.hukua.fundradar.data.AiContext
import com.hukua.fundradar.data.AiPrompt
import com.hukua.fundradar.data.AiVerdict
import com.hukua.fundradar.data.Candle
import com.hukua.fundradar.data.KlineMeta
import com.hukua.fundradar.data.KlineSeries
import com.hukua.fundradar.data.KlineSource
import com.hukua.fundradar.data.PulseRow
import com.hukua.fundradar.data.SwingAction
import com.hukua.fundradar.data.Trend
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.sin

/**
 * 提示词拼装。
 *
 * 这里最重要的不是断言，而是 `build/ai-prompt-sample.txt` —— 那是用一份
 * 拟真的行情跑出来的完整原文，可以直接拿去和线上表现对照，调提示词不用猜。
 */
class AiPromptTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Calendar =
        Calendar.getInstance().apply {
            set(year, month - 1, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }

    /** 80 个交易日，最后一根是 2026-11-10（盘中），价格带波动和趋势。 */
    private fun buildSeries(): KlineSeries {
        val now = at(2026, 11, 10, 14, 30)
        val dates = ArrayList<String>(80)
        val cursor = now.clone() as Calendar
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        while (dates.size < 80) {
            val dow = cursor.get(Calendar.DAY_OF_WEEK)
            if (dow != Calendar.SATURDAY && dow != Calendar.SUNDAY) {
                dates.add(0, format.format(cursor.time))
            }
            cursor.add(Calendar.DAY_OF_MONTH, -1)
        }

        val candles = dates.mapIndexed { index, date ->
            val close = 2200.0 + 120.0 * sin(index / 9.0) - index * 0.6
            val previous = if (index == 0) close else 2200.0 + 120.0 * sin((index - 1) / 9.0) - (index - 1) * 0.6
            Candle(
                date = date,
                open = previous,
                close = close,
                high = maxOf(previous, close) + 8.0,
                low = minOf(previous, close) - 8.0,
                volume = if (index == dates.lastIndex) 20_654_874.0 else 26_294_975.0
            )
        }
        return KlineSeries(
            code = "931151",
            name = "光伏产业",
            source = KlineSource.EXCHANGE,
            candles = candles,
            meta = KlineMeta(
                pe = 32.96,
                constituents = 50,
                historySource = "中证指数官网",
                tailSource = "东方财富",
                asOf = "2026-11-10"
            )
        )
    }

    private fun buildContext() = AiContext(
        pulse = listOf(
            PulseRow("上证指数", "000001", 3660.79, 0.32),
            PulseRow("深证成指", "399001", 11_922.40, -0.18),
            PulseRow("创业板指", "399006", 2_410.55, 0.64),
            PulseRow("沪深300", "000300", 4056.11, 0.05),
            PulseRow("中证500", "000905", 6_180.22, 0.41),
            PulseRow("科创50", "000688", 1_042.30, -0.52)
        ),
        benchmark = PulseRow("沪深300", "000300", 4056.11, 0.05),
        benchmark20 = 1.10,
        relative20 = 2.30,
        relatedFunds = listOf("光伏产业ETF联接A（012345）"),
        accounts = listOf(
            AccountLine(
                fundCode = "012345",
                fundName = "光伏产业ETF联接A",
                shares = 12_345.67,
                avgCost = 0.8210,
                nav = 0.7932,
                value = 9_792.79,
                profit = -343.02,
                profitPercent = -3.38,
                today = 54.81,
                source = "天天基金·估值"
            )
        ),
        constituents = listOf(
            "阳光电源" to 1.24, "隆基绿能" to -0.86, "通威股份" to 0.42,
            "TCL中环" to -1.10, "晶澳科技" to 0.75
        ),
        events = listOf("光伏产业ETF联接A（012345）开放申购，最新净值日期 2026-11-09"),
        missing = listOf("前十大重仓股行情")
    )

    private fun buildPrevious() = AiVerdict(
        action = SwingAction.HOLD,
        confidence = 62,
        reason = "大盘窄幅震荡，光伏板块相对沪深300 略强但量能不足，等站上 MA20 再说。",
        risk = "跌破 2100 则趋势转弱",
        raw = "{}",
        at = at(2026, 11, 10, 10, 12).timeInMillis,
        trend = Trend.RANGE
    )

    @Test
    fun promptKeepsIntradayBarOutOfIndicators() {
        val prompt = AiPrompt.judge(
            fundName = "光伏产业",
            code = "931151",
            subject = "指数",
            raw = buildSeries(),
            context = buildContext(),
            previous = buildPrevious(),
            sameDaySwitches = 0,
            now = at(2026, 11, 10, 14, 30)
        )
        val text = prompt.render()

        // 指标基准日必须是上一个已收盘的交易日，不能是今天这根还在跳的。
        assertTrue(text.contains("技术指标基准日：2026-11-09"))
        assertTrue(text.contains("【四、技术面（全部基于 2026-11-09 收盘，已定型）】"))
        // 今天的实时价单独放在「盘中」一段，并明确说不能作为改口依据。
        assertTrue(text.contains("【五、盘中实时"))
        assertTrue(text.contains("不能作为改变结论的依据"))
        // 上一次的结论和切换门槛都要在。
        assertTrue(text.contains("【八、上一次的判断】"))
        assertTrue(text.contains("上一次（2026-11-10 10:12）给出：观望（置信度 62）"))
        assertTrue(text.contains("trend"))
        assertTrue(text.contains("同一天内不要反复改口"))
        // 不能漏出计算失败的痕迹。
        assertFalse(text.contains("NaN"))
        assertFalse(text.contains("null"))
    }

    /** 这几条是 1.14.0 新增的数据驱动护栏，任何一条被删掉都该被拦下来。 */
    @Test
    fun promptCarriesDataDrivenGuards() {
        val text = AiPrompt.judge(
            fundName = "光伏产业",
            code = "931151",
            subject = "指数",
            raw = buildSeries(),
            context = buildContext(),
            previous = buildPrevious(),
            sameDaySwitches = 0,
            now = at(2026, 11, 10, 14, 30)
        ).render()

        // 大盘只做标注，不再硬禁止买入：全历史回测里「普跌日禁止买入」是负贡献。
        assertTrue(text.contains("标注：正常（没有一边倒的普跌或普涨）"))
        assertFalse(text.contains("不允许给「波段买入」"))
        // MACD 要看三根柱，「未连续衰竭」才成立。
        assertTrue(text.contains("再前一根柱="))
        assertTrue(text.contains("最近两根柱有没有连续缩短"))
        // 置信度三项上限。
        assertTrue(text.contains("【四·补充】置信度上限"))
        assertTrue(text.contains("confidence 必须 ≤ 60"))
        // 结论有效期。
        assertTrue(text.contains("有效期 5 个交易日"))
    }

    /** 上一次结论过期后，不能再拿「维持」把它锁死。 */
    @Test
    fun expiredVerdictInvitesRejudge() {
        val old = buildPrevious().copy(at = at(2026, 10, 10, 15, 0).timeInMillis)
        val text = AiPrompt.judge(
            fundName = "光伏产业",
            code = "931151",
            subject = "指数",
            raw = buildSeries(),
            context = buildContext(),
            previous = old,
            sameDaySwitches = 0,
            now = at(2026, 11, 10, 14, 30)
        ).render()
        assertTrue(text.contains("这条结论已过期"))
        assertTrue(text.contains("可以重新判断"))
    }

    @Test
    fun secondSwitchPromptIsStricter() {
        val prompt = AiPrompt.judge(
            fundName = "光伏产业",
            code = "931151",
            subject = "指数",
            raw = buildSeries(),
            context = buildContext(),
            previous = buildPrevious(),
            sameDaySwitches = 2,
            now = at(2026, 11, 10, 14, 30)
        )
        assertTrue(prompt.user.contains("今天已经改过 2 次方向了"))
        assertTrue(prompt.user.contains("置信度 ≥ 75 且 trend 与 action 同向"))
    }

    @Test
    fun closedBarIsUsedAsIsAfterClose() {
        val series = buildSeries()
        // 14:30 算没收盘，15:30 算已经收盘。
        assertTrue(series.hasOpenBar(now = at(2026, 11, 10, 14, 30)))
        assertFalse(series.hasOpenBar(now = at(2026, 11, 10, 15, 30)))
        assertTrue(series.completed(now = at(2026, 11, 10, 14, 30)).candles.size == series.candles.size - 1)
        assertTrue(series.completed(now = at(2026, 11, 10, 15, 30)).candles.size == series.candles.size)
    }

    /** 把完整原文写到文件，供人工比对；断言只是保证它确实写成功了。 */
    @Test
    fun dumpsReadableSample() {
        val prompt = AiPrompt.judge(
            fundName = "光伏产业",
            code = "931151",
            subject = "指数",
            raw = buildSeries(),
            context = buildContext(),
            previous = buildPrevious(),
            sameDaySwitches = 1,
            now = at(2026, 11, 10, 14, 30)
        )
        val file = File(System.getProperty("user.dir"), "build/ai-prompt-sample.txt")
        file.parentFile?.mkdirs()
        file.writeText(prompt.render())
        assertTrue(file.length() > 1_000)
        println("AI 提示词样例已写入 ${file.absolutePath}")
    }
}
