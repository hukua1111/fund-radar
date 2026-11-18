package com.hukua.fundradar.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「定投」页的观察清单：存的是**指数**，不是基金。
 *
 * 单独一份存储（`dca_indices_v1`），和持仓、限购的基金清单互不共享 —— 定投关心的是
 * 「这个行业现在贵不贵、趋势坏没坏」，先看指数，再决定用 ETF 还是场外指基去买。
 */
class DcaIndexStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("fund_radar", Context.MODE_PRIVATE)

    fun entries(): List<IndexEntry> {
        val raw = prefs.getString(KEY, "").orEmpty()
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

    fun contains(secid: String): Boolean = entries().any { it.secid == secid }

    /** 加到末尾；已经在了就返回 false。 */
    fun add(entry: IndexEntry): Boolean {
        val current = entries()
        if (current.any { it.secid == entry.secid }) return false
        write(current + entry)
        return true
    }

    fun remove(secid: String) {
        val current = entries()
        val next = current.filterNot { it.secid == secid }
        if (next.size != current.size) write(next)
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
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "dca_indices_v1"
    }
}
