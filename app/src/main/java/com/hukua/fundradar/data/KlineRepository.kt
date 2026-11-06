package com.hukua.fundradar.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/** 一次 K 线加载的结果，顺带说明数据是新拉的还是本地缓存。 */
data class KlineLoad(
    val series: KlineSeries?,
    val fromCache: Boolean,
    /** 这次实际新增的交易日根数；0 表示上游没有新数据。 */
    val added: Int = 0,
    /** 本地缓存写入/最近一次成功拉取的时间。 */
    val fetchedAt: Long = 0L,
    /** 本次是否真的发了网络请求。 */
    val requested: Boolean = false,
    val error: String? = null
)

/**
 * K 线读取：先在本地缓存里找，新鲜就直接用；不新鲜只补最后几根。
 *
 * 上游 `kline/get` 一次要 250 根，对一个指数每天开关十几次就是十几次整段
 * 下载。现在只有第一次（或本地没有）才拉全量，之后每次最多 5 根，按日期
 * 合并进缓存，既省流量也避开接口的频率限制。
 */
class KlineRepository(context: Context) {

    private val store = KlineStore(context)

    /** 不联网，直接把磁盘上的缓存拿出来（界面先渲染这个）。 */
    fun cached(secid: String): KlineLoad? {
        val entry = store.read(secid) ?: return null
        return KlineLoad(entry.series, fromCache = true, fetchedAt = entry.fetchedAt)
    }

    suspend fun load(secid: String, force: Boolean = false): KlineLoad = withContext(Dispatchers.IO) {
        val entry = store.read(secid)
        val now = System.currentTimeMillis()

        if (!force && entry != null && !isStale(entry.fetchedAt, now)) {
            return@withContext KlineLoad(
                series = entry.series,
                fromCache = true,
                fetchedAt = entry.fetchedAt
            )
        }

        // 本地没有（或者残缺）才拉全量，其余只补尾巴。
        val limit = if (entry == null || entry.series.candles.size < TAIL_BARS * 4) {
            FULL_BARS
        } else {
            TAIL_BARS
        }

        val fresh = runCatching { EastMoneyApi.fetchIndexKline(secid, limit) }.getOrNull()
        if (fresh == null) {
            return@withContext if (entry != null) {
                KlineLoad(
                    series = entry.series,
                    fromCache = true,
                    fetchedAt = entry.fetchedAt,
                    requested = true,
                    error = "行情接口暂时没有响应，显示本地缓存"
                )
            } else {
                KlineLoad(null, fromCache = false, requested = true, error = "行情加载失败，请检查网络")
            }
        }

        val base = entry?.series
        val (merged, added) = mergeCandles(base?.candles.orEmpty(), fresh.candles)
        val series = KlineSeries(
            code = fresh.code.ifBlank { base?.code.orEmpty() },
            name = fresh.name.ifBlank { base?.name.orEmpty() },
            source = KlineSource.EXCHANGE,
            candles = merged
        )
        store.write(secid, series, now)

        KlineLoad(
            series = series,
            fromCache = false,
            added = added,
            fetchedAt = now,
            requested = true
        )
    }

    private companion object {
        /** 首次拉取的历史长度。 */
        const val FULL_BARS = 250

        /** 增量更新时只补最后几根。 */
        const val TAIL_BARS = 5

        /** 盘中两分钟内不重复请求；收盘后半天内都算新鲜。 */
        const val TRADING_TTL_MS = 2 * 60 * 1000L
        const val IDLE_TTL_MS = 12 * 60 * 60 * 1000L

        fun isStale(fetchedAt: Long, now: Long): Boolean {
            if (fetchedAt <= 0L) return true
            val age = now - fetchedAt
            if (age < 0) return true
            return age > if (isTradingTime()) TRADING_TTL_MS else IDLE_TTL_MS
        }
    }
}

/** 是否在 A 股连续竞价时段（含集合竞价前后几分钟，周一到周五）。 */
fun isTradingTime(calendar: Calendar = Calendar.getInstance()): Boolean {
    val day = calendar.get(Calendar.DAY_OF_WEEK)
    if (day == Calendar.SATURDAY || day == Calendar.SUNDAY) return false
    val minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    return minutes in (9 * 60 + 15)..(15 * 60 + 5)
}
