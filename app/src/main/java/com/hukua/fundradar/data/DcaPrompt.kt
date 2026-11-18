package com.hukua.fundradar.data

import java.util.Calendar
import java.util.Locale

/**
 * 定投的 AI 提示词。
 *
 * 与波段判断（[AiPrompt]）刻意分开：波段问「这周买不买」，定投问「这个行业指数还值
 * 不值得按计划买、现在要不要停」。喂给模型的数据也不一样 —— 除了指数的技术面，还必须
 * 带上**定投计划**（多久一期、每期多少、从哪天开始）和**按计划回放出来的持仓**
 * （已投几期、累计投入、市值、收益率、最大浮亏），否则它给出的「继续 / 暂停」没有参照物。
 *
 * 提示词是纯函数，可以用固定的 K 线跑出完整原文来比对，调参不靠猜。
 */
internal object DcaPrompt {

    fun build(
        entry: IndexEntry,
        series: KlineSeries,
        report: DcaReport,
        plan: DcaPlan,
        now: Calendar = Calendar.getInstance()
    ): JudgePrompt {
        val completed = series.completed(now = now)
        val candles = completed.candles

        val ma = completed.maValues(listOf(5, 10, 20, 60, 120))
        val maText = listOf(5, 10, 20, 60, 120).joinToString(" / ") { period ->
            val value = ma[period]
            if (value == null) "MA$period —" else "MA$period ${fmt(value, 2)}"
        }

        val recentText = buildString {
            val recent = candles.takeLast(10)
            val offset = candles.size - recent.size
            recent.forEachIndexed { index, candle ->
                val before = if (offset + index > 0) candles[offset + index - 1].close else 0.0
                val pct = if (before > 0) (candle.close / before - 1.0) * 100.0 else 0.0
                appendLine("${candle.date} 收 ${fmt(candle.close, 2)}（${signed(pct)}）")
            }
        }.trimEnd()

        val signalText = report.signal?.let { signal ->
            if (signal.signals.isEmpty()) {
                "无事件信号 · ${signal.summary}"
            } else {
                signal.signals.joinToString("；") { item ->
                    "${item.action.label}·${item.title}（${item.reason.take(70)}）"
                }
            }
        } ?: "未计算"

        val planText = if (plan.enabled) {
            "${plan.cadenceText()} 投 ${fmt(plan.amount, 0)} 元，" +
                "${plan.startDate} 开始；止盈线 ${fmt(plan.target, 0)}%"
        } else {
            "尚未设定投计划（用户可能只是观察，也可能在等更好的位置）"
        }

        val sim = report.simulation
        val positionText = when {
            !plan.enabled -> "还没设定投计划，所以还没有持仓（用户可能只是在观察）"
            sim == null -> "计划已设，但可用日线不够，没法回放持仓"
            !sim.started -> "还没扣过款（下一期 ${sim.nextDue.ifBlank { "未定" }}）"
            else -> buildString {
                appendLine("已投 ${sim.periods} 期（首期 ${sim.firstBuy}，最近一期 ${sim.lastBuy}）")
                appendLine("累计投入 ${fmt(sim.invested, 0)} 元 · 持有份额 ${fmt(sim.units, 4)} 份")
                appendLine(
                    "当前市值 ${fmt(sim.value, 0)} 元 · 浮动盈亏 ${signedMoney(sim.profit)} 元" +
                        "（${signed(sim.profitPct)}）"
                )
                append("过程中最大浮亏 ${fmt(sim.maxLossPct, 1)}%")
            }
        }

        val extra = plan.note.trim().ifBlank { "没有补充（用户只在这个指数上做定投）" }

        val factorText = report.factors.joinToString("；") {
            "${it.name} ${it.score}/${it.max}（${it.detail}）"
        }

        val system = """
            你是一名做指数基金定投的顾问，服务对象是普通个人投资者。你只能使用用户给出的数据，不要编造行情、政策或消息面，也不要预测具体点位。
            判断原则：
            1. 定投赚的是「长期向上 + 波动摊薄」的钱。先判断这个行业指数还值不值得长期持有，再判断现在该不该继续扣款。
            2. 用户已达到自己设定的止盈线时，必须明确提示落袋，不能因为「长期看好」而让浮盈回吐。
            3. 趋势已经破坏（跌破长期均线且均线向下）时，应建议暂停或减少，而不是无条件「越跌越买」。
            4. 浮亏不是加仓的理由；只有当行业长期逻辑没坏、位置又低时才谈加码。
            5. 定投频率与金额是用户自己定的，你只能建议「维持 / 加倍 / 减半 / 暂停」，不要改扣款日这类细节。
            6. 不承诺收益，不给点位预测，不推荐具体产品。
            只输出一个 JSON 对象，不要 Markdown 代码块，不要任何多余文字。
        """.trimIndent()

        val user = buildString {
            appendLine("【定投标的】")
            appendLine("指数：${report.indexName}（${entry.code}）· ${if (entry.market == 1) "沪市" else "深市"}")
            appendLine("说明：这是一个行业 / 主题指数，用户会自己选 ETF 或场外指数基金去买。")
            appendLine()
            appendLine("【指数技术面】")
            appendLine("基准日：${report.asOf}（已收盘定型${if (report.live) "，另有盘中实时 bar 未计入" else ""}）")
            appendLine("$maText")
            appendLine("收盘 ${fmt(report.close, 2)} · ${report.summary}")
            appendLine(
                "近一年分位：" + (report.percentile?.let { fmt(it * 100.0, 0) + "%" } ?: "—") +
                    " · 距近一年高点回撤：" + (report.drawdown?.let { fmt(it, 1) + "%" } ?: "—") +
                    " · 年化波动：" + (report.volatility?.let { fmt(it, 1) + "%" } ?: "—")
            )
            appendLine("短线信号：$signalText")
            appendLine()
            appendLine("【我的定投计划】")
            appendLine(planText)
            appendLine()
            appendLine("【按计划回放出来的持仓】")
            appendLine(positionText)
            appendLine()
            appendLine("【我的补充说明】")
            appendLine(extra)
            appendLine()
            appendLine("【本地规则给出的参考（你可以同意，也可以反驳，但要说清依据）】")
            appendLine("定投评分：${report.score} / 100（${report.verdict.label}）")
            appendLine("评分明细：$factorText")
            appendLine("当前建议：${report.action.label} —— ${report.actionReason}")
            appendLine()
            appendLine("【最近 10 个交易日】")
            appendLine(recentText)
            appendLine()
            appendLine("【任务】")
            appendLine("结合上面的技术面、定投计划和回放出来的持仓，判断我现在应该怎么做。")
            appendLine("注意区分两种情况：还没开始（要不要开始）与已经投了几期（继续 / 减半 / 暂停 / 止盈卖出）。")
            appendLine()
            appendLine("【输出格式】只输出 JSON：")
            appendLine("""{"stance":"CONTINUE","confidence":72,"reason":"...","risk":"...","next":"..."}""")
            appendLine("stance 只能取 CONTINUE（继续）/ ADD（加码）/ REDUCE（减少）/ PAUSE（暂停）/ SELL（卖出）。")
            appendLine("confidence 是 0–100 的整数。")
            append("reason 不超过 120 字，必须引用上面至少两个具体数字；risk 不超过 60 字；next 是给我的下一条具体动作，不超过 40 字。")
        }

        return JudgePrompt(system, user)
    }

    private fun fmt(value: Double, digits: Int): String =
        String.format(Locale.US, "%.${digits}f", value)

    private fun signed(value: Double): String {
        val sign = if (value > 0) "+" else if (value < 0) "-" else ""
        return sign + String.format(Locale.US, "%.2f", kotlin.math.abs(value)) + "%"
    }

    private fun signedMoney(value: Double): String {
        val sign = if (value > 0) "+" else if (value < 0) "-" else ""
        return sign + String.format(Locale.US, "%,.0f", kotlin.math.abs(value))
    }
}
