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
import com.hukua.fundradar.data.DcaIndexStore
import com.hukua.fundradar.data.DcaPlan
import com.hukua.fundradar.data.DcaPlanStore
import com.hukua.fundradar.data.DcaPrompt
import com.hukua.fundradar.data.DcaReport
import com.hukua.fundradar.data.DcaVerdict
import com.hukua.fundradar.data.IndexEntry
import com.hukua.fundradar.data.IndexSearchApi
import com.hukua.fundradar.data.KlineRepository
import com.hukua.fundradar.data.KlineSeries
import com.hukua.fundradar.data.SettingsStore
import com.hukua.fundradar.data.SignalEngine
import com.hukua.fundradar.data.SignalReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 定投页的一行：一个行业主题指数 + 定投判断 + 计划 + 按计划回放出来的持仓。 */
data class DcaRow(
    val entry: IndexEntry,
    val report: DcaReport?,
    val note: String = "",
    val plan: DcaPlan = DcaPlan(),
    /** 报告背后的日线，AI 判断要拿它拼提示词；不参与界面展示。 */
    val series: KlineSeries? = null,
    /** 最新一根已收盘日线的涨跌（%），卡片右上角用。 */
    val changePct: Double? = null
) {
    val secid: String get() = entry.secid

    val displayName: String get() = entry.name.ifBlank { report?.indexName.orEmpty() }
}

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
    val empty: Boolean = true,
    val filter: DcaFilter = DcaFilter.ALL,
    /** 详情页当前看的指数 secid，空串表示在列表页。 */
    val selected: String = "",
    val plans: Map<String, DcaPlan> = emptyMap(),
    val advice: Map<String, DcaAiAdvice> = emptyMap(),
    val aiLoading: String? = null,
    val aiError: Map<String, String> = emptyMap(),
    /** 「添加行业指数」弹层的搜索状态，复用基金详情里那套指数搜索。 */
    val picker: TrackPickerState = TrackPickerState()
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

    /** 按计划一年要投出去的钱，日用 / 周用 / 月用都折算到年。 */
    val yearlyTotal: Double
        get() = rows.sumOf { if (it.plan.enabled) it.plan.yearlyAmount() else 0.0 }

    /** 回放出来的累计投入 / 市值 / 浮动盈亏。 */
    val investedTotal: Double get() = rows.sumOf { it.report?.simulation?.invested ?: 0.0 }
    val valueTotal: Double get() = rows.sumOf { it.report?.simulation?.value ?: 0.0 }
    val profitTotal: Double get() = valueTotal - investedTotal
    val profitPct: Double? get() = if (investedTotal > 0) profitTotal / investedTotal * 100.0 else null

    val live: Boolean get() = rows.any { it.report?.live == true }

    fun rowOf(secid: String): DcaRow? = rows.firstOrNull { it.secid == secid }
}

/**
 * 「定投」页的 view model。
 *
 * 和另外两个页签不同，这里维护的是**指数清单**（[DcaIndexStore]），不是基金清单：
 * 定投先要回答「这个行业现在值不值得买」，指数才是那个有长期日线、能算趋势和分位的东西。
 * 买哪个 ETF / 场外指基由用户自己定，页面上只给判断。
 *
 * 三件事：把指数换成日线、按日线算评分与动作、把「指数现状 + 定投计划 + 回放的持仓」发给 AI。
 */
class DcaViewModel(context: Context) : ViewModel() {

    private val appContext = context.applicationContext
    private val klineRepository = KlineRepository(appContext)
    private val indexStore = DcaIndexStore(appContext)
    private val planStore = DcaPlanStore(appContext)

    private val _state = MutableStateFlow(
        DcaUiState(empty = indexStore.entries().isEmpty(), plans = planStore.all())
    )
    val state: StateFlow<DcaUiState> = _state.asStateFlow()

    private var evalJob: Job? = null
    private var searchJob: Job? = null

    init {
        refresh()
    }

    /** 重新拉一遍所有指数的日线并重算。盘中最多两分钟一次，别把指标算爆。 */
    fun refresh(force: Boolean = false) {
        if (!force && evalJob?.isActive == true) return
        evalJob?.cancel()

        val entries = indexStore.entries()
        val plans = planStore.all()

        if (entries.isEmpty()) {
            _state.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    rows = emptyList(),
                    empty = true,
                    plans = plans
                )
            }
            return
        }

        _state.update {
            it.copy(
                loading = it.rows.isEmpty(),
                refreshing = force && it.rows.isNotEmpty(),
                empty = false,
                error = null
            )
        }

        evalJob = viewModelScope.launch {
            // 沪深300 近 20 日涨跌作为相对强弱的基准，拿不到就只算绝对动量。
            val benchmark20 = runCatching {
                klineRepository.load(BENCHMARK_SECID).series?.changeOver(20)
            }.getOrNull()

            val seriesMap = HashMap<String, KlineSeries>()
            val failures = HashMap<String, String>()

            entries.map { it.secid }.distinct().chunked(3).forEach { chunk ->
                val loaded = coroutineScope {
                    chunk.map { secid ->
                        async(Dispatchers.IO) {
                            secid to runCatching { klineRepository.load(secid) }.getOrNull()
                        }
                    }.awaitAll()
                }
                loaded.forEach { (secid, load) ->
                    val series = load?.series
                    if (series == null) {
                        failures[secid] = load?.error ?: "行情加载失败"
                    } else {
                        seriesMap[secid] = series
                    }
                }
            }

            val rows = entries.map { entry ->
                val series = seriesMap[entry.secid]
                val plan = plans[entry.secid] ?: DcaPlan()
                if (series == null) {
                    return@map DcaRow(
                        entry = entry,
                        report = null,
                        note = failures[entry.secid] ?: "指数日线暂不可用",
                        plan = plan
                    )
                }

                val self20 = series.changeOver(20)
                val relative20 = if (self20 != null && benchmark20 != null) self20 - benchmark20 else null
                val signal: SignalReport = SignalEngine.evaluate(series, relative20)
                val report = DcaEngine.evaluate(
                    entry = entry,
                    series = series,
                    plan = plan,
                    signal = signal
                )
                DcaRow(
                    entry = entry,
                    report = report,
                    plan = plan,
                    series = series,
                    changePct = series.completed().candles.let { candles ->
                        if (candles.size < 2) null else {
                            val last = candles.last().close
                            val before = candles[candles.size - 2].close
                            if (before > 0) (last / before - 1.0) * 100.0 else null
                        }
                    }
                )
            }

            _state.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    rows = rows,
                    updatedAt = System.currentTimeMillis(),
                    empty = rows.isEmpty(),
                    plans = plans,
                    error = null
                )
            }
        }
    }

    fun setFilter(filter: DcaFilter) {
        _state.update { it.copy(filter = filter) }
    }

    fun openDetail(secid: String) {
        _state.update { it.copy(selected = secid) }
    }

    fun closeDetail() {
        _state.update { it.copy(selected = "") }
    }

    // -------------------------------------------------------------- 清单维护

    fun addIndex(entry: IndexEntry) {
        if (!indexStore.add(entry)) {
            _state.update { it.copy(picker = it.picker.copy(message = "这个指数已经在定投清单里了")) }
            return
        }
        _state.update { it.copy(picker = TrackPickerState()) }
        refresh(force = true)
    }

    fun removeIndex(secid: String) {
        indexStore.remove(secid)
        planStore.remove(secid)
        _state.update {
            it.copy(
                selected = if (it.selected == secid) "" else it.selected,
                plans = planStore.all(),
                rows = it.rows.filterNot { row -> row.secid == secid },
                empty = indexStore.entries().isEmpty()
            )
        }
    }

    // -------------------------------------------------------------- 指数搜索

    fun openPicker() {
        _state.update { it.copy(picker = TrackPickerState(open = true, fundName = "定投")) }
    }

    fun closePicker() {
        searchJob?.cancel()
        _state.update { it.copy(picker = TrackPickerState()) }
    }

    fun onPickerQueryChange(query: String) {
        _state.update {
            it.copy(picker = it.picker.copy(query = query, message = null))
        }
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(picker = it.picker.copy(results = emptyList(), searching = false)) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _state.update { it.copy(picker = it.picker.copy(searching = true)) }
            val result = runCatching { IndexSearchApi.search(query) }.getOrNull()
            _state.update {
                it.copy(
                    picker = it.picker.copy(
                        searching = false,
                        results = result.orEmpty(),
                        message = if (result == null) "搜索失败，检查网络后重试" else null
                    )
                )
            }
        }
    }

    // ------------------------------------------------------------- 定投计划

    fun savePlan(secid: String, plan: DcaPlan) {
        if (secid.isBlank()) return
        planStore.save(secid, plan)

        // 计划直接影响「已投几期、现在该不该继续扣」，保存后立刻按同一根 K 线重算。
        val rows = _state.value.rows.map { row ->
            if (row.secid != secid) return@map row
            val series = row.series ?: return@map row.copy(plan = plan)
            val report = DcaEngine.evaluate(
                entry = row.entry,
                series = series,
                plan = plan,
                signal = row.report?.signal
            )
            row.copy(plan = plan, report = report)
        }
        _state.update { it.copy(plans = planStore.all(), rows = rows) }
    }

    // ------------------------------------------------------------------ AI

    /** 让 AI 结合指数现状与按计划回放的持仓复核一遍。点了才请求，不常驻。 */
    fun askAi(secid: String) {
        val row = _state.value.rowOf(secid) ?: return
        val series = row.series
        val report = row.report
        if (series == null || report == null) {
            _state.update { it.copy(aiError = it.aiError + (secid to "这个指数还没有可用的日线，稍后再试")) }
            return
        }
        if (_state.value.aiLoading != null) return

        val config = SettingsStore(appContext).load()
        if (!config.configured) {
            _state.update {
                it.copy(aiError = it.aiError + (secid to "还没有配置 AI 接口，去设置里填地址、模型和 Key"))
            }
            return
        }

        _state.update { it.copy(aiLoading = secid, aiError = it.aiError - secid, selected = secid) }
        viewModelScope.launch {
            runCatching {
                val prompt = DcaPrompt.build(
                    entry = row.entry,
                    series = series,
                    report = report,
                    plan = row.plan
                )
                val content = AiAdvisor.consult(config, prompt)
                AiAdvisor.parseDcaAdvice(content, prompt.render())
            }.onSuccess { advice ->
                _state.update { it.copy(aiLoading = null, advice = it.advice + (secid to advice)) }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        aiLoading = null,
                        aiError = it.aiError + (secid to (error.message ?: "AI 请求失败"))
                    )
                }
            }
        }
    }

    fun clearAiError(secid: String) {
        _state.update { it.copy(aiError = it.aiError - secid) }
    }

    fun consumeError() {
        _state.update { it.copy(error = null) }
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 320L
        private const val BENCHMARK_SECID = "1.000300"

        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { DcaViewModel(context.applicationContext) }
        }
    }
}
