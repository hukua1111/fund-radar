package com.hukua.fundradar.data

import android.content.Context
import org.json.JSONObject

/**
 * 基金 → 指数 的手动指定。
 *
 * 基金页只给出「跟踪标的」的一段中文名，名字和行情端目录里的简称未必一致
 * （例如「中证电网设备主题指数」对不上「电网设备」），自动匹配就会落空。
 * 用户在基金详情里亲手挑一个指数后记在这里，[IndexRepository] 解析时会优先
 * 采用，优先级高于按名字匹配。
 */
class TrackOverrideStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("fund_radar", Context.MODE_PRIVATE)

    /** fundCode → 指定指数，按写入顺序返回。 */
    fun all(): Map<String, IndexEntry> {
        val raw = prefs.getString(KEY_TRACK_OVERRIDES, "").orEmpty()
        if (raw.isBlank()) return emptyMap()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val result = LinkedHashMap<String, IndexEntry>()
        json.keys().forEach { fundCode ->
            val item = json.optJSONObject(fundCode) ?: return@forEach
            val code = item.optString("code")
            val name = item.optString("name")
            if (code.isBlank() || name.isBlank()) return@forEach
            result[fundCode] = IndexEntry(code, item.optInt("market", 0), name)
        }
        return result
    }

    fun get(fundCode: String): IndexEntry? = all()[fundCode]

    fun set(fundCode: String, entry: IndexEntry) {
        val current = all().toMutableMap()
        current[fundCode] = entry
        write(current)
    }

    /** 回到「按基金名自动识别」。 */
    fun clear(fundCode: String) {
        val current = all().toMutableMap()
        if (current.remove(fundCode) != null) write(current)
    }

    /** 自选里的基金被删掉后，顺手把它的指定也清掉。 */
    fun retain(fundCodes: Collection<String>) {
        val keep = fundCodes.toSet()
        val current = all()
        if (current.keys.all { it in keep }) return
        write(current.filterKeys { it in keep })
    }

    private fun write(items: Map<String, IndexEntry>) {
        val json = JSONObject()
        items.forEach { (fundCode, entry) ->
            json.put(
                fundCode,
                JSONObject()
                    .put("code", entry.code)
                    .put("market", entry.market)
                    .put("name", entry.name)
            )
        }
        prefs.edit().putString(KEY_TRACK_OVERRIDES, json.toString()).apply()
    }

    private companion object {
        const val KEY_TRACK_OVERRIDES = "track_overrides"
    }
}
