package com.hukua.fundradar.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** 一次估算的结果：每只基金一条，外加整体性的错误说明。 */
data class EstimateBatch(
    val estimates: Map<String, Estimate> = emptyMap(),
    /** 行情源整体不可用时的说明，用来在界面上给一句解释。 */
    val error: String? = null,
    val at: Long = 0L
)

/**
 * Turns "what does this fund hold" into "how much is it up today".
 *
 * The chain is: [PositionApi] for the holdings (cached for a week, they only
 * change quarterly) → [QuoteApi] for one batched quote request → a weighted
 * change. Feeder funds use the ETF's own move; everything else falls back to
 * the weighted move of its top ten stocks, then to its tracked index, and
 * finally to the last published NAV change.
 *
 * 天天基金自己的盘中估值接口已被叫停，所以这里全部靠交易所的实时行情自己算。
 */
class EstimateRepository(context: Context) {

    private val appContext = context.applicationContext
    private val cacheFile = File(appContext.filesDir, "fund_position_cache.json")
    private val positions: MutableMap<String, CachedPosition> by lazy { readCache() }

    suspend fun estimates(
        funds: List<Fund>,
        /** 基金代码 → 标的指数，拿不到持仓时用它兜底。 */
        proxies: Map<String, IndexEntry> = emptyMap()
    ): EstimateBatch = withContext(Dispatchers.IO) {
        if (funds.isEmpty()) return@withContext EstimateBatch(at = System.currentTimeMillis())
        val now = System.currentTimeMillis()
        val semaphore = Semaphore(3)
        val dirty = AtomicBoolean(false)

        val resolved = coroutineScope {
            funds.map { fund ->
                async(Dispatchers.IO) {
                    val cached = synchronized(positions) { positions[fund.code] }
                    val usable = cached?.takeIf { now - it.savedAt < TTL_MS }?.position
                    val position = if (usable != null) {
                        usable
                    } else {
                        semaphore.withPermit {
                            val fresh = runCatching { PositionApi.fetch(fund.code) }
                                .getOrNull()
                                ?.takeUnless { it.isEmpty }
                            if (fresh != null) {
                                synchronized(positions) {
                                    positions[fund.code] = CachedPosition(fresh, now)
                                }
                                dirty.set(true)
                                fresh
                            } else {
                                cached?.position
                            }
                        }
                    }
                    fund to position
                }
            }.awaitAll()
        }

        if (dirty.get()) writeCache()

        val symbols = LinkedHashSet<String>()
        resolved.forEach { (fund, position) ->
            position?.etfCode?.takeIf { it.isNotBlank() }?.let { symbols += exchangeSymbol(it) }
            position?.stocks?.forEach { symbols += exchangeSymbol(it.code, it.market) }
            proxies[fund.code]?.let { symbols += indexQuoteSymbol(it) }
        }
        if (symbols.isEmpty()) {
            return@withContext EstimateBatch(error = "没有可用的持仓或指数数据", at = now)
        }

        val quotes = QuoteApi.fetch(symbols)
        val error = if (quotes.isEmpty()) "行情源暂时没有响应，稍后会自动重试" else null

        val result = LinkedHashMap<String, Estimate>()
        resolved.forEach { (fund, position) ->
            val proxy = proxies[fund.code]?.let { entry ->
                quotes[indexQuoteSymbol(entry)]?.let { entry to it }
            }
            estimateFund(fund, position, quotes, proxy)?.let { result[fund.code] = it }
        }
        EstimateBatch(estimates = result, error = error, at = now)
    }

    // ------------------------------------------------------------ cache

    private fun readCache(): MutableMap<String, CachedPosition> {
        val map = HashMap<String, CachedPosition>()
        if (!cacheFile.exists()) return map
        runCatching {
            val root = JSONObject(cacheFile.readText())
            root.keys().forEach { code ->
                val item = root.optJSONObject(code) ?: return@forEach
                val stocks = ArrayList<HoldingStock>()
                val rows = item.optJSONArray("stocks")
                if (rows != null) {
                    for (i in 0 until rows.length()) {
                        val row = rows.optJSONObject(i) ?: continue
                        stocks += HoldingStock(
                            code = row.optString("code"),
                            name = row.optString("name"),
                            weight = row.optDouble("weight", 0.0),
                            market = row.optInt("market", -1)
                        )
                    }
                }
                map[code] = CachedPosition(
                    position = FundPosition(
                        etfCode = item.optString("etfCode"),
                        etfName = item.optString("etfName"),
                        stocks = stocks
                    ),
                    savedAt = item.optLong("savedAt", 0L)
                )
            }
        }
        return map
    }

    private fun writeCache() {
        runCatching {
            val root = JSONObject()
            synchronized(positions) { HashMap(positions) }.forEach { (code, entry) ->
                val rows = JSONArray()
                entry.position.stocks.forEach { stock ->
                    rows.put(
                        JSONObject()
                            .put("code", stock.code)
                            .put("name", stock.name)
                            .put("weight", stock.weight)
                            .put("market", stock.market)
                    )
                }
                root.put(
                    code,
                    JSONObject()
                        .put("etfCode", entry.position.etfCode)
                        .put("etfName", entry.position.etfName)
                        .put("stocks", rows)
                        .put("savedAt", entry.savedAt)
                )
            }
            cacheFile.writeText(root.toString())
        }
    }

    private data class CachedPosition(val position: FundPosition, val savedAt: Long)

    private companion object {
        /** Holdings only move when a quarterly report lands. */
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
