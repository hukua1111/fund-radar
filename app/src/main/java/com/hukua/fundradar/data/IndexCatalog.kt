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

/** An index that at least one watchlist fund tracks. */
data class TrackedIndex(
    val entry: IndexEntry,
    val trackedName: String,
    val fundCodes: List<String> = emptyList()
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

        var score = when {
            candidate.core == target.core -> 100
            else -> containmentScore(target.core, candidate.core)
        }
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
    return if (bestScore >= 60) best else null
}

private fun containmentScore(target: String, candidate: String): Int {
    val shorter = if (candidate.length <= target.length) candidate else target
    val longer = if (candidate.length <= target.length) target else candidate
    if (shorter.length < 3 || !longer.contains(shorter)) return 0
    // 长度差太多的不是同一个指数：新能源 ≠ 新能源汽车
    if (shorter.length * 10 <= longer.length * 6) return 0
    return if (candidate.length < target.length) 60 + candidate.length else 50 + target.length
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

    private var memory: List<IndexEntry>? = null

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

    private fun read(file: File): Pair<Long, List<IndexEntry>>? = runCatching {
        val json = JSONObject(file.readText(Charsets.UTF_8))
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
            .put("savedAt", System.currentTimeMillis())
            .put("items", array)
        file.writeText(json.toString(), Charsets.UTF_8)
    }
}
