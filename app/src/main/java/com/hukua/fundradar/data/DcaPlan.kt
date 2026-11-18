package com.hukua.fundradar.data

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 定投节奏：按交易日、按周、按月。 */
enum class DcaFrequency(val label: String, val short: String) {
    DAILY("每个交易日", "每日"),
    WEEKLY("每周固定一天", "每周"),
    MONTHLY("每月固定一天", "每月");

    companion object {
        fun of(raw: String): DcaFrequency =
            entries.firstOrNull { it.name == raw.trim().uppercase(Locale.US) } ?: MONTHLY
    }
}

/** 周一 ~ 周五，[dayOfWeek] 用的就是这里的下标 + 1。 */
internal val WEEK_LABELS = listOf("一", "二", "三", "四", "五")

/** `yyyy-MM-dd` → 当天 0 点；解析不了就是 null。 */
internal fun parseDayText(text: String): Calendar? {
    val value = text.trim()
    if (value.length < 8) return null
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(value)
    }.getOrNull()?.let { date -> Calendar.getInstance().apply { time = date } }
}

/** 去掉时分秒，只留日期，用来比较「哪一天」。 */
internal fun dayOnly(source: Calendar): Calendar {
    val copy = Calendar.getInstance()
    copy.timeInMillis = source.timeInMillis
    copy.set(Calendar.HOUR_OF_DAY, 0)
    copy.set(Calendar.MINUTE, 0)
    copy.set(Calendar.SECOND, 0)
    copy.set(Calendar.MILLISECOND, 0)
    return copy
}

internal fun dayText(calendar: Calendar): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(calendar.timeInMillis))

/**
 * 一条定投计划。对象是**行业主题指数**，不是某只基金 —— 同一个指数底下可能有
 * ETF、场外指基、增强基金好几种买法，先判断指数的位置和趋势，再决定用哪个工具买。
 *
 * 计划只描述**节奏**（多久投一次、每次多少、从哪天开始、赚到多少止盈），不记流水：
 * 累计投入、持有份额、当前收益都由 [DcaSimulator] 按指数日线回放出来，改起始日
 * 立刻整条重算，不会出现两份账互相打架。
 */
data class DcaPlan(
    /** 每期金额（元）；0 表示还没设定投计划。 */
    val amount: Double = 0.0,
    val frequency: DcaFrequency = DcaFrequency.MONTHLY,
    /** 周几扣（1 = 周一 … 5 = 周五），只在 [DcaFrequency.WEEKLY] 下有意义。 */
    val dayOfWeek: Int = 1,
    /** 每月几号扣，1–28（避开月末，2 月也不会漏）。 */
    val dayOfMonth: Int = 8,
    /** 起始日 `yyyy-MM-dd`；空表示未设置。 */
    val startDate: String = "",
    /** 止盈目标（%），达到后建议分批落袋。 */
    val targetProfitPct: Double = 30.0,
    /** 我自己的持仓备注（「已另投 2 万在场外」之类），会原样喂给 AI。 */
    val note: String = ""
) {
    val enabled: Boolean get() = amount > 0 && start != null

    val start: Calendar? get() = parseDayText(startDate)

    val weekday: Int get() = dayOfWeek.coerceIn(1, 5)

    val monthDay: Int get() = dayOfMonth.coerceIn(1, 28)

    /** 止盈线兜底 30%，避免存进 0 后永远不触发。 */
    val target: Double get() = if (targetProfitPct > 0) targetProfitPct else 30.0

    /** 「每个交易日 / 每周三 / 每月 8 日」。 */
    fun cadenceText(): String = when (frequency) {
        DcaFrequency.DAILY -> "每个交易日"
        DcaFrequency.WEEKLY -> "每周" + WEEK_LABELS[weekday - 1]
        DcaFrequency.MONTHLY -> "每月 $monthDay 日"
    }

    /** 「2025-03-01 起 · 每周一 · 1,000 元」。 */
    fun scheduleText(): String {
        if (!enabled) return "未设定投计划"
        return "$startDate 起 · ${cadenceText()} · ${money(amount)}"
    }

    /** 一年按计划要投多少，用来和月供对比。 */
    fun yearlyAmount(): Double = when (frequency) {
        DcaFrequency.DAILY -> amount * 244
        DcaFrequency.WEEKLY -> amount * 52
        DcaFrequency.MONTHLY -> amount * 12
    }

    /** 第一期真正该扣的日子：按日起投就是当天，按周/按月则顺延到第一个对得上的日子。 */
    fun firstDue(): Calendar? {
        val from = start ?: return null
        val due = dayOnly(from)
        return when (frequency) {
            DcaFrequency.DAILY -> due
            DcaFrequency.WEEKLY -> {
                // Calendar 里周一 = 2，正好是 dayOfWeek + 1。
                var guard = 0
                while (due.get(Calendar.DAY_OF_WEEK) != weekday + 1 && guard++ < 7) {
                    due.add(Calendar.DAY_OF_MONTH, 1)
                }
                due
            }
            DcaFrequency.MONTHLY -> {
                if (due.get(Calendar.DAY_OF_MONTH) > monthDay) {
                    due.set(Calendar.DAY_OF_MONTH, 1)
                    due.add(Calendar.MONTH, 1)
                }
                due.set(Calendar.DAY_OF_MONTH, monthDay)
                due
            }
        }
    }

    /** 严格晚于 [from] 的下一期扣款日。 */
    fun nextDueAfter(from: Calendar): Calendar {
        val next = Calendar.getInstance()
        next.timeInMillis = from.timeInMillis
        when (frequency) {
            DcaFrequency.DAILY -> next.add(Calendar.DAY_OF_MONTH, 1)
            DcaFrequency.WEEKLY -> next.add(Calendar.DAY_OF_MONTH, 7)
            DcaFrequency.MONTHLY -> {
                val sameMonth = from.get(Calendar.DAY_OF_MONTH) < monthDay
                next.set(Calendar.DAY_OF_MONTH, 1)
                if (!sameMonth) next.add(Calendar.MONTH, 1)
                next.set(Calendar.DAY_OF_MONTH, monthDay)
            }
        }
        return dayOnly(next)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("amount", amount)
        put("freq", frequency.name)
        put("dow", dayOfWeek)
        put("dom", dayOfMonth)
        put("startDate", startDate)
        // 老版本存的是「起始月」字符串，这里顺手兼容一下。
        if (startDate.isBlank()) put("start", "")
        put("target", targetProfitPct)
        put("note", note)
    }

    companion object {
        /** 默认从今天开始、每月 8 日、1000 元，用户点开就能直接用。 */
        fun defaultFor(now: Calendar = Calendar.getInstance()): DcaPlan = DcaPlan(
            amount = 1000.0,
            frequency = DcaFrequency.MONTHLY,
            dayOfWeek = 1,
            dayOfMonth = 8,
            startDate = dayText(dayOnly(now))
        )

        fun fromJson(json: JSONObject): DcaPlan {
            // 上一版只存了起始月（yyyy-MM），补成当月 1 号，老计划不会丢。
            val legacyMonth = json.optString("start", "")
            val start = json.optString("startDate").ifBlank {
                if (legacyMonth.length >= 7) legacyMonth.take(7) + "-01" else ""
            }
            return DcaPlan(
                amount = json.optDouble("amount", 0.0),
                frequency = DcaFrequency.of(json.optString("freq", "MONTHLY")),
                dayOfWeek = json.optInt("dow", 1),
                dayOfMonth = json.optInt("dom", json.optInt("day", 8)),
                startDate = start,
                targetProfitPct = json.optDouble("target", 30.0),
                note = json.optString("note", "")
            )
        }
    }
}
