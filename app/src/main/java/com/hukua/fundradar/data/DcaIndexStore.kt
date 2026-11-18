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
            // 旧版本给 930/931 这类中证代码存过 0 号市场（当时只按「5/6 开头是沪市」
            // 判断），那种 secid 换不来行情。读出来时按号段纠正，避免清单里的指数
            // 一直是「日线暂不可用」。
            val saved = item.optInt("market", 0)
            val market = indexMarketOf(code) ?: saved
            result += IndexEntry(code, market, name)
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

    /**
     * 用 K 线返回的真实名称替换占位名。
     *
     * 只输代码添加时（搜索服务抖了），名称先放的是代码本身；日线拉回来后
     * 东财会带着指数名，这里把它写回清单，下次打开就不用再猜。
     */
    fun rename(secid: String, name: String) {
        val clean = name.trim()
        if (secid.isBlank() || clean.isBlank() || clean == "-") return
        val current = entries()
        if (current.none { it.secid == secid && it.name != clean }) return
        write(current.map { if (it.secid == secid) it.copy(name = clean) else it })
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
