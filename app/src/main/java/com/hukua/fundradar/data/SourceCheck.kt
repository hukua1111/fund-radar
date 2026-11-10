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
 * 估值的主口径是「跟踪指数」，所以自检第一步就看它：基金 → 指数的映射能不
 * 能对上、指数行情能不能取到。之后才看目标 ETF（指数认不出来时的备选）与
 * 腾讯 / 东财两条行情通道。任何一关断了，界面上看起来都只是「按重仓股估算」，
 * 分不清原因；这个自检把每一关的真实返回摊开，出问题一眼能看出卡在哪。
 */
object SourceCheck {

    suspend fun run(context: Context): List<SourceCheckRow> = withContext(Dispatchers.IO) {
        val rows = ArrayList<SourceCheckRow>()
        val repository = FundRepository(context)
        val codes = repository.codes()
        val fundCode = codes.firstOrNull { it == "008280" } ?: codes.firstOrNull() ?: "008280"

        // 1) 跟踪指数 —— 现在的主口径，放第一位。
        val indexRepository = IndexRepository(context)
        val proxies = runCatching { indexRepository.fundProxies() }.getOrDefault(emptyMap())
        val entry = proxies[fundCode]
        if (entry == null) {
            rows += SourceCheckRow("跟踪指数", false, "$fundCode 还没有匹配到跟踪指数")
        } else {
            val batch = runCatching { IndexQuoteApi.fetchBatch(listOf(entry)) }
                .getOrDefault(IndexQuoteBatch())
            val quote = batch.quotes[entry.secid]
            rows += if (quote != null) {
                SourceCheckRow(
                    "跟踪指数",
                    true,
                    "${entry.name} ${entry.code} ${pct(quote.changePercent)} · " +
                        batch.source.label + if (batch.degraded) "（备用源）" else "（主源）"
                )
            } else {
                SourceCheckRow("跟踪指数", false, "${entry.name} ${entry.code} 没有行情")
            }
        }

        // 2) 基金持仓：目标 ETF（备选口径）与前十大重仓股。
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

        // 3) 行情通道：直接问这只 ETF（没有 ETF 就用第一只重仓股）。
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
            } ?: SourceCheckRow("腾讯行情", false, "$probeSymbol 查不到")

            val eastmoney = runCatching { QuoteApi.fromEastmoney(listOf(probeSymbol)) }
                .getOrDefault(emptyMap())
            rows += eastmoney[probeSymbol]?.let { quote ->
                SourceCheckRow("东财行情", true, "$probeSymbol ${pct(quote.changePercent)} · ${quote.time}")
            } ?: SourceCheckRow("东财行情", false, "$probeSymbol 查不到")
        }

        rows
    }

    private fun pct(value: Double): String = String.format(Locale.US, "%+.2f%%", value)
}
