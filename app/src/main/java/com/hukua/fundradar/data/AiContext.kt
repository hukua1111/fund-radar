package com.hukua.fundradar.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 一个宽基/风格指数的盘中读数。 */
data class PulseRow(
    val name: String,
    val code: String,
    val price: Double,
    val percent: Double
)

/** 账户里与这个指数相关的一只基金。 */
data class AccountLine(
    val fundCode: String,
    val fundName: String,
    val shares: Double,
    val avgCost: Double,
    val nav: Double,
    val value: Double,
    val profit: Double,
    val profitPercent: Double?,
    val today: Double?,
    val source: String
)

/**
 * 交给 AI 的完整上下文。
 *
 * 判断「现在该不该买卖」只看一条均线是远远不够的，所以这里按
 * 大盘 → 风格/板块 → 标的 → 个股 → 账户与事件 五层把数据凑齐：
 * 先看市场冷暖，再看这个板块相对强弱，然后才是个券自身的技术面，
 * 最后才是「我自己的成本和仓位」——同一根 K 线，空仓和深套的结论不一样。
 */
data class AiContext(
    val pulse: List<PulseRow> = emptyList(),
    /** 沪深300，作为风格比较的基准。 */
    val benchmark: PulseRow? = null,
    /** 沪深300 近 20 日涨跌，拿不到则为 null。 */
    val benchmark20: Double? = null,
    /** 标的指数近 20 日相对沪深300 的超额收益。 */
    val relative20: Double? = null,
    /** 跟踪这个指数的自选基金。 */
    val relatedFunds: List<String> = emptyList(),
    val accounts: List<AccountLine> = emptyList(),
    /** 前十大重仓股（名称 + 当日涨跌），来自相关基金的最新持仓。 */
    val constituents: List<Pair<String, Double>> = emptyList(),
    val events: List<String> = emptyList(),
    /** 这次没取到的数据，写进提示词里让模型知道哪里是盲区。 */
    val missing: List<String> = emptyList()
) {
    val hasAnything: Boolean
        get() = pulse.isNotEmpty() || accounts.isNotEmpty() || constituents.isNotEmpty()
}

/**
 * 把「大盘、板块、个股、账户、事件」凑成一份上下文。
 *
 * 每个来源都单独 try + 超时：AI 判断不该因为某个接口慢了就整体失败，
 * 拿不到的部分会记进 [AiContext.missing]，提示词里会明确说「这块没有数据」。
 */
class AiContextBuilder(context: Context) {

    private val appContext = context.applicationContext

    suspend fun build(entry: IndexEntry, series: KlineSeries): AiContext = coroutineScope {
        val missing = mutableListOf<String>()

        // 1. 大盘：一次批量行情，6 个宽基指数。
        val pulseJob = async(Dispatchers.IO) {
            runCatching { IndexQuoteApi.fetch(BROAD) }.getOrDefault(emptyMap())
        }

        // 2. 基金 → 指数映射，用来找「哪些自选基金跟踪这个指数」。
        val proxyJob = async(Dispatchers.IO) {
            withTimeoutOrNull(6_000) {
                runCatching { IndexRepository(appContext).fundProxies() }.getOrNull()
            } ?: emptyMap()
        }

        // 3. 沪深300 的 20 日涨跌：衡量板块相对强弱要有基准。
        val benchJob = async(Dispatchers.IO) {
            withTimeoutOrNull(4_000) {
                runCatching { KlineRepository(appContext).load("1.000300").series }.getOrNull()
            }
        }

        val quotes = pulseJob.await()
        val pulse = BROAD.mapNotNull { target ->
            quotes[target.secid]?.let { quote ->
                PulseRow(
                    name = quote.name.ifBlank { target.name },
                    code = target.code,
                    price = quote.price,
                    percent = quote.changePercent
                )
            }
        }
        if (pulse.isEmpty()) missing += "大盘指数行情"

        val proxies = proxyJob.await()
        val relatedCodes = proxies.filterValues { it.secid == entry.secid }.keys.toList()
        if (proxies.isEmpty()) missing += "基金与指数的对应关系"

        val benchmark = pulse.firstOrNull { it.code == "000300" }
        val benchmark20 = benchJob.await()?.changeOver(20)
        val self20 = series.changeOver(20)
        val relative20 = if (benchmark20 != null && self20 != null) self20 - benchmark20 else null

        // 4. 账户 + 事件：只查跟踪这个指数的自选基金，最多三只。
        val fundsWithDetail = relatedCodes.take(3).mapNotNull { code ->
            withTimeoutOrNull(6_000) {
                runCatching { EastMoneyApi.fetchFundDetail(code) }.getOrNull()
            }?.toFund(code)
        }
        if (relatedCodes.isNotEmpty() && fundsWithDetail.isEmpty()) missing += "相关基金的最新净值"

        val estimates = if (fundsWithDetail.isEmpty()) {
            emptyMap()
        } else {
            withTimeoutOrNull(9_000) {
                runCatching {
                    EstimateRepository(appContext).estimates(
                        fundsWithDetail,
                        fundsWithDetail.associate { it.code to entry }
                    )
                }.getOrNull()
            }?.estimates.orEmpty()
        }

        val holdingStore = HoldingStore(appContext)
        val accounts = fundsWithDetail.mapNotNull { fund ->
            val holding = holdingStore.get(fund.code)?.takeUnless { it.isEmpty } ?: return@mapNotNull null
            val estimate = estimates[fund.code]
            val nav = estimate?.nav ?: fund.nav
            if (nav <= 0) return@mapNotNull null
            AccountLine(
                fundCode = fund.code,
                fundName = fund.name,
                shares = holding.shares,
                avgCost = holding.avgCost,
                nav = nav,
                value = holding.marketValue(nav),
                profit = holding.profit(nav),
                profitPercent = holding.profitPercent(nav),
                today = estimate?.takeIf { it.isRealtime }?.let { holding.todayProfit(nav, it.percent) },
                source = estimate?.sourceLabel.orEmpty()
            )
        }

        val events = buildList {
            fundsWithDetail.forEach { fund ->
                add("${fund.name}（${fund.code}）${fund.limitBadgeText}，最新净值日期 ${fund.navDate.ifBlank { "未知" }}")
            }
            if (relatedCodes.size > 1) add("共有 ${relatedCodes.size} 只自选基金跟踪这个指数")
            if (holdingStore.get(relatedCodes.firstOrNull().orEmpty()) == null && accounts.isEmpty() && relatedCodes.isNotEmpty()) {
                add("这只指数相关基金没有填写持仓")
            }
        }

        // 5. 个股：从相关基金的最新持仓里取前十大，配交易所行情。
        val constituents = runCatching { constituentsOf(relatedCodes) }.getOrDefault(emptyList())
        if (constituents.isEmpty() && relatedCodes.isNotEmpty()) missing += "前十大重仓股行情"

        AiContext(
            pulse = pulse,
            benchmark = benchmark,
            benchmark20 = benchmark20,
            relative20 = relative20,
            relatedFunds = fundsWithDetail.map { "${it.name}（${it.code}）" },
            accounts = accounts,
            constituents = constituents,
            events = events,
            missing = missing.distinct()
        )
    }

    /** 找一只有股票持仓的相关基金，取它的前十大重仓股与当日涨跌。 */
    private suspend fun constituentsOf(codes: List<String>): List<Pair<String, Double>> {
        for (code in codes.take(3)) {
            val position = withTimeoutOrNull(5_000) {
                runCatching { PositionApi.fetch(code) }.getOrNull()
            } ?: continue
            if (position.stocks.isEmpty()) continue
            val symbols = position.stocks.associate { it.code to exchangeSymbol(it.code, it.market) }
            val quotes = withTimeoutOrNull(6_000) {
                runCatching { QuoteApi.fetch(symbols.values) }.getOrNull()
            } ?: continue
            val rows = position.stocks.mapNotNull { stock ->
                val quote = quotes[symbols[stock.code]] ?: return@mapNotNull null
                stock.name.ifBlank { stock.code } to quote.changePercent
            }
            if (rows.isNotEmpty()) return rows
        }
        return emptyList()
    }

    companion object {
        /** 「大盘」这一层固定看这几个：沪、深、创业板、沪深300、中证500、科创50。 */
        val BROAD = listOf(
            IndexEntry("000001", 1, "上证指数"),
            IndexEntry("399001", 0, "深证成指"),
            IndexEntry("399006", 0, "创业板指"),
            IndexEntry("000300", 1, "沪深300"),
            IndexEntry("000905", 1, "中证500"),
            IndexEntry("000688", 1, "科创50")
        )
    }
}
