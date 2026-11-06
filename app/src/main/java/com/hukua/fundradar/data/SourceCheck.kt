package com.hukua.fundradar.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** 一条自检结果：哪一步、通没通、看到了什么。 */
data class SourceCheckRow(
    val label: String,
    val ok: Boolean,
    val detail: String
)

/**
 * 估值数据源自检。
 *
 * 盘中的「估算涨跌」要连过四关：基金持仓（拿到目标 ETF 代码）→ 交易所行情
 * （腾讯，失败时补东财）→ 标的指数行情 → 本地计算。任何一关断了，界面上看
 * 起来都只是「按标的指数估算」，分不清是没配 ETF 还是行情没取到。这个自检
 * 把每一关的真实返回摊开，出问题时一眼能看出卡在哪。
 */
object SourceCheck {

    suspend fun run(context: Context): List<SourceCheckRow> = withContext(Dispatchers.IO) {
        val rows = ArrayList<SourceCheckRow>()
        val repository = FundRepository(context)
        val codes = repository.codes()
        // 优先拿一只 ETF 联接基金做样本，它同时覆盖「持仓」和「目标 ETF 行情」两步。
        val fundCode = codes.firstOrNull { it == "008280" } ?: codes.firstOrNull() ?: "008280"

        val position = runCatching { PositionApi.fetch(fundCode) }.getOrNull()
        val etfCode = position?.etfCode.orEmpty()
        val etfLabel = listOf(position?.etfName.orEmpty(), etfCode)
            .filter { it.isNotBlank() }
            .joinToString(" ")

        rows += when {
            position == null -> SourceCheckRow(
                "基金持仓接口",
                false,
                "$fundCode 没有返回持仓（接口不通或被限流）"
            )
            etfCode.isNotBlank() -> SourceCheckRow(
                "基金持仓接口",
                true,
                "$fundCode → 目标 ETF $etfLabel"
            )
            position.stocks.isNotEmpty() -> SourceCheckRow(
                "基金持仓接口",
                true,
                "$fundCode → ${position.stocks.size} 只重仓股（没有联接 ETF）"
            )
            else -> SourceCheckRow("基金持仓接口", false, "$fundCode 返回空持仓")
        }

        // 腾讯行情：直接问这只 ETF（没有 ETF 就用第一只重仓股）。
        val probeCode = etfCode.ifBlank { position?.stocks?.firstOrNull()?.code.orEmpty() }
        val probeSymbol = if (probeCode.isBlank()) "" else {
            exchangeSymbol(probeCode, position?.stocks?.firstOrNull { it.code == probeCode }?.market ?: -1)
        }

        if (probeSymbol.isBlank()) {
            rows += SourceCheckRow("腾讯行情", false, "没有可探测的代码")
            rows += SourceCheckRow("东财行情", false, "没有可探测的代码")
        } else {
            val tencent = runCatching { QuoteApi.fromTencent(listOf(probeSymbol)) }
                .getOrDefault(emptyMap())
            rows += tencent[probeSymbol]?.let { quote ->
                SourceCheckRow("腾讯行情", true, "$probeSymbol ${pct(quote.changePercent)} · ${quote.time}")
            } ?: SourceCheckRow("腾讯行情", false, "$probeSymbol 查不到（这是 ETF 估值失败最常见的原因）")

            val eastmoney = runCatching { QuoteApi.fromEastmoney(listOf(probeSymbol)) }
                .getOrDefault(emptyMap())
            rows += eastmoney[probeSymbol]?.let { quote ->
                SourceCheckRow("东财行情", true, "$probeSymbol ${pct(quote.changePercent)} · ${quote.time}")
            } ?: SourceCheckRow("东财行情", false, "$probeSymbol 查不到")
        }

        // 标的指数：看基金 → 指数的映射能不能拿到，再看指数行情。
        val indexRepository = IndexRepository(context)
        val proxies = runCatching { indexRepository.fundProxies() }.getOrDefault(emptyMap())
        val entry = proxies[fundCode]
        if (entry == null) {
            rows += SourceCheckRow("标的指数", false, "$fundCode 还没有匹配到跟踪指数")
        } else {
            val quotes = runCatching { IndexQuoteApi.fetch(listOf(entry)) }.getOrDefault(emptyMap())
            val quote = quotes[entry.secid]
            rows += if (quote != null) {
                SourceCheckRow("标的指数", true, "${entry.name} ${entry.code} ${pct(quote.changePercent)}")
            } else {
                SourceCheckRow("标的指数", false, "${entry.name} ${entry.code} 没有行情")
            }
        }

        rows
    }

    private fun pct(value: Double): String = String.format(Locale.US, "%+.2f%%", value)
}
