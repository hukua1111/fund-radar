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

/**
 * Strips the words that differ between a fund's 跟踪标的 and the index quote
 * name: `中证煤炭等权指数` and `煤炭等权` are the same index.
 */
fun normalizeIndexName(raw: String): String {
    var text = raw.replace(" ", "").replace("　", "")
    text = text.replace(Regex("[（(][^）)]*[）)]"), "")
    var changed = true
    var guard = 0
    while (changed && guard++ < 8) {
        changed = false
        for (prefix in PREFIXES) {
            if (text.startsWith(prefix) && text.length > prefix.length + 1) {
                text = text.removePrefix(prefix)
                changed = true
                break
            }
        }
        for (suffix in SUFFIXES) {
            if (text.endsWith(suffix) && text.length > suffix.length + 1) {
                text = text.removeSuffix(suffix)
                changed = true
                break
            }
        }
    }
    return text
}

/**
 * Matches a 跟踪标的 name against the index catalogue. Only exact matches or
 * full containment count, so we never silently chart the wrong index.
 */
fun resolveIndex(trackedName: String, catalog: List<IndexEntry>): IndexEntry? {
    val target = normalizeIndexName(trackedName)
    if (target.isBlank()) return null

    var best: IndexEntry? = null
    var bestScore = 0
    for (entry in catalog) {
        val candidate = normalizeIndexName(entry.name)
        if (candidate.isBlank()) continue
        val score = when {
            candidate == target -> 100
            candidate.length >= 3 && target.contains(candidate) -> 60 + candidate.length
            target.length >= 3 && candidate.contains(target) -> 50 + target.length
            else -> 0
        }
        if (score == 0) continue
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
