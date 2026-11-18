package com.hukua.fundradar.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hukua.fundradar.data.AiAdvisor
import com.hukua.fundradar.data.DcaAction
import com.hukua.fundradar.data.DcaAiAdvice
import com.hukua.fundradar.data.DcaEngine
import com.hukua.fundradar.data.DcaPlan
import com.hukua.fundradar.data.DcaPlanStore
import com.hukua.fundradar.data.DcaPrompt
import com.hukua.fundradar.data.DcaReport
import com.hukua.fundradar.data.DcaVerdict
import com.hukua.fundradar.data.Estimate
import com.hukua.fundradar.data.Fund
import com.hukua.fundradar.data.Holding
import com.hukua.fundradar.data.IndexCatalog
import com.hukua.fundradar.data.IndexEntry
import com.hukua.fundradar.data.KlineRepository
import com.hukua.fundradar.data.KlineSeries
import com.hukua.fundradar.data.SettingsStore
import com.hukua.fundradar.data.SignalEngine
import com.hukua.fundradar.data.SignalReport
import com.hukua.fundradar.data.TrackOverrideStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 定投页的一行：基金 + 定投判断 + 计划 + 持仓。 */
data class DcaRow(
    val fund: Fund,
    val report: DcaReport?,
    val note: String = "",
    val plan: DcaPlan = DcaPlan(),
    val holding: Holding? = null,
    val indexName: String = "",
    /** 报告背后的日线，AI 判断要拿它拼提示词；不参与界面展示。 */
    val series: KlineSeries? = null
)

/** 定投页的筛选。 */
enum class DcaFilter(val label: String) {
    ALL("全部"),
    GOOD("适合定投"),
    SLOW("建议减/停"),
    PROFIT("建议止盈");

    fun matches(row: DcaRow): Boolean {
        val report = row.report ?: return this == ALL
        return when (this) {
            ALL -> true
            GOOD -> report.verdict == DcaVerdict.GOOD
            SLOW -> report.action == DcaAction.PAUSE || report.action == DcaAction.REDUCE ||
                report.action == DcaAction.STOP
            PROFIT -> report.action == DcaAction.TAKE_PROFIT || report.action == DcaAction.STOP
        }
    }
}

data class DcaUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val rows: List<DcaRow> = emptyList(),
    val updatedAt: Long = 0L,
    val error: String? = null,
    val watchlistEmpty: Boolean = false,
    val filter: DcaFilter = DcaFilter.ALL,
    /** 详情页当前看的那只基金代码，空串表示在列表页。 */
    val selected: String = "",
    val plans: Map<String, DcaPlan> = emptyMap(),
    val advice: Map<String, DcaAiAdvice> = emptyMap(),
    val aiLoading: String? = null,
    val aiError: Map<String, String> = emptyMap()
) {
    val visible: List<DcaRow> get() = rows.filter { filter.matches(it) }

    val goodCount: Int get() = rows.count { it.report?.verdict == DcaVerdict.GOOD }
    val pauseCount: Int
        get() = rows.count {
            it.report?.action == DcaAction.PAUSE || it.report?.action == DcaAction.REDUCE ||
                it.report?.action == DcaAction.STOP
        }
    val profitCount: Int
        get() = rows.count {
            it.report?.action == DcaAction.TAKE_PROFIT || it.report?.action == DcaAction.STOP
        }

    /** 每月计划投入合计。 */
    val monthlyTotal: Double get() = rows.sumOf { if (it.plan.enabled) it.plan.monthlyAmount else 0.0 }

    /** 定投池里有持仓的市值 / 成本合计，用的是最新净值。 */
    val holdingValue: Double
        get() = rows.sumOf { row -> row.holding?.marketValue(row.fund.nav) ?: 0.0 }
    val holdingCost: Double get() = rows.sumOf { it.holding?.cost ?: 0.0 }
    val holdingProfit: Double get() = holdingValue - holdingCost

    val live: Boolean get() = rows.any { it.report?.live == true }

    fun rowOf(code: String): DcaRow? = rows.firstOrNull { it.fund.code == code }
}

/**
 * 「定投」页的 view model。
 *
 * 列表与持仓复用 [FundViewModel]（[com.hukua.fundradar.data.FundScope.DCA]），这里只做
 * 三件独立的事：把基金映射到跟踪指数、按指数日线算定投判断、把「基金现状 + 我的
 * 持仓 + 定投计划」发给 AI。
 *
 * 基金列表由外部推进来（[evaluate]），避免两套请求同时打基金页接口。
 */
class DcaViewModel(context: Context) : ViewModel() {

    private val appContext = context.applicationContext
    private val klineRepository = KlineRepository(appContext)
    private val planStore = DcaPlanStore(appContext)
    private val trackStore = TrackOverrideStore(appContext)

    private val _state = MutableStateFlow(DcaUiState(plans = planStore.all()))
    val state: StateFlow<DcaUiState> = _state.asStateFlow()

    private var evalJob: Job? = null
    private var lastSignature: String = ""
    private var lastEvaluatedAt: Long = 0L

    private var lastFunds: List<Fund> = emptyList()
    private var lastHoldings: Map<String, Holding> = emptyMap()
    private var lastEstimates: Map<String, Estimate> = emptyMap()

    /**
     * 按当前基金列表重算一遍。估算每 5 秒变一次，这里用「代码 + 持仓 + 计划」做
     * 签名并加 60 秒节流 —— 行情的跳动不该让 250 根 K 线的指标每分钟重算几十遍。
     */
    fun evaluate(
        funds: List<Fund>,
        holdings: Map<String, Holding>,
        estimates: Map<String, Estimate> = emptyMap(),
        force: Boolean = false
    ) {
        lastFunds = funds
        lastHoldings = holdings
        lastEstimates = estimates

        if (funds.isEmpty()) {
            evalJob?.cancel()
            _state.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    rows = emptyList(),
                    watchlistEmpty = true,
                    plans = planStore.all()
                )
            }
            return
        }

        val signature = funds.map { it.code }.joinToString(",") + "|" + holdings.hashCode()
        val now = System.currentTimeMillis()
        if (!force && signature == lastSignature && now - lastEvaluatedAt < MIN_INTERVAL_MS) {
            return
        }
        if (evalJob?.isActive == true && !force) return

        evalJob?.cancel()
        _state.update {
            it.copy(
                loading = it.rows.isEmpty(),
                refreshing = force && it.rows.isNotEmpty(),
                watchlistEmpty = false,
                error = null
            )
        }

        evalJob = viewModelScope.launch {
            val plans = planStore.all()
            val overrides = trackStore.all()
            val entries = HashMap<String, IndexEntry>()
            overrides.forEach { (code, entry) ->
                if (funds.any { it.code == code }) entries[code] = entry
            }

            // 基金页只有跟踪指数的中文名，先并发换成一个能取行情的 secid。
            val unresolved = funds.filter { it.code !in entries && it.trackIndex.isNotBlank() }
            coroutineScope {
                unresolved.map { fund ->
                    async(Dispatchers.IO) {
                        val entry = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                            runCatching { IndexCatalog.resolveByName(appContext, fund.trackIndex) }.getOrNull()
                        }
                        fund.code to entry
                    }
                }.awaitAll().forEach { (code, entry) ->
                    if (entry != null) entries[code] = entry
                }
            }

            // 沪深300 近 20 日涨跌作为相对强弱的基准，拿不到就只算绝对动量。
            val benchmark20 = withTimeoutOrNull(BENCHMARK_TIMEOUT_MS) {
                runCatching { klineRepository.load(BENCHMARK_SECID).series?.changeOver(20) }.getOrNull()
            }

            val seriesMap = HashMap<String, KlineSeries>()
            val reports = HashMap<String, SignalReport?>()
            val failures = HashMap<String, String>()
            val secids = entries.values.map { it.secid }.distinct()

            secids.chunked(3).forEach { chunk ->
                val loaded = coroutineScope {
                    chunk.map { secid ->
                        async { secid to runCatching { klineRepository.load(secid) }.getOrNull() }
                    }.awaitAll()
                }
                loaded.forEach { (secid, load) ->
                    val series = load?.series
                    if (series == null) {
                        failures[secid] = load?.error ?: "行情加载失败"
                        return@forEach
                    }
                    seriesMap[secid] = series
                    val self20 = series.changeOver(20)
                    val relative20 = if (self20 != null && benchmark20 != null) {
                        self20 - benchmark20
                    } else {
                        null
                    }
                    reports[secid] = SignalEngine.evaluate(series, relative20)
                }
            }

            val rows = funds.map { fund ->
                val entry = entries[fund.code]
                val series = entry?.let { seriesMap[it.secid] }
                val plan = plans[fund.code] ?: DcaPlan()
                val holding = holdings[fund.code]?.takeUnless { it.isEmpty }

                val report = if (entry != null && series != null) {
                    DcaEngine.evaluate(
                        fund = fund,
                        series = series,
                        signal = reports[entry.secid],
                        holding = holding,
                        plan = plan,
                        estimate = estimates[fund.code]
                    )
                } else {
                    null
                }

                val note = when {
                    fund.trackIndex.isBlank() && entry == null ->
                        "主动基金没有跟踪指数，定投判断只适用于指数 / 行业基金"
                    entry == null ->
                        "没认出跟踪指数（名字没匹配上），可在基金详情里手动指定"
                    series == null ->
                        failures[entry.secid] ?: "跟踪指数 K 线暂不可用"
                    else -> ""
                }

                DcaRow(
                    fund = fund,
                    report = report,
                    note = note,
                    plan = plan,
                    holding = holding,
                    indexName = entry?.name ?: fund.trackIndex,
                    series = series
                )
            }

            lastSignature = signature
            lastEvaluatedAt = System.currentTimeMillis()
            _state.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    rows = rows,
                    updatedAt = System.currentTimeMillis(),
                    watchlistEmpty = false,
                    plans = plans,
                    error = null
                )
            }
        }
    }

    fun refresh() {
        evaluate(lastFunds, lastHoldings, lastEstimates, force = true)
    }

    fun setFilter(filter: DcaFilter) {
        _state.update { it.copy(filter = filter) }
    }

    fun openDetail(code: String) {
        _state.update { it.copy(selected = code) }
    }

    fun closeDetail() {
        _state.update { it.copy(selected = "") }
    }

    // ------------------------------------------------------------- 定投计划

    fun savePlan(code: String, plan: DcaPlan) {
        if (code.isBlank()) return
        planStore.save(code, plan)

        // 计划直接影响「该不该继续扣」，保存后立刻按同一根 K 线重算这一行的建议。
        val rows = _state.value.rows.map { row ->
            if (row.fund.code != code) return@map row
            val series = row.series
            val report = if (series != null) {
                DcaEngine.evaluate(
                    fund = row.fund,
                    series = series,
                    signal = row.report?.signal,
                    holding = row.holding,
                    plan = plan
                )
            } else {
                row.report
            }
            row.copy(plan = plan, report = report)
        }
        _state.update { it.copy(plans = planStore.all(), rows = rows) }
    }

    /** 从定投池里删掉一只基金时，把计划一起清掉。 */
    fun forget(code: String) {
        planStore.remove(code)
        _state.update {
            it.copy(
                selected = "",
                plans = planStore.all(),
                rows = it.rows.filterNot { row -> row.fund.code == code }
            )
        }
    }

    // ------------------------------------------------------------------ AI

    /** 让 AI 结合基金现状与我的真实持仓复核一遍。点了才请求，不常驻。 */
    fun askAi(code: String) {
        val row = _state.value.rowOf(code) ?: return
        val series = row.series
        val report = row.report
        if (series == null || report == null) {
            _state.update { it.copy(aiError = it.aiError + (code to "这只基金还没有可用的指数行情，先把跟踪指数指定好")) }
            return
        }
        if (_state.value.aiLoading != null) return

        val config = SettingsStore(appContext).load()
        if (!config.configured) {
            _state.update {
                it.copy(aiError = it.aiError + (code to "还没有配置 AI 接口，去设置里填地址、模型和 Key"))
            }
            return
        }

        _state.update { it.copy(aiLoading = code, aiError = it.aiError - code, selected = code) }
        viewModelScope.launch {
            runCatching {
                val prompt = DcaPrompt.build(
                    fund = row.fund,
                    series = series,
                    report = report,
                    holding = row.holding,
                    plan = row.plan
                )
                val content = AiAdvisor.consult(config, prompt)
                AiAdvisor.parseDcaAdvice(content, prompt.render())
            }.onSuccess { advice ->
                _state.update {
                    it.copy(aiLoading = null, advice = it.advice + (code to advice))
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        aiLoading = null,
                        aiError = it.aiError + (code to (error.message ?: "AI 请求失败"))
                    )
                }
            }
        }
    }

    fun clearAiError(code: String) {
        _state.update { it.copy(aiError = it.aiError - code) }
    }

    fun consumeError() {
        _state.update { it.copy(error = null) }
    }

    companion object {
        /** 两次重算之间至少隔这么久，避免估算轮询把指标算爆。 */
        private const val MIN_INTERVAL_MS = 60_000L

        private const val RESOLVE_TIMEOUT_MS = 6_000L
        private const val BENCHMARK_TIMEOUT_MS = 8_000L
        private const val BENCHMARK_SECID = "1.000300"

        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { DcaViewModel(context.applicationContext) }
        }
    }
}
