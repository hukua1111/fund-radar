package com.hukua.fundradar.data

import android.content.Context
import org.json.JSONObject

/**
 * 定投计划的本机存储：`{指数 secid: {amount, freq, dow, dom, startDate, target, note}}`。
 *
 * 键是 `1.000300` 这样的 secid，不是基金代码 —— 定投页观察的是指数本身。
 * 摊在一张 JSON 表里，几十条以内，整表读写最省事，也不会出现半条记录。
 * 数据放在 `fund_radar` 这个 prefs 里，覆盖安装不丢。
 */
class DcaPlanStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("fund_radar", Context.MODE_PRIVATE)

    fun all(): Map<String, DcaPlan> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val result = LinkedHashMap<String, DcaPlan>()
        root.keys().forEach { secid ->
            root.optJSONObject(secid)?.let { result[secid] = DcaPlan.fromJson(it) }
        }
        return result
    }

    fun get(secid: String): DcaPlan? = all()[secid]

    fun save(secid: String, plan: DcaPlan) {
        if (secid.isBlank()) return
        val current = all().toMutableMap()
        if (!plan.enabled) {
            current.remove(secid)
        } else {
            current[secid] = plan
        }
        write(current)
    }

    fun remove(secid: String) {
        val current = all().toMutableMap()
        if (current.remove(secid) != null) write(current)
    }

    private fun write(plans: Map<String, DcaPlan>) {
        val root = JSONObject()
        plans.forEach { (secid, plan) -> root.put(secid, plan.toJson()) }
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    private companion object {
        const val KEY = "dca_plans_v1"
    }
}
