package com.hukua.fundradar.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Indices the user added by hand on the 指数 tab.
 *
 * They live next to the indices that are discovered automatically from the
 * watchlist: the fund driven ones can never be removed by hand (they follow the
 * fund), while these stay until the user removes them.
 */
class IndexWatchStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("fund_radar", Context.MODE_PRIVATE)

    fun entries(): List<IndexEntry> {
        val raw = prefs.getString(KEY_INDICES, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val result = ArrayList<IndexEntry>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val code = item.optString("code")
            val name = item.optString("name")
            if (code.isBlank() || name.isBlank()) continue
            result += IndexEntry(code, item.optInt("market", 0), name)
        }
        return result
    }

    fun secids(): Set<String> = entries().map { it.secid }.toSet()

    fun contains(secid: String): Boolean = entries().any { it.secid == secid }

    /** Adds an index at the end of the list; false when it is already there. */
    fun add(entry: IndexEntry): Boolean {
        val current = entries()
        if (current.any { it.secid == entry.secid }) return false
        write(current + entry)
        return true
    }

    fun remove(secid: String) {
        write(entries().filterNot { it.secid == secid })
    }

    private fun write(items: List<IndexEntry>) {
        val array = JSONArray()
        items.forEach { entry ->
            array.put(
                JSONObject()
                    .put("code", entry.code)
                    .put("market", entry.market)
                    .put("name", entry.name)
            )
        }
        prefs.edit().putString(KEY_INDICES, array.toString()).apply()
    }

    private companion object {
        const val KEY_INDICES = "manual_indices"
    }
}
