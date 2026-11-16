package com.hukua.fundradar.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hukua.fundradar.data.Fund
import com.hukua.fundradar.data.FundRepository
import com.hukua.fundradar.data.FundScope
import com.hukua.fundradar.data.HoldingStore
import com.hukua.fundradar.data.IndexRepository
import com.hukua.fundradar.data.KlineRepository
import com.hukua.fundradar.data.KlineSeries
import com.hukua.fundradar.data.SignalAction
import com.hukua.fundradar.data.SignalEngine
import com.hukua.fundradar.data.SignalReport
import com.hukua.fundradar.data.TrackedIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 信号页的一行：一只持仓清单里的基金 + 它跟踪指数的信号报告。 */
data class SignalRow(
    val fund: Fund,
    /** 填过份额 = 持有，显示买卖两类信号；未持有只显示买入。 */
    val held: Boolean,
    /** 持有市值，用来给持有段排序。 */
    val holdingValue: Double,
    /** 解析出来的跟踪指数名；没认出来时为空。 */
    val indexName: String,
    val report: SignalReport?,
    /** 没有报告时的原因（未识别指数 / K线加载失败）。 */
    val note: String = ""
)

data class SignalUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val rows: List<SignalRow> = emptyList(),
    val updatedAt: Long = 0L,
    val error: String? = null,
    val watchlistEmpty: Boolean = false
) {
    val heldRows: List<SignalRow>
        get() = rows.filter { it.held }.sortedByDescending { it.holdingValue }
    val unheldRows: List<SignalRow> get() = rows.filter { !it.held }

    val buyCount: Int get() = rows.count { it.report?.hasBuy == true }
    val sellCount: Int get() = rows.count { it.held && it.report?.hasSell == true }
}

/**
 * 「信号」页：持仓清单里每只基金 → 跟踪指数 → 已收盘日线 → 本地规则信号。
 *
 * 与 AI 判断完全独立：不读 AI 配置、不发模型请求，[SignalEngine] 纯本地计算。
 * K 线走 [KlineRepository] 的缓存 + 增量链路，多只基金跟踪同一指数时只算一次。
 */
class SignalViewModel(context: Context) : ViewModel() {

    private val appContext = context.applicationContext
    private val fundRepository = FundRepository(appContext, FundScope.PORTFOLIO)
    private val indexRepository = IndexRepository(appContext)
    private val klineRepository = KlineRepository(appContext)
    private val holdingStore = HoldingStore(appContext)

    private val _state = MutableStateFlow(SignalUiState())
    val state: StateFlow<SignalUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    fun load(force: Boolean = false) {
        if (loadJob?.isActive == true) return
        val current = _state.value
        _state.value = current.copy(
            loading = current.rows.isEmpty(),
            refreshing = force && current.rows.isNotEmpty(),
            error = null
        )
        loadJob = viewModelScope.launch {
            val result = runCatching { fundRepository.load(force = force) }.getOrNull()
            if (result == null) {
                _state.value = _state.value.copy(
                    loading = false,
                    refreshing = false,
                    error = "基金列表加载失败，请检查网络后重试"
                )
                return@launch
            }
            if (result.funds.isEmpty()) {
                _state.value = SignalUiState(loading = false, watchlistEmpty = true)
                return@launch
            }

            // 基金 → 指数映射（含手动指定），与详情页同一套解析。
            val tracked = withTimeoutOrNull(10_000) {
                runCatching { indexRepository.tracked() }.getOrNull()
            }
            val byFund = HashMap<String, TrackedIndex>()
            tracked?.items?.forEach { item ->
                item.fundCodes.forEach { code -> byFund.putIfAbsent(code, item) }
            }

            // 沪深300 近 20 日涨跌：强势回踩信号的相对强弱基准。
            val benchmark20 = withTimeoutOrNull(8_000) {
                runCatching {
                    klineRepository.load(BENCHMARK_SECID).series?.completed()?.changeOver(20)
                }.getOrNull()
            }

            // 同一指数只加载 / 计算一次。并发只负责取数，结果回到主线程再合并，
            // 避免多个协程同时写一个 HashMap。
            val entries = byFund.values.map { it.entry }.distinctBy { it.secid }
            val reports = HashMap<String, SignalReport?>()
            val failures = HashMap<String, String>()
            entries.chunked(4).forEach { chunk ->
                val loaded = coroutineScope {
                    chunk.map { entry ->
                        async { entry to runCatching { klineRepository.load(entry.secid) }.getOrNull() }
                    }.awaitAll()
                }
                loaded.forEach { (entry, load) ->
                    val series = load?.series
                    if (series == null) {
                        failures[entry.secid] = load?.error ?: "行情加载失败"
                    } else {
                        reports[entry.secid] = evaluate(series, benchmark20)
                    }
                }
            }

            val rows = result.funds.map { fund ->
                val holding = holdingStore.get(fund.code)?.takeUnless { it.isEmpty }
                val held = holding != null
                val item = byFund[fund.code]
                val report = item?.let { reports[it.entry.secid] }
                val filtered = if (!held && report != null) {
                    // 未持有的基金只关心买点，卖出信号不显示。
                    report.copy(signals = report.signals.filter { it.action == SignalAction.BUY })
                } else {
                    report
                }
                val note = when {
                    item == null -> "未识别跟踪指数（主动基金或名字未匹配），可在基金详情里手动指定"
                    filtered == null -> failures[item.entry.secid] ?: "指数K线暂不可用"
                    filtered.note.isNotBlank() -> filtered.note
                    else -> ""
                }
                SignalRow(
                    fund = fund,
                    held = held,
                    holdingValue = holding?.marketValue(fund.nav) ?: 0.0,
                    indexName = item?.entry?.name ?: fund.trackIndex,
                    report = filtered,
                    note = note
                )
            }

            _state.value = SignalUiState(
                loading = false,
                refreshing = false,
                rows = rows,
                updatedAt = System.currentTimeMillis(),
                watchlistEmpty = false
            )
        }
    }

    private fun evaluate(series: KlineSeries, benchmark20: Double?): SignalReport {
        val self20 = series.completed().changeOver(20)
        val relative20 = if (self20 != null && benchmark20 != null) self20 - benchmark20 else null
        return SignalEngine.evaluate(series, relative20)
    }

    fun consumeError() {
        _state.value = _state.value.copy(error = null)
    }

    companion object {
        private const val BENCHMARK_SECID = "1.000300"

        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { SignalViewModel(context.applicationContext) }
        }
    }
}