package com.hukua.fundradar.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Latest quote of one index. */
data class IndexQuote(
    val entry: IndexEntry,
    val latest: Double,
    val changePct: Double,
    val date: String
)

/**
 * Turns the watchlist into the list of indices those funds track.
 *
 * The fund page only carries the index *name* (跟踪标的), so the name is matched
 * against the local index catalogue to get a quotable secid.
 */
class IndexRepository(context: Context) {

    private val appContext = context.applicationContext
    private val funds = FundRepository(appContext)

    suspend fun tracked(): List<TrackedIndex> = withContext(Dispatchers.IO) {
        val catalog = IndexCatalog.load(appContext)
        check(catalog.isNotEmpty()) { "指数目录下载失败，检查网络后重试" }

        val watchlist = funds.load().funds
        val byName = LinkedHashMap<String, MutableList<String>>()
        watchlist.forEach { fund ->
            val tracked = fund.trackIndex.trim()
            if (tracked.isBlank()) return@forEach
            byName.getOrPut(tracked) { mutableListOf() }.add(fund.code)
        }

        val merged = LinkedHashMap<String, TrackedIndex>()
        byName.forEach { (name, codes) ->
            val entry = resolveIndex(name, catalog) ?: return@forEach
            val existing = merged[entry.secid]
            merged[entry.secid] = if (existing == null) {
                TrackedIndex(entry, name, codes.distinct())
            } else {
                existing.copy(fundCodes = (existing.fundCodes + codes).distinct())
            }
        }
        merged.values.sortedBy { it.entry.name }
    }

    suspend fun quote(entry: IndexEntry): IndexQuote? = withContext(Dispatchers.IO) {
        val series = EastMoneyApi.fetchIndexKline(entry.secid, QUOTE_BARS) ?: return@withContext null
        val candles = series.candles
        val last = candles.lastOrNull() ?: return@withContext null
        val previous = candles.getOrNull(candles.size - 2)?.close
        val change = if (previous != null && previous > 0) {
            (last.close - previous) / previous * 100.0
        } else {
            last.changePct
        }
        IndexQuote(entry, last.close, change, last.date)
    }

    suspend fun series(entry: IndexEntry, limit: Int = 250): KlineSeries? =
        withContext(Dispatchers.IO) { EastMoneyApi.fetchIndexKline(entry.secid, limit) }

    private companion object {
        const val QUOTE_BARS = 5
    }
}
