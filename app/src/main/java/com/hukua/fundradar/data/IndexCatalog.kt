package com.hukua.fundradar.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One quotable index from the eastmoney index catalogue. */
data class IndexEntry(
    val code: String,
    val market: Int,
    val name: String
) {
    /** Quote id used by the kline endpoint, e.g. `0.399998`. */
    val secid: String get() = "$market.$code"

    val displayName: String get() = name
}

/** An index that at least one watchlist fund tracks, or that the user added. */
data class TrackedIndex(
    val entry: IndexEntry,
    val trackedName: String,
    val fundCodes: List<String> = emptyList(),
    /** True when the row was added by hand on the 指数 tab. */
    val manual: Boolean = false
)

private val PREFIXES = listOf("中证全指", "中证", "国证", "深证", "上证", "沪深")
private val SUFFIXES = listOf("全收益", "净收益", "指数", "R")

/** A name split into its publishing family (`中证`) and the rest (`煤炭等权`). */
data class IndexNameParts(val prefix: String, val core: String)

/**
 * Splits the words that differ between a fund's 跟踪标的 and the index quote
 * name: `中证煤炭等权指数` and `煤炭等权` are the same index.
 *
 * The family prefix is kept separately on purpose — 中证军工 and 国证军工 are
 * different indices and must never be swapped for each other.
 */
fun splitIndexName(raw: String): IndexNameParts {
    var text = raw.replace(" ", "").replace("　", "")
    text = text.replace(Regex("[（(][^）)]*[）)]"), "")

    var prefix = ""
    for (candidate in PREFIXES) {
        if (text.startsWith(candidate) && text.length > candidate.length + 1) {
            prefix = candidate
            text = text.removePrefix(candidate)
            break
        }
    }

    var changed = true
    var guard = 0
    while (changed && guard++ < 8) {
        changed = false
        for (suffix in SUFFIXES) {
            if (text.endsWith(suffix) && text.length > suffix.length + 1) {
                text = text.removeSuffix(suffix)
                changed = true
                break
            }
        }
    }
    return IndexNameParts(prefix, text)
}

/** Family-free form of a name, used for display and matching. */
fun normalizeIndexName(raw: String): String = splitIndexName(raw).core

/**
 * Matches a 跟踪标的 name against the index catalogue. Only exact matches or
 * near-equal containment count, so we never silently chart the wrong index.
 */
fun resolveIndex(trackedName: String, catalog: List<IndexEntry>): IndexEntry? {
    val target = splitIndexName(trackedName)
    if (target.core.isBlank()) return null

    var best: IndexEntry? = null
    var bestScore = 0
    for (entry in catalog) {
        val candidate = splitIndexName(entry.name)
        if (candidate.core.isBlank()) continue
        // 中证 / 国证 / 沪深 是不同系列的指数，名字撞了也不能互相顶替
        if (candidate.prefix.isNotEmpty() && candidate.prefix != target.prefix) continue

        var score = matchScore(target.core, candidate.core)
        if (score == 0) continue
        if (candidate.prefix.isNotEmpty() && candidate.prefix == target.prefix) score += 5

        val better = when {
            score > bestScore -> true
            score < bestScore -> false
            else -> preferEntry(entry, best)
        }
        if (better) {
            best = entry
            bestScore = score
        }
    }
    return if (bestScore >= MIN_SCORE) best else null
}

/**
 * Offline fallback for the "添加指数" search box: matches the local catalogue
 * by exact / prefix code and by name, ranking the same publishing family first.
 */
fun searchCatalog(
    query: String,
    catalog: List<IndexEntry>,
    limit: Int = 25
): List<IndexEntry> {
    val keyword = query.trim().replace(" ", "")
    if (keyword.isEmpty()) return emptyList()
    val target = splitIndexName(keyword)
    if (target.core.isEmpty()) return emptyList()

    val ranked = catalog.mapNotNull { entry ->
        val candidate = splitIndexName(entry.name)
        if (candidate.core.isEmpty()) return@mapNotNull null
        val base = when {
            entry.code == keyword -> 100
            keyword.length >= 3 && entry.code.startsWith(keyword) -> 90
            candidate.core == target.core -> 80
            target.core.length >= 2 && candidate.core.contains(target.core) -> 60
            else -> return@mapNotNull null
        }
        val bonus = if (target.prefix.isNotEmpty() && candidate.prefix == target.prefix) 5 else 0
        entry to (base + bonus)
    }.sortedWith(
        compareByDescending<Pair<IndexEntry, Int>> { it.second }
            .thenBy { it.first.code }
    )

    return ranked.take(limit).map { it.first }
}

private const val MIN_SCORE = 45

private fun matchScore(target: String, candidate: String): Int {
    if (candidate == target) return 100

    val shorter = if (candidate.length <= target.length) candidate else target
    val longer = if (candidate.length <= target.length) target else candidate
    if (shorter.length >= 3 && longer.contains(shorter) && shorter.length * 10 > longer.length * 6) {
        return if (candidate.length < target.length) 60 + candidate.length else 50 + target.length
    }

    // 目录里用简称：国证半导体芯片指数 → 国证芯片
    if (candidate.length >= 2 &&
        target.length > candidate.length &&
        target.endsWith(candidate) &&
        target.length - candidate.length <= 4
    ) {
        return 45
    }
    return 0
}

private fun preferEntry(candidate: IndexEntry, current: IndexEntry?): Boolean {
    if (current == null) return true
    if (candidate.market != current.market) return candidate.market == 0
    if (candidate.name.length != current.name.length) return candidate.name.length < current.name.length
    return candidate.code < current.code
}

/**
 * Local copy of the index catalogue (about 1k rows, 45 KB). Refreshed weekly —
 * the list of indices barely changes.
 */
object IndexCatalog {

    private const val FILE_NAME = "index_catalog.json"
    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * Bumped when the set of indices we download changes. A cache written by an
     * older build is treated as stale, so 1.3.1 picks up 中证指数公司's own codes
     * (930xxx / 931xxx) on first launch instead of a week later.
     */
    private const val CACHE_VERSION = 2

    /** 跟踪标的名 → `secid|名称`，一个月内不再问网络。 */
    private const val RESOLVE_FILE = "index_name_resolve.json"
    private const val RESOLVE_TTL_MS = 30L * 24 * 60 * 60 * 1000

    private var memory: List<IndexEntry>? = null
    private var resolveCache: MutableMap<String, String>? = null
    private val resolveMisses = HashSet<String>()

    suspend fun load(context: Context, force: Boolean = false): List<IndexEntry> {
        if (!force) {
            memory?.let { return it }
        }
        val file = File(context.applicationContext.filesDir, FILE_NAME)

        if (!force && file.exists()) {
            val cached = read(file)
            if (cached != null && System.currentTimeMillis() - cached.first < TTL_MS) {
                memory = cached.second
                return cached.second
            }
        }

        val fresh = runCatching { EastMoneyApi.fetchIndexCatalog() }.getOrNull()
        if (!fresh.isNullOrEmpty()) {
            memory = fresh
            runCatching { write(file, fresh) }
            return fresh
        }

        val cached = if (file.exists()) read(file)?.second else null
        if (!cached.isNullOrEmpty()) {
            memory = cached
            return cached
        }
        return emptyList()
    }

    /**
     * Turns a fund's 跟踪标的 into a quotable index, for names the local
     * catalogue does not contain.
     *
     * The catalogue only knows the indices 行情 lists for 沪深; 中证指数公司's own
     * codes (930xxx / 931xxx / H30xxx) live under market 2 and are reached
     * through the suggest API instead. Hits and misses are cached on disk so a
     * refresh does not hit the network again, and a failed lookup is remembered
     * for the rest of the session so an offline refresh stays fast.
     */
    suspend fun resolveByName(context: Context, trackedName: String): IndexEntry? {
        val key = normalizeIndexName(trackedName)
        if (key.isBlank()) return null

        val cache = loadResolveCache(context)
        cache[key]?.let { return parseCached(it) }
        if (key in resolveMisses) return null

        val candidates = runCatching { EastMoneyApi.searchIndices(trackedName) }
            .getOrDefault(emptyList())
        if (candidates.isEmpty()) {
            resolveMisses += key
            return null
        }

        // 同一个词可能同时命中 指数 / 板块；用和本地目录一样的打分逻辑挑最像的
        val best = resolveIndex(trackedName, candidates) ?: candidates.firstOrNull()
        if (best == null) {
            resolveMisses += key
            return null
        }

        cache[key] = "${best.secid}|${best.name}"
        runCatching { saveResolveCache(context, cache) }
        return best
    }

    private fun parseCached(value: String): IndexEntry? {
        val secid = value.substringBefore('|')
        val name = value.substringAfter('|', "")
        val market = secid.substringBefore('.').toIntOrNull() ?: return null
        val code = secid.substringAfter('.')
        if (code.isBlank() || name.isBlank()) return null
        return IndexEntry(code, market, name)
    }

    private fun loadResolveCache(context: Context): MutableMap<String, String> {
        resolveCache?.let { return it }
        val map = HashMap<String, String>()
        val file = File(context.applicationContext.filesDir, RESOLVE_FILE)
        runCatching {
            if (!file.exists()) return@runCatching
            val json = JSONObject(file.readText(Charsets.UTF_8))
            if (System.currentTimeMillis() - json.optLong("savedAt", 0L) > RESOLVE_TTL_MS) return@runCatching
            val items = json.optJSONObject("items") ?: return@runCatching
            items.keys().forEach { name ->
                val value = items.optString(name)
                if (value.isNotBlank()) map[name] = value
            }
        }
        resolveCache = map
        return map
    }

    private fun saveResolveCache(context: Context, map: Map<String, String>) {
        val items = JSONObject()
        map.forEach { (name, value) -> items.put(name, value) }
        val json = JSONObject()
            .put("savedAt", System.currentTimeMillis())
            .put("items", items)
        File(context.applicationContext.filesDir, RESOLVE_FILE)
            .writeText(json.toString(), Charsets.UTF_8)
    }

    private fun read(file: File): Pair<Long, List<IndexEntry>>? = runCatching {
        val json = JSONObject(file.readText(Charsets.UTF_8))
        if (json.optInt("version", 1) != CACHE_VERSION) return@runCatching null
        val savedAt = json.optLong("savedAt", 0L)
        val array = json.optJSONArray("items") ?: JSONArray()
        val items = ArrayList<IndexEntry>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val code = item.optString("code")
            val name = item.optString("name")
            if (code.isBlank() || name.isBlank()) continue
            items += IndexEntry(code, item.optInt("market", 0), name)
        }
        savedAt to items
    }.getOrNull()

    private fun write(file: File, items: List<IndexEntry>) {
        val array = JSONArray()
        items.forEach { entry ->
            array.put(
                JSONObject()
                    .put("code", entry.code)
                    .put("market", entry.market)
                    .put("name", entry.name)
            )
        }
        val json = JSONObject()
            .put("version", CACHE_VERSION)
            .put("savedAt", System.currentTimeMillis())
            .put("items", array)
        file.writeText(json.toString(), Charsets.UTF_8)
    }
}
