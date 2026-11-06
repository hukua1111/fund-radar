package com.hukua.fundradar.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Latest quote of one index. */
data class IndexQuote(
    val entry: IndexEntry,
    val latest: Double,
    val changePct: Double,
    val date: String
)

/** What the 指数 tab shows, plus whether the catalogue itself failed. */
data class TrackedResult(
    val items: List<TrackedIndex>,
    val catalogFailed: Boolean = false,
    /** 跟踪标的名没能解析成指数代码，用来给用户一句解释而不是静默消失。 */
    val unresolved: List<String> = emptyList()
)

/**
 * Turns the watchlist into the list of indices those funds track, and keeps the
 * indices the user added by hand on top of that.
 *
 * The fund page only carries the index *name* (跟踪标的), so the name is matched
 * against the local index catalogue to get a quotable secid.
 */
class IndexRepository(context: Context) {

    private val appContext = context.applicationContext
    private val funds = FundRepository(appContext)
    private val manual = IndexWatchStore(appContext)

    suspend fun tracked(): TrackedResult = withContext(Dispatchers.IO) {
        val manualItems = manual.entries().map {
            TrackedIndex(it, MANUAL_LABEL, emptyList(), manual = true)
        }

        val watchlist = funds.load().funds
        if (watchlist.isEmpty()) {
            return@withContext TrackedResult(manualItems)
        }

        val catalog = IndexCatalog.load(appContext)

        val byName = LinkedHashMap<String, MutableList<String>>()
        watchlist.forEach { fund ->
            val tracked = fund.trackIndex.trim()
            if (tracked.isBlank()) return@forEach
            byName.getOrPut(tracked) { mutableListOf() }.add(fund.code)
        }

        // 本地目录先匹配；目录里没有的（930xxx / 931xxx 中证自有代码）再去问
        // suggest 接口。并发解析，几个名字不会串成一条长队。
        val resolved = coroutineScope {
            byName.map { (name, codes) ->
                async {
                    val entry = resolveIndex(name, catalog)
                        ?: IndexCatalog.resolveByName(appContext, name)
                    Triple(name, codes, entry)
                }
            }.awaitAll()
        }

        val merged = LinkedHashMap<String, TrackedIndex>()
        val unresolved = ArrayList<String>()
        resolved.forEach { (name, codes, entry) ->
            if (entry == null) {
                unresolved += name
                return@forEach
            }
            val existing = merged[entry.secid]
            merged[entry.secid] = if (existing == null) {
                TrackedIndex(entry, name, codes.distinct())
            } else {
                existing.copy(fundCodes = (existing.fundCodes + codes).distinct())
            }
        }

        val auto = merged.values.sortedBy { it.entry.name }
        val autoSecids = auto.map { it.entry.secid }.toSet()
        TrackedResult(
            items = auto + manualItems.filterNot { it.entry.secid in autoSecids },
            catalogFailed = catalog.isEmpty() && auto.isEmpty(),
            unresolved = unresolved
        )
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

    // ------------------------------------------------------- manual indices

    fun isManual(secid: String): Boolean = manual.contains(secid)

    fun removeIndex(secid: String) = manual.remove(secid)

    /**
     * Validates the index against the quote endpoint before saving it, so the
     * list never contains a row whose K line cannot be drawn. The name returned
     * by the quote endpoint wins — it is the authoritative short name.
     *
     * When the endpoint cannot be reached at all we do not block the user: a
     * row picked from the search results is trusted and will simply show
     * "暂无行情" until the network is back. A bare code typed by hand still
     * needs a successful check, otherwise every typo would land in the list.
     */
    suspend fun addIndex(entry: IndexEntry): IndexEntry? = withContext(Dispatchers.IO) {
        if (manual.contains(entry.secid)) return@withContext entry

        val typedCode = entry.name == entry.code
        val candidates = if (typedCode) {
            // A bare code: try the likely venue first, then the other two
            // (0 深市 / 1 沪市 / 2 中证指数公司).
            listOf(entry) + listOf(0, 1, 2)
                .filter { it != entry.market }
                .map { entry.copy(market = it) }
        } else {
            listOf(entry)
        }

        var unreachable = false
        for (candidate in candidates) {
            val probe = EastMoneyApi.probeIndex(candidate.secid)
            when (probe.status) {
                IndexProbeStatus.OK -> {
                    val resolved = candidate.copy(name = probe.name.ifBlank { candidate.name })
                    manual.add(resolved)
                    return@withContext resolved
                }

                IndexProbeStatus.MISSING -> Unit
                IndexProbeStatus.UNREACHABLE -> unreachable = true
            }
        }

        if (unreachable && !typedCode) {
            manual.add(entry)
            return@withContext entry
        }
        null
    }

    /**
     * Search for the "添加指数" sheet.
     *
     * East Money's suggest API comes first — it is the only source that knows
     * 中证指数公司's own codes (930xxx / 931xxx), which most index funds track.
     * Tencent smartbox is the fallback (it also answers bare codes), and the
     * local catalogue covers the offline case. A bare 6-digit code that none of
     * them knows is offered as a candidate — [addIndex] verifies it against the
     * quote endpoint before it is saved.
     */
    suspend fun search(query: String): List<IndexEntry> = withContext(Dispatchers.IO) {
        val keyword = query.trim()
        if (keyword.isEmpty()) return@withContext emptyList()

        val eastMoney = runCatching { EastMoneyApi.searchIndices(keyword) }
            .getOrDefault(emptyList())
        if (eastMoney.isNotEmpty()) return@withContext eastMoney

        val remote = runCatching { IndexSearchApi.search(keyword) }.getOrDefault(emptyList())
        if (remote.isNotEmpty()) return@withContext remote

        val catalog = runCatching { IndexCatalog.load(appContext) }.getOrDefault(emptyList())
        val local = searchCatalog(keyword, catalog)
        if (local.isNotEmpty()) return@withContext local

        if (keyword.length == 6 && keyword.all { it.isDigit() }) {
            val market = if (keyword.startsWith("5") || keyword.startsWith("6")) 1 else 0
            return@withContext listOf(IndexEntry(keyword, market, keyword))
        }
        emptyList()
    }

    private companion object {
        const val QUOTE_BARS = 5
        const val MANUAL_LABEL = "手动添加"
    }
}
