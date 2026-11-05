package com.hukua.fundradar.data

import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/** The purchase limit situation of a fund, as reported by the data source. */
enum class LimitType {
    /** 暂停申购 */
    SUSPENDED,

    /** 限大额 — a daily cap exists */
    LIMITED,

    /** 开放申购 / 不限购 */
    OPEN,

    /** Unknown or not reported */
    UNKNOWN
}

/**
 * One fund, one card.
 *
 * [maxBuy] is the daily purchase cap in CNY. The upstream feed reports
 * `100000000000` (100 billion) for funds without any cap, which we treat as
 * "unlimited" through [isUnlimited].
 */
data class Fund(
    val code: String,
    val name: String,
    val type: String = "",
    val nav: Double = 0.0,
    val accNav: Double = 0.0,
    val dayGrowth: Double = 0.0,
    val buyStatus: String = "",
    val sellStatus: String = "",
    val minBuy: Double? = null,
    val maxBuy: Double? = null,
    val rate: String = "",
    val sourceRate: String = "",
    val riskLevel: Int = 0,
    val navDate: String = ""
) {
    val limitType: LimitType
        get() = when {
            buyStatus.contains("暂停") -> LimitType.SUSPENDED
            buyStatus.contains("限") -> LimitType.LIMITED
            buyStatus.contains("开放") -> LimitType.OPEN
            else -> LimitType.UNKNOWN
        }

    val isUnlimited: Boolean
        get() = maxBuy == null || maxBuy >= UNLIMITED_THRESHOLD

    /** Short label shown in the top-right corner of the card. */
    val limitBadgeText: String
        get() = when (limitType) {
            LimitType.SUSPENDED -> "暂停申购"
            LimitType.LIMITED -> maxBuy?.let { "限大额 " + money(it) } ?: "限大额"
            LimitType.OPEN -> if (isUnlimited) "不限购" else "限大额 " + money(maxBuy ?: 0.0)
            LimitType.UNKNOWN -> buyStatus.ifBlank { "状态未知" }
        }

    /** Longer sentence used in the detail sheet. */
    val limitDetailText: String
        get() = when (limitType) {
            LimitType.SUSPENDED -> "该基金当前暂停申购"
            LimitType.LIMITED, LimitType.OPEN ->
                if (isUnlimited) "单日申购无额度上限" else "单日累计申购上限 " + money(maxBuy ?: 0.0)
            LimitType.UNKNOWN -> "暂无申购状态数据"
        }

    val subtitle: String
        get() = listOf(code, type)
            .filter { it.isNotBlank() }
            .joinToString("  ·  ")

    fun merge(detail: FundDetail): Fund = copy(
        name = detail.name.ifBlank { name },
        type = detail.type.ifBlank { type },
        nav = if (detail.nav > 0) detail.nav else nav,
        dayGrowth = detail.dayGrowth,
        navDate = detail.navDate.ifBlank { navDate },
        buyStatus = detail.buyStatus.ifBlank { buyStatus },
        sellStatus = detail.sellStatus.ifBlank { sellStatus },
        minBuy = detail.minBuy ?: minBuy,
        maxBuy = detail.maxBuy ?: maxBuy,
        rate = detail.rate.ifBlank { rate },
        sourceRate = detail.sourceRate.ifBlank { sourceRate },
        riskLevel = if (detail.riskLevel > 0) detail.riskLevel else riskLevel
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("code", code)
        put("name", name)
        put("type", type)
        put("nav", nav)
        put("accNav", accNav)
        put("dayGrowth", dayGrowth)
        put("buyStatus", buyStatus)
        put("sellStatus", sellStatus)
        put("minBuy", minBuy ?: JSONObject.NULL)
        put("maxBuy", maxBuy ?: JSONObject.NULL)
        put("rate", rate)
        put("sourceRate", sourceRate)
        put("riskLevel", riskLevel)
        put("navDate", navDate)
    }

    companion object {
        const val UNLIMITED_THRESHOLD = 10_000_000_000.0

        fun fromJson(json: JSONObject): Fund = Fund(
            code = json.optString("code"),
            name = json.optString("name"),
            type = json.optString("type"),
            nav = json.optDouble("nav", 0.0),
            accNav = json.optDouble("accNav", 0.0),
            dayGrowth = json.optDouble("dayGrowth", 0.0),
            buyStatus = json.optString("buyStatus"),
            sellStatus = json.optString("sellStatus"),
            minBuy = if (json.isNull("minBuy")) null else json.optDouble("minBuy"),
            maxBuy = if (json.isNull("maxBuy")) null else json.optDouble("maxBuy"),
            rate = json.optString("rate"),
            sourceRate = json.optString("sourceRate"),
            riskLevel = json.optInt("riskLevel", 0),
            navDate = json.optString("navDate")
        )
    }
}

/** Everything parsed from a fund page; also used as the disk cache record. */
data class FundDetail(
    val name: String = "",
    val nav: Double = 0.0,
    val dayGrowth: Double = 0.0,
    val navDate: String = "",
    val type: String = "",
    val buyStatus: String = "",
    val sellStatus: String = "",
    val minBuy: Double? = null,
    val maxBuy: Double? = null,
    val rate: String = "",
    val sourceRate: String = "",
    val riskLevel: Int = 0,
    val company: String = "",
    val manager: String = "",
    val established: String = "",
    val scale: String = ""
) {
    fun toFund(code: String): Fund = Fund(
        code = code,
        name = name,
        type = type,
        nav = nav,
        dayGrowth = dayGrowth,
        buyStatus = buyStatus,
        sellStatus = sellStatus,
        minBuy = minBuy,
        maxBuy = maxBuy,
        rate = rate,
        sourceRate = sourceRate,
        riskLevel = riskLevel,
        navDate = navDate
    )

    fun toExtra(): FundExtra = FundExtra(
        company = company,
        manager = manager,
        established = established,
        scale = scale
    )
}

/** Extra fields shown only inside the detail sheet. */
data class FundExtra(
    val company: String = "",
    val manager: String = "",
    val established: String = "",
    val scale: String = ""
)

/** How the purchase limit moved between two refreshes. */
enum class ChangeKind {
    /** 额度变少 / 开始限购 */
    TIGHTENED,

    /** 额度变多 / 取消限购 */
    RELAXED,

    /** 转为暂停申购 */
    SUSPENDED,

    /** 从暂停申购恢复 */
    RESUMED
}

/** One fund whose purchase limit changed since the previous fetch. */
data class LimitChange(
    val code: String,
    val name: String,
    val oldLabel: String,
    val newLabel: String,
    val kind: ChangeKind
) {
    val isNegative: Boolean
        get() = kind == ChangeKind.TIGHTENED || kind == ChangeKind.SUSPENDED
}

/**
 * Compares the previously cached page with the freshly fetched one.
 * Returns null when the limit situation did not move (or was unknown before).
 */
fun limitChangeOf(code: String, old: FundDetail, new: FundDetail): LimitChange? {
    val before = old.toFund(code)
    val after = new.toFund(code)
    if (before.buyStatus.isBlank() || after.buyStatus.isBlank()) return null
    if (before.limitBadgeText == after.limitBadgeText) return null

    val kind = when {
        after.limitType == LimitType.SUSPENDED -> ChangeKind.SUSPENDED
        before.limitType == LimitType.SUSPENDED -> ChangeKind.RESUMED
        after.limitType == LimitType.LIMITED && before.limitType == LimitType.OPEN ->
            ChangeKind.TIGHTENED
        after.limitType == LimitType.OPEN && before.limitType == LimitType.LIMITED ->
            ChangeKind.RELAXED
        (after.maxBuy ?: 0.0) < (before.maxBuy ?: 0.0) -> ChangeKind.TIGHTENED
        else -> ChangeKind.RELAXED
    }

    return LimitChange(
        code = code,
        name = after.name.ifBlank { before.name },
        oldLabel = before.limitBadgeText,
        newLabel = after.limitBadgeText,
        kind = kind
    )
}

// ---------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------

/** 300000 -> "30万", 1000 -> "1000元", 10 -> "10元" */
fun money(value: Double): String = when {
    value >= 100_000_000 -> trimNumber(value / 100_000_000) + "亿"
    value >= 10_000 -> trimNumber(value / 10_000) + "万"
    else -> trimNumber(value) + "元"
}

/** 1.2345 -> "1.2345"; keeps four decimals like fund NAV pages do. */
fun navText(value: Double): String = String.format(Locale.US, "%.4f", value)

/** +5.61 / -1.20 with sign and two decimals. */
fun growthText(value: Double): String {
    val sign = if (value > 0) "+" else ""
    return sign + String.format(Locale.US, "%.2f", value) + "%"
}

fun riskText(level: Int): String = when (level) {
    1 -> "低风险"
    2 -> "中低风险"
    3 -> "中风险"
    4 -> "中高风险"
    5 -> "高风险"
    else -> "—"
}

private fun trimNumber(value: Double): String {
    // Round to one decimal first so 49.9999万 prints as "50万", not "50.0万".
    val rounded = String.format(Locale.US, "%.1f", value).toDouble()
    return if (abs(rounded - rounded.toLong()) < 0.05) {
        rounded.toLong().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
}
