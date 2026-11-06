package com.hukua.fundradar.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hukua.fundradar.data.Estimate
import com.hukua.fundradar.data.EstimateBatch
import com.hukua.fundradar.data.EstimateRepository
import com.hukua.fundradar.data.Fund
import com.hukua.fundradar.data.FundExtra
import com.hukua.fundradar.data.FundRepository
import com.hukua.fundradar.data.FundScope
import com.hukua.fundradar.data.IndexEntry
import com.hukua.fundradar.data.IndexRepository
import com.hukua.fundradar.data.LimitChange
import com.hukua.fundradar.data.LimitType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class FundFilter(val label: String) {
    ALL("全部"),
    LIMITED("限大额"),
    SUSPENDED("暂停申购"),
    OPEN("不限购");

    fun matches(fund: Fund): Boolean = when (this) {
        ALL -> true
        LIMITED -> fund.limitType == LimitType.LIMITED
        SUSPENDED -> fund.limitType == LimitType.SUSPENDED
        OPEN -> fund.limitType == LimitType.OPEN
    }
}

data class FundUiState(
    val all: List<Fund> = emptyList(),
    val visible: List<Fund> = emptyList(),
    val filter: FundFilter = FundFilter.ALL,
    val scope: FundScope = FundScope.PORTFOLIO,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val updatedAt: Long = 0L,
    val fromCache: Boolean = false,
    /** True when the user has not added any fund yet. */
    val watchlistEmpty: Boolean = true,
    /** Funds whose purchase limit moved during the last refresh. */
    val changes: List<LimitChange> = emptyList(),
    /** Intraday estimates, keyed by fund code. Portfolio only. */
    val estimates: Map<String, Estimate> = emptyMap(),
    val estimatesLoading: Boolean = false,
    val estimatesAt: Long = 0L,
    /** 行情源整体不可用时给用户一句解释。 */
    val estimatesError: String? = null,
    /** 用户手动指定的「基金 → 指数」，覆盖按名字自动匹配。 */
    val overrides: Map<String, IndexEntry> = emptyMap()
) {
    val limitedCount: Int get() = all.count { it.limitType == LimitType.LIMITED }
    val suspendedCount: Int get() = all.count { it.limitType == LimitType.SUSPENDED }
    val openCount: Int get() = all.count { it.limitType == LimitType.OPEN }

    /** Funds with a usable estimate, and how many of those are ETF based. */
    val estimatedCount: Int get() = all.count { estimates[it.code]?.isRealtime == true }
    val etfCount: Int get() = estimates.values.count { it.source == com.hukua.fundradar.data.EstimateSource.ETF }

    fun derive(): FundUiState = copy(visible = all.filter { filter.matches(it) })
}

/** State of the "add fund" bottom sheet. */
data class AddFundState(
    val open: Boolean = false,
    val query: String = "",
    val searching: Boolean = false,
    val results: List<Fund> = emptyList(),
    val adding: String? = null,
    val message: String? = null,
    val watched: Set<String> = emptySet()
)

sealed interface DetailState {
    val fund: Fund

    data class Loading(override val fund: Fund) : DetailState
    data class Ready(override val fund: Fund, val extra: FundExtra?) : DetailState
}

/** 「指定跟踪指数」底部弹层的状态。 */
data class TrackPickerState(
    val open: Boolean = false,
    val fundCode: String = "",
    val fundName: String = "",
    val query: String = "",
    val searching: Boolean = false,
    val results: List<IndexEntry> = emptyList(),
    val message: String? = null,
    /** 当前已经手动指定的指数，没有则为 null。 */
    val current: IndexEntry? = null
)

class FundViewModel(
    private val repository: FundRepository,
    private val estimateRepository: EstimateRepository,
    private val indexRepository: IndexRepository
) : ViewModel() {

    val scope: FundScope get() = repository.scope

    private val _state = MutableStateFlow(
        FundUiState(
            scope = repository.scope,
            watchlistEmpty = repository.codes().isEmpty(),
            overrides = indexRepository.trackOverrides()
        )
    )
    val state: StateFlow<FundUiState> = _state.asStateFlow()

    private val _add = MutableStateFlow(AddFundState())
    val add: StateFlow<AddFundState> = _add.asStateFlow()

    private val _detail = MutableStateFlow<DetailState?>(null)
    val detail: StateFlow<DetailState?> = _detail.asStateFlow()

    private val _trackPicker = MutableStateFlow(TrackPickerState())
    val trackPicker: StateFlow<TrackPickerState> = _trackPicker.asStateFlow()

    private var searchJob: Job? = null
    private var estimateJob: Job? = null
    private var trackJob: Job? = null
    private var searchSeq = 0
    private var trackSeq = 0
    private var proxyCache: Pair<Long, Map<String, IndexEntry>>? = null

    init {
        load(force = false)
    }

    fun refresh() = load(force = true)

    fun setFilter(filter: FundFilter) {
        _state.update { it.copy(filter = filter).derive() }
    }

    // ---------------------------------------------------------- watchlist

    fun openAdd() {
        _add.value = AddFundState(open = true, watched = repository.codes().toSet())
    }

    fun closeAdd() {
        searchSeq++
        searchJob?.cancel()
        _add.value = AddFundState()
    }

    fun onQueryChange(query: String) {
        _add.update { it.copy(query = query, message = null) }
        searchJob?.cancel()
        val seq = ++searchSeq
        if (query.isBlank()) {
            _add.update { it.copy(results = emptyList(), searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(350)
            _add.update { it.copy(searching = true) }
            val results = repository.search(query.trim())
            if (seq == searchSeq) {
                _add.update { it.copy(searching = false, results = results) }
            }
        }
    }

    fun addFund(code: String) {
        val normalized = code.trim()
        if (normalized.isEmpty() || _add.value.adding != null) return

        _add.update { it.copy(adding = normalized, message = null) }
        viewModelScope.launch {
            val ok = repository.add(normalized)
            if (!ok) {
                _add.update { it.copy(adding = null, message = "没有找到该基金，请检查代码") }
                return@launch
            }
            _add.value = AddFundState()
            load(force = true)
        }
    }

    fun removeFund(code: String) {
        repository.remove(code)
        indexRepository.clearTrack(code)
        _detail.value = null
        _state.update { current ->
            current.copy(
                all = current.all.filterNot { it.code == code },
                changes = current.changes.filterNot { it.code == code },
                estimates = current.estimates - code,
                overrides = current.overrides - code,
                watchlistEmpty = repository.codes().isEmpty()
            ).derive()
        }
    }

    /** Fills an empty limit watchlist with everything the user holds. */
    fun importPortfolio() {
        viewModelScope.launch {
            val added = repository.importPortfolio()
            if (added == 0 && repository.codes().isEmpty()) return@launch
            load(force = true)
        }
    }

    /** Hides the "limit changed" card without touching the data. */
    fun dismissChanges() {
        _state.update { it.copy(changes = emptyList()) }
    }

    // ---------------------------------------------------------- estimates

    /**
     * Re-reads the intraday estimate for every fund already on screen. Cheap
     * (one batched quote request) so it can run every time the tab is opened.
     */
    fun refreshEstimates() {
        if (!scope.isPortfolio) return
        val funds = _state.value.all
        if (funds.isEmpty()) return
        estimateJob?.cancel()
        _state.update { it.copy(estimatesLoading = true) }
        estimateJob = viewModelScope.launch {
            val proxies = withContext(Dispatchers.IO) { proxies() }
            val batch = runCatching { estimateRepository.estimates(funds, proxies) }
                .getOrElse { EstimateBatch(error = it.message ?: "估算失败") }
            _state.update {
                it.copy(
                    estimates = batch.estimates,
                    estimatesError = batch.error,
                    estimatesLoading = false,
                    estimatesAt = batch.at.takeIf { at -> at > 0 } ?: System.currentTimeMillis()
                )
            }
        }
    }

    /**
     * 基金 → 标的指数。解析要读目录、偶尔还要联网，十分钟内复用一份，
     * 免得每分钟的估值刷新都去重算一遍。
     */
    private suspend fun proxies(): Map<String, IndexEntry> {
        val cached = proxyCache
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.first < PROXY_TTL_MS) return cached.second
        val fresh = indexRepository.fundProxies()
        proxyCache = now to fresh
        return fresh
    }

    // ---------------------------------------------------------- detail

    fun openDetail(fund: Fund) {
        _detail.value = DetailState.Loading(fund)
        viewModelScope.launch {
            val extra = repository.extra(fund.code)
            val current = _detail.value
            if (current is DetailState.Loading && current.fund.code == fund.code) {
                _detail.value = DetailState.Ready(fund, extra)
            }
        }
    }

    fun closeDetail() {
        _detail.value = null
    }

    // ------------------------------------------------ 跟踪标的手动指定

    fun openTrackPicker(fund: Fund) {
        _trackPicker.value = TrackPickerState(
            open = true,
            fundCode = fund.code,
            fundName = fund.name,
            current = indexRepository.trackOverride(fund.code)
        )
    }

    fun closeTrackPicker() {
        trackSeq++
        trackJob?.cancel()
        _trackPicker.value = TrackPickerState()
    }

    fun onTrackQueryChange(query: String) {
        _trackPicker.update { it.copy(query = query, message = null) }
        trackJob?.cancel()
        val seq = ++trackSeq
        if (query.isBlank()) {
            _trackPicker.update { it.copy(results = emptyList(), searching = false) }
            return
        }
        trackJob = viewModelScope.launch {
            delay(350)
            _trackPicker.update { it.copy(searching = true) }
            val results = indexRepository.search(query.trim())
            if (seq == trackSeq) {
                _trackPicker.update { it.copy(searching = false, results = results) }
            }
        }
    }

    /** 保存选择：这只基金之后的指数行情就取这个标的。 */
    fun assignTrack(entry: IndexEntry) {
        val target = _trackPicker.value
        if (target.fundCode.isBlank()) return
        indexRepository.assignTrack(target.fundCode, entry)
        proxyCache = null
        _state.update { it.copy(overrides = indexRepository.trackOverrides()) }
        closeTrackPicker()
    }

    fun clearTrack(fundCode: String) {
        indexRepository.clearTrack(fundCode)
        proxyCache = null
        _state.update { it.copy(overrides = indexRepository.trackOverrides()) }
    }

    fun consumeError() {
        _state.update { it.copy(error = null) }
    }

    // ----------------------------------------------------------

    private fun load(force: Boolean) {
        val current = _state.value
        if (current.refreshing) return

        val codes = repository.codes()
        _state.update {
            it.copy(
                loading = it.all.isEmpty() && codes.isNotEmpty(),
                refreshing = force && it.all.isNotEmpty(),
                error = null,
                watchlistEmpty = codes.isEmpty()
            )
        }

        viewModelScope.launch {
            runCatching { repository.load(codes, force) }
                .onSuccess { result ->
                    _state.update {
                        it.copy(
                            all = result.funds,
                            loading = false,
                            refreshing = false,
                            updatedAt = result.updatedAt,
                            fromCache = result.fromCache,
                            error = null,
                            watchlistEmpty = codes.isEmpty(),
                            changes = result.changes,
                            overrides = indexRepository.trackOverrides()
                        ).derive()
                    }
                    if (scope.isPortfolio) refreshEstimates()
                }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            error = throwable.message ?: "网络连接失败"
                        )
                    }
                }
        }
    }

    companion object {
        /** 基金 → 指数的映射缓存十分钟，够覆盖几次估值刷新。 */
        private const val PROXY_TTL_MS = 10 * 60 * 1000L

        fun factory(context: Context, scope: FundScope): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    FundViewModel(
                        repository = FundRepository(context.applicationContext, scope),
                        estimateRepository = EstimateRepository(context.applicationContext),
                        indexRepository = IndexRepository(context.applicationContext)
                    )
                }
            }
    }
}
