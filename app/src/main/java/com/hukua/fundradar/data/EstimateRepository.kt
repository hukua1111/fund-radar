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

/**
 * Turns "what does this fund hold" into "how much is it up today".
 *
 * The chain is: [PositionApi] for the holdings (cached for a week, they only
 * change quarterly) → [QuoteApi] for one batched quote request → a weighted
 * change. Feeder funds use the ETF's own move; everything else falls back to
 * the weighted move of its top ten stocks.
 */
class EstimateRepository(context: Context) {

    private val appContext = context.applicationContext
    private val cacheFile = File(appContext.filesDir, "fund_position_cache.json")
    private val positions: MutableMap<String, CachedPosition> by lazy { readCache() }

    suspend fun estimates(funds: List<Fund>): Map<String, Estimate> = withContext(Dispatchers.IO) {
        if (funds.isEmpty()) return@withContext emptyMap()
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
        resolved.forEach { (_, position) ->
            position?.etfCode?.takeIf { it.isNotBlank() }?.let { symbols += exchangeSymbol(it) }
            position?.stocks?.forEach { symbols += exchangeSymbol(it.code, it.market) }
        }
        if (symbols.isEmpty()) return@withContext emptyMap()
        val quotes = QuoteApi.fetch(symbols)

        val result = LinkedHashMap<String, Estimate>()
        resolved.forEach { (fund, position) ->
            if (position == null) return@forEach
            estimateFund(fund, position, quotes)?.let { result[fund.code] = it }
        }
        result
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
