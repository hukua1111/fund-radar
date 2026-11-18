package com.hukua.fundradar.data

import java.util.Locale

/**
 * AI 对定投给出的态度。
 *
 * 只让模型输出这几个枚举，不让它自由写「可以适当考虑」—— 那种句子落到界面上
 * 等于没说，用户要的是一个能直接执行的动作。
 */
enum class DcaStance(val label: String) {
    CONTINUE("继续定投"),
    ADD("加码定投"),
    REDUCE("减少定投"),
    PAUSE("暂停定投"),
    SELL("卖出"),
    UNKNOWN("未判断");

    companion object {
        fun of(raw: String): DcaStance = when (raw.trim().uppercase(Locale.US)) {
            "CONTINUE", "GO", "KEEP", "继续", "维持", "保持" -> CONTINUE
            "ADD", "BUY", "加码", "加仓", "加倍" -> ADD
            "REDUCE", "HALF", "CUT", "减少", "减半", "降低" -> REDUCE
            "PAUSE", "STOP", "HOLD", "暂停", "停止", "观望" -> PAUSE
            "SELL", "EXIT", "卖出", "清仓", "止盈" -> SELL
            else -> UNKNOWN
        }
    }
}

/** 一次 AI 定投判断的结果。 */
data class DcaAiAdvice(
    val stance: DcaStance,
    val confidence: Int,
    /** 结论理由，要求模型引用具体数字。 */
    val reason: String,
    /** 主要风险。 */
    val risk: String,
    /** 下一条具体动作。 */
    val next: String,
    val raw: String = "",
    /** 这一轮实际发出去的提示词原文，界面上可以展开复核。 */
    val prompt: String = "",
    val at: Long = System.currentTimeMillis()
) {
    val parsed: Boolean get() = stance != DcaStance.UNKNOWN
}
