package com.hukua.fundradar.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

data class LoadResult(
    val funds: List<Fund>,
    val updatedAt: Long,
    val fromCache: Boolean,
    /** Funds whose purchase limit moved since the previous fetch. */
    val changes: List<LimitChange> = emptyList()
)

/**
 * Loads the funds the user picked, one request per fund.
 *
 * The fund page carries name, NAV, daily change, purchase status, the exact
 * daily cap, minimum purchase, type, fee, company, manager and size, so a single
 * fetch is enough for both the card and the detail sheet.
 *
 * To stay gentle with the upstream host: at most 3 requests in flight, every
 * result cached for [TTL_MS], and after [MAX_FAILURES] consecutive failures the
 * remaining funds fall back to whatever is cached instead of hammering on.
 */
class FundRepository(
    context: Context,
    val scope: FundScope = FundScope.PORTFOLIO
) {

    private val appContext = context.applicationContext
    private val store = WatchlistStore(appContext, scope)
    private val cacheFile = File(appContext.filesDir, "fund_detail_cache_v2.json")
    private val details: MutableMap<String, CachedDetail> by lazy { readCache() }

    fun codes(): List<String> = store.codes()

    fun isWatched(code: String): Boolean = store.contains(code)

    /** Name from the local cache, used by the daily job when the K line has none. */
    fun cachedName(code: String): String? =
        synchronized(details) { details[code]?.detail?.name?.ifBlank { null } }

    /** Adds a fund to the watchlist, validating the code against the live page. */
    suspend fun add(code: String): Boolean = withContext(Dispatchers.IO) {
        if (store.contains(code)) return@withContext true
        val detail = runCatching { EastMoneyApi.fetchFundDetail(code) }.getOrNull()
            ?: return@withContext false
        synchronized(details) {
            details[code] = CachedDetail(detail, System.currentTimeMillis())
            writeCache(details)
        }
        store.add(code)
        true
    }

    fun remove(code: String) {
        store.remove(code)
    }

    /**
     * Copies the portfolio into the limit watchlist in one go — the limit list
     * is usually a subset of what the user holds, so retyping codes would be
     * pointless busywork.
     */
    suspend fun importPortfolio(): Int = withContext(Dispatchers.IO) {
        val portfolio = WatchlistStore(appContext, FundScope.PORTFOLIO).codes()
        val before = store.codes().toSet()
        store.addAll(portfolio)
        store.codes().count { it !in before }
    }

    suspend fun load(
        codes: List<String> = store.codes(),
        force: Boolean = false
    ): LoadResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (codes.isEmpty()) return@withContext LoadResult(emptyList(), now, fromCache = false)

        val semaphore = Semaphore(3)
        val failures = AtomicInteger(0)
        val freshCount = AtomicInteger(0)

        val fetched = coroutineScope {
            codes.map { code ->
                async(Dispatchers.IO) {
                    val cached = synchronized(details) { details[code] }
                    if (!force && cached != null && now - cached.savedAt < TTL_MS) {
                        return@async Triple(code, cached.detail, null)
                    }
                    if (cached != null && failures.get() >= MAX_FAILURES) {
                        return@async Triple(code, cached.detail, null)
                    }
                    semaphore.withPermit {
                        val fresh = runCatching { EastMoneyApi.fetchFundDetail(code) }.getOrNull()
                        if (fresh != null) {
                            failures.set(0)
                            freshCount.incrementAndGet()
                            synchronized(details) { details[code] = CachedDetail(fresh, now) }
                            Triple(code, fresh, cached?.detail)
                        } else {
                            failures.incrementAndGet()
                            cached?.let { Triple(code, it.detail, null) }
                        }
                    }
                }
            }.awaitAll()
        }

        if (freshCount.get() > 0) {
            synchronized(details) { writeCache(details) }
        }

        val resolved = fetched.filterNotNull()
        val byCode = resolved.associateBy({ it.first }, { it.second })
        val changes = resolved.mapNotNull { (code, detail, previous) ->
            previous?.let { limitChangeOf(code, it, detail) }
        }
        val funds = codes.map { code ->
            byCode[code]?.toFund(code) ?: Fund(code = code, name = code)
        }
        LoadResult(
            funds = funds,
            updatedAt = now,
            fromCache = freshCount.get() == 0 && resolved.isNotEmpty(),
            changes = changes
        )
    }

    /** Company / manager / size for the detail sheet. */
    suspend fun extra(code: String): FundExtra? = withContext(Dispatchers.IO) {
        synchronized(details) { details[code] }?.let { return@withContext it.detail.toExtra() }
        val fresh = runCatching { EastMoneyApi.fetchFundDetail(code) }.getOrNull()
            ?: return@withContext null
        synchronized(details) {
            details[code] = CachedDetail(fresh, System.currentTimeMillis())
            writeCache(details)
        }
        fresh.toExtra()
    }

    /** Search used by the "add fund" sheet. */
    suspend fun search(query: String): List<Fund> = withContext(Dispatchers.IO) {
        runCatching { EastMoneyApi.fetchList(pageSize = 20, keyword = query) }
            .getOrDefault(emptyList())
    }

    // -----------------------------------------------------------

    private fun readCache(): MutableMap<String, CachedDetail> {
        val map = HashMap<String, CachedDetail>()
        if (!cacheFile.exists()) return map
        runCatching {
            val root = JSONObject(cacheFile.readText())
            root.keys().forEach { code ->
                val item = root.optJSONObject(code) ?: return@forEach
                map[code] = CachedDetail(
                    detail = FundDetail(
                        name = item.optString("name"),
                        nav = item.optDouble("nav", 0.0),
                        dayGrowth = item.optDouble("dayGrowth", 0.0),
                        navDate = item.optString("navDate"),
                        type = item.optString("type"),
                        buyStatus = item.optString("buyStatus"),
                        sellStatus = item.optString("sellStatus"),
                        minBuy = item.optDoubleOrNull("minBuy"),
                        maxBuy = item.optDoubleOrNull("maxBuy"),
                        rate = item.optString("rate"),
                        sourceRate = item.optString("sourceRate"),
                        riskLevel = item.optInt("riskLevel", 0),
                        company = item.optString("company"),
                        manager = item.optString("manager"),
                        established = item.optString("established"),
                        scale = item.optString("scale"),
                        trackIndex = item.optString("trackIndex")
                    ),
                    savedAt = item.optLong("savedAt", 0L)
                )
            }
        }
        return map
    }

    private fun writeCache(cache: Map<String, CachedDetail>) {
        runCatching {
            val root = JSONObject()
            cache.forEach { (code, entry) ->
                val d = entry.detail
                root.put(
                    code,
                    JSONObject()
                        .put("name", d.name)
                        .put("nav", d.nav)
                        .put("dayGrowth", d.dayGrowth)
                        .put("navDate", d.navDate)
                        .put("type", d.type)
                        .put("buyStatus", d.buyStatus)
                        .put("sellStatus", d.sellStatus)
                        .put("minBuy", d.minBuy ?: JSONObject.NULL)
                        .put("maxBuy", d.maxBuy ?: JSONObject.NULL)
                        .put("rate", d.rate)
                        .put("sourceRate", d.sourceRate)
                        .put("riskLevel", d.riskLevel)
                        .put("company", d.company)
                        .put("manager", d.manager)
                        .put("established", d.established)
                        .put("scale", d.scale)
                        .put("trackIndex", d.trackIndex)
                        .put("savedAt", entry.savedAt)
                )
            }
            cacheFile.writeText(root.toString())
        }
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (isNull(key)) null else optDouble(key)

    private data class CachedDetail(val detail: FundDetail, val savedAt: Long)

    private companion object {
        const val MAX_FAILURES = 3
        const val TTL_MS = 15 * 60 * 1000L
    }
}
