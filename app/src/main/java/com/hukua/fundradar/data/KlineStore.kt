package com.hukua.fundradar.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 本地 K 线缓存：一只指数一个 JSON 文件。 */
class KlineStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "kline")

    data class Entry(val series: KlineSeries, val fetchedAt: Long)

    fun read(secid: String): Entry? {
        val file = fileOf(secid)
        if (!file.exists()) return null
        return runCatching {
            val root = JSONObject(file.readText())
            val rows = root.optJSONArray("candles") ?: return null
            val candles = ArrayList<Candle>(rows.length())
            for (i in 0 until rows.length()) {
                val row = rows.optJSONArray(i) ?: continue
                if (row.length() < 5) continue
                val date = row.optString(0)
                if (date.isBlank()) continue
                candles += Candle(
                    date = date,
                    open = row.optDouble(1, 0.0),
                    close = row.optDouble(2, 0.0),
                    high = row.optDouble(3, 0.0),
                    low = row.optDouble(4, 0.0),
                    volume = row.optDouble(5, 0.0)
                )
            }
            if (candles.isEmpty()) return null
            val metaJson = root.optJSONObject("meta")
            Entry(
                series = KlineSeries(
                    code = root.optString("code"),
                    name = root.optString("name"),
                    source = KlineSource.EXCHANGE,
                    candles = candles,
                    meta = KlineMeta(
                        // 老缓存没有 meta 对象，全部走默认值 —— KlineRepository
                        // 靠 historySource 为空来判断「需要重建一次」。
                        pe = metaJson?.optDouble("pe")?.takeUnless { it.isNaN() },
                        constituents = metaJson?.optInt("constituents", 0)?.takeIf { it > 0 },
                        historySource = metaJson?.optString("historySource").orEmpty(),
                        tailSource = metaJson?.optString("tailSource").orEmpty(),
                        asOf = metaJson?.optString("asOf").orEmpty()
                    )
                ),
                fetchedAt = root.optLong("fetchedAt", 0L)
            )
        }.getOrNull()
    }

    fun write(secid: String, series: KlineSeries, fetchedAt: Long) {
        runCatching {
            val rows = JSONArray()
            series.candles.forEach { candle ->
                rows.put(
                    JSONArray()
                        .put(candle.date)
                        .put(candle.open)
                        .put(candle.close)
                        .put(candle.high)
                        .put(candle.low)
                        .put(candle.volume)
                )
            }
            val metaJson = JSONObject()
                .put("pe", series.meta.pe ?: JSONObject.NULL)
                .put("constituents", series.meta.constituents ?: JSONObject.NULL)
                .put("historySource", series.meta.historySource)
                .put("tailSource", series.meta.tailSource)
                .put("asOf", series.meta.asOf)

            val root = JSONObject()
                .put("code", series.code)
                .put("name", series.name)
                .put("fetchedAt", fetchedAt)
                .put("meta", metaJson)
                .put("candles", rows)

            if (!dir.exists()) dir.mkdirs()
            val target = fileOf(secid)
            val temp = File(dir, target.name + ".tmp")
            temp.writeText(root.toString())
            if (!temp.renameTo(target)) {
                target.writeText(root.toString())
                temp.delete()
            }
        }
    }

    fun clear(secid: String) {
        runCatching { fileOf(secid).delete() }
    }

    private fun fileOf(secid: String): File =
        File(dir, secid.replace('.', '_').replace('/', '_') + ".json")
}

/**
 * 把增量回来的几根接到缓存上：按日期去重（新数据覆盖旧的），保持升序，
 * 最多留 [MAX_BARS] 根。返回合并结果与真正新增的根数。
 */
fun mergeCandles(
    cached: List<Candle>,
    fresh: List<Candle>,
    maxBars: Int = MAX_BARS
): Pair<List<Candle>, Int> {
    if (fresh.isEmpty()) return cached to 0
    val byDate = LinkedHashMap<String, Candle>(cached.size + fresh.size)
    cached.forEach { byDate[it.date] = it }
    val known = byDate.keys.toHashSet()
    var added = 0
    fresh.forEach { candle ->
        if (candle.date !in known) added++
        byDate[candle.date] = candle
    }
    val merged = byDate.values.sortedBy { it.date }
    return if (merged.size > maxBars) merged.takeLast(maxBars) to added else merged to added
}

/**
 * 单只指数最多保留多少根日 K。
 *
 * 1.13.0 从 500 提到 1200（约五年）：中证官网一次能给到上市以来全部日线，
 * 只留两年太浪费；单只文件约 60KB，十只指数 600KB，可以接受。
 */
const val MAX_BARS = 1200
