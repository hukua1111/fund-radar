package com.hukua.fundradar.data

import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * 一只基金的定投计划。只有「每月投多少、每月几号扣、从哪个月开始、赚到多少止盈」，
 * 不记流水 —— 真正买到多少份额已经在 [Holding] 里，这里只描述**节奏**。
 *
 * 已投期数不单独存：按「起始月 → 当前月」推算，用户改了起始月，期数和累计投入
 * 立刻跟着变，不会出现两份数据互相打架。
 */
data class DcaPlan(
    /** 每期金额（元）；0 表示还没设定投计划。 */
    val monthlyAmount: Double = 0.0,
    /** 每月扣款日，1–28（避开月末，2 月也不会漏）。 */
    val day: Int = 8,
    /** 起始月份，`yyyy-MM`；空表示未设置。 */
    val startMonth: String = "",
    /** 止盈目标（%），达到后建议分批落袋。 */
    val targetProfitPct: Double = 30.0
) {
    val enabled: Boolean get() = monthlyAmount > 0

    val dayOfMonth: Int get() = day.coerceIn(1, 28)

    /** 止盈线兜底 30%，避免存进 0 后永远不触发。 */
    val target: Double get() = if (targetProfitPct > 0) targetProfitPct else 30.0

    /** 从起始月到 [now] 已经扣过几期（含起始月；本月未到扣款日则不算）。 */
    fun periods(now: Calendar = Calendar.getInstance()): Int {
        if (!enabled) return 0
        val year = startMonth.substringBefore('-').toIntOrNull() ?: return 0
        val month = startMonth.substringAfter('-', "").toIntOrNull() ?: return 0
        if (month !in 1..12) return 0
        var months = (now.get(Calendar.YEAR) - year) * 12 + (now.get(Calendar.MONTH) + 1 - month)
        if (months < 0) return 0
        if (now.get(Calendar.DAY_OF_MONTH) >= dayOfMonth) months += 1
        return months
    }

    /** 按计划累计投入的本金。 */
    fun invested(now: Calendar = Calendar.getInstance()): Double = periods(now) * monthlyAmount

    /** 「2025-03 · 每月 8 日 · 1000 元」这样一行字。 */
    fun scheduleText(): String {
        if (!enabled) return "未设定投计划"
        val start = startMonth.ifBlank { "未设定起始月" }
        return "$start 起 · 每月 $dayOfMonth 日 · " + money(monthlyAmount)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("amount", monthlyAmount)
        put("day", day)
        put("start", startMonth)
        put("target", targetProfitPct)
    }

    companion object {
        /** 默认从本月开始，用户点开就能直接用。 */
        fun defaultFor(now: Calendar = Calendar.getInstance()): DcaPlan = DcaPlan(
            monthlyAmount = 1000.0,
            day = 8,
            startMonth = String.format(
                Locale.US,
                "%04d-%02d",
                now.get(Calendar.YEAR),
                now.get(Calendar.MONTH) + 1
            )
        )

        fun fromJson(json: JSONObject): DcaPlan = DcaPlan(
            monthlyAmount = json.optDouble("amount", 0.0),
            day = json.optInt("day", 8),
            startMonth = json.optString("start"),
            targetProfitPct = json.optDouble("target", 30.0)
        )
    }
}
