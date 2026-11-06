package com.hukua.fundradar.data

import android.content.Context
import org.json.JSONObject

/**
 * 「基金代码 → 持仓」的本地存储。
 *
 * 数据量很小（几十只基金、每只几十条记录），所以整张表当成一个 JSON 存在
 * `fund_radar` 里，读写都在内存副本上做，写法简单也不会出现半条记录。
 */
class HoldingStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("fund_radar", Context.MODE_PRIVATE)

    fun all(): Map<String, Holding> = read().toSortedMap()

    fun get(code: String): Holding? = read()[code]

    fun save(code: String, holding: Holding) {
        if (code.isBlank()) return
        val current = read().toMutableMap()
        if (holding.isEmpty && holding.trades.isEmpty()) {
            current.remove(code)
        } else {
            current[code] = holding
        }
        write(current)
    }

    /** 记录一笔买入 / 卖出，返回更新后的持仓。 */
    fun apply(code: String, trade: Trade): Holding {
        val before = read()[code] ?: Holding()
        val after = before.applied(trade)
        save(code, after)
        return after
    }

    fun remove(code: String) {
        val current = read().toMutableMap()
        if (current.remove(code) != null) write(current)
    }

    private fun read(): Map<String, Holding> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val result = LinkedHashMap<String, Holding>()
        root.keys().forEach { code ->
            root.optJSONObject(code)?.let { result[code] = Holding.fromJson(it) }
        }
        return result
    }

    private fun write(holdings: Map<String, Holding>) {
        val root = JSONObject()
        holdings.forEach { (code, holding) -> root.put(code, holding.toJson()) }
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    private companion object {
        const val KEY = "holdings_v1"
    }
}
