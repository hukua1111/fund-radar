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
    val error: String? = null,
    /** 「中证指数官网 + 东方财富」这类来源说明，直接显示在 K 线卡片上。 */
    val sourceLabel: String = ""
)

/**
 * K 线读取：先在本地缓存里找，新鲜就直接用；不新鲜只补最后几根。
 *
 * ## 取数顺序（1.13.0 起）
 * 历史段和当日那根分开取，因为两家各有一个硬缺口：
 *
 * - **中证官网**覆盖 000/930/931 全段、能给到指数上市以来全部日线（沪深300
 *   可回溯到 2004 年），还带 PE 与成分股数；但它是 **T-1**，当天收盘后到次日
 *   更新前都拿不到当日那根。
 * - **东财**有当日实时 bar，但 930/931 段只保留约 1426 根，而且 push2 主机
 *   偶发空回复。
 *
 * 两家同一天的 OHLC 实测逐位一致（最大差 0.0000），所以「官网出历史 + 东财补
 * 当日」拼起来不会出现台阶。
 *
 * 深市 399 段官网没有（实测 0 行），仍按「东财全量 → 新浪」走。
 *
 * ## 缓存策略
 * 旧版本缓存里没有 [KlineMeta]，读出来 historySource 为空，正好用来判定
 * 「这是老格式，重建一次」；重建之后短历史的指数也不会再触发全量。
 */
class KlineRepository(context: Context) {

    private val store = KlineStore(context)

    /** 不联网，直接把磁盘上的缓存拿出来（界面先渲染这个）。 */
    fun cached(secid: String): KlineLoad? {
        val entry = store.read(secid) ?: return null
        return KlineLoad(
            series = entry.series,
            fromCache = true,
            fetchedAt = entry.fetchedAt,
            sourceLabel = entry.series.meta.label
        )
    }

    suspend fun load(secid: String, force: Boolean = false): KlineLoad = withContext(Dispatchers.IO) {
        val entry = store.read(secid)
        val now = System.currentTimeMillis()

        if (!force && entry != null && !isStale(entry.fetchedAt, now)) {
            return@withContext KlineLoad(
                series = entry.series,
                fromCache = true,
                fetchedAt = entry.fetchedAt,
                sourceLabel = entry.series.meta.label
            )
        }

        val market = secid.substringBefore('.').toIntOrNull() ?: 1
        val code = secid.substringAfter('.', "")
        // 老缓存（没有来源标记）重建一次，之后只补尾巴。
        val rebuild = entry == null || entry.series.meta.historySource.isBlank()
        val bars = if (rebuild) FULL_BARS else TAIL_BARS + 30

        // 1) 历史段：官网优先，失败回落东财、新浪。
        val history = (if (CsIndexApi.supports(market)) CsIndexApi.fetchDaily(code, bars) else null)
            ?: EastMoneyApi.fetchIndexKline(secid, bars)
            ?: SinaKlineApi.fetchIndexKline(secid, bars)

        if (history == null) {
            return@withContext if (entry != null) {
                KlineLoad(
                    series = entry.series,
                    fromCache = true,
                    fetchedAt = entry.fetchedAt,
                    requested = true,
                    error = "行情接口暂时没有响应，显示本地缓存",
                    sourceLabel = entry.series.meta.label
                )
            } else {
                KlineLoad(null, fromCache = false, requested = true, error = "行情加载失败，请检查网络")
            }
        }

        // 2) 当日那根：官网是 T-1，补一次东财（含盘中实时 bar）。
        //    深市本来就走东财，历史段里已经带了当日，不必再请求一次。
        val fromCsIndex = history.meta.historySource == CsIndexApi.LABEL
        val tail = if (fromCsIndex) EastMoneyApi.fetchIndexKline(secid, TAIL_BARS) else null

        val fresh = mergeCandles(history.candles, tail?.candles.orEmpty(), MAX_BARS).first
        val (merged, added) = mergeCandles(entry?.series?.candles.orEmpty(), fresh, MAX_BARS)
        val series = KlineSeries(
            code = history.code.ifBlank { entry?.series?.code.orEmpty() },
            name = history.name.ifBlank { entry?.series?.name.orEmpty() },
            source = KlineSource.EXCHANGE,
            candles = merged,
            meta = history.meta.copy(
                tailSource = when {
                    // 官网历史 + 东财当日补成功
                    fromCsIndex && tail != null -> EastMoneyApi.LABEL
                    // 官网历史，东财没补上：只有到 T-1 的数据
                    fromCsIndex -> history.meta.tailSource
                    // 历史本身就是东财整段拉的，当日已经在里面
                    else -> history.meta.historySource
                },
                asOf = merged.lastOrNull()?.date ?: history.meta.asOf
            )
        )
        store.write(secid, series, now)

        KlineLoad(
            series = series,
            fromCache = false,
            added = added + (if (entry == null) merged.size else 0),
            fetchedAt = now,
            requested = true,
            sourceLabel = series.meta.label
        )
    }

    private companion object {
        /**
         * 首次拉取的历史长度。
         *
         * 1.12.0 及以前是 250，K 线滑到头就没了；官网一次给 800 根也只要
         * 200ms 左右，索性把视窗撑满（界面最长就是 800 根）。
         */
        const val FULL_BARS = 800

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
