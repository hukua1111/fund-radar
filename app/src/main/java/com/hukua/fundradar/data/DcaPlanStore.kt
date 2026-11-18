package com.hukua.fundradar.data

import android.content.Context
import org.json.JSONObject

/**
 * 定投计划的本机存储：`{基金代码: {amount, day, start, target}}`。
 *
 * 和持仓一样摊在一张 JSON 表里 —— 每只基金一条，几十只的规模，整表读写最省事，
 * 也不会出现半条记录。数据放在 `fund_radar` 这个 prefs 里，覆盖安装不丢。
 */
class DcaPlanStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("fund_radar", Context.MODE_PRIVATE)

    fun all(): Map<String, DcaPlan> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val result = LinkedHashMap<String, DcaPlan>()
        root.keys().forEach { code ->
            root.optJSONObject(code)?.let { result[code] = DcaPlan.fromJson(it) }
        }
        return result
    }

    fun get(code: String): DcaPlan? = all()[code]

    fun save(code: String, plan: DcaPlan) {
        if (code.isBlank()) return
        val current = all().toMutableMap()
        if (!plan.enabled) {
            current.remove(code)
        } else {
            current[code] = plan
        }
        write(current)
    }

    fun remove(code: String) {
        val current = all().toMutableMap()
        if (current.remove(code) != null) write(current)
    }

    private fun write(plans: Map<String, DcaPlan>) {
        val root = JSONObject()
        plans.forEach { (code, plan) -> root.put(code, plan.toJson()) }
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    private companion object {
        const val KEY = "dca_plans_v1"
    }
}
