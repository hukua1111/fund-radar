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
import com.hukua.fundradar.data.Holding
import com.hukua.fundradar.data.HoldingStore
import com.hukua.fundradar.data.IndexEntry
import com.hukua.fundradar.data.IndexRepository
import com.hukua.fundradar.data.LimitChange
import com.hukua.fundradar.data.LimitType
import com.hukua.fundradar.data.Trade
import com.hukua.fundradar.data.TradeKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

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
    val overrides: Map<String, IndexEntry> = emptyMap(),
    /** 用户自己的持仓（份额 / 成本 / 已实现盈亏），仅持仓页使用。 */
    val holdings: Map<String, Holding> = emptyMap()
) {
    val limitedCount: Int get() = all.count { it.limitType == LimitType.LIMITED }
    val suspendedCount: Int get() = all.count { it.limitType == LimitType.SUSPENDED }
    val openCount: Int get() = all.count { it.limitType == LimitType.OPEN }

    /** Funds with a usable estimate, and how many of those are ETF based. */
    val estimatedCount: Int get() = all.count { estimates[it.code]?.isRealtime == true }
    val etfCount: Int get() = estimates.values.count { it.source == com.hukua.fundradar.data.EstimateSource.ETF }

    /** 估算净值优先，拿不到估算就用最近公布的净值。 */
    fun navOf(fund: Fund): Double = estimates[fund.code]?.nav ?: fund.nav

    fun holdingOf(fund: Fund): Holding? = holdings[fund.code]?.takeUnless { it.isEmpty }

    /** 有持仓的基金（按份额降序，默认列表顺序不受影响）。 */
    val holdingFunds: List<Fund>
        get() = all.filter { holdingOf(it) != null }
            .sortedByDescending { holdingOf(it)?.marketValue(navOf(it)) ?: 0.0 }

    /** 持仓总市值。 */
    val holdingValue: Double
        get() = all.sumOf { fund -> holdingOf(fund)?.marketValue(navOf(fund)) ?: 0.0 }

    /** 持仓总成本。 */
    val holdingCost: Double get() = all.sumOf { holdingOf(it)?.cost ?: 0.0 }

    /** 累计浮动盈亏。 */
    val holdingProfit: Double get() = holdingValue - holdingCost

    /**
     * 今日盈亏 = 各基金市值 × 涨跌幅 / (100 + 涨跌幅)。
     * 只算真·盘中估算的基金 ——「最近公布净值」那条不是今天的涨跌。
     */
    val holdingTodayProfit: Double
        get() = all.sumOf { fund ->
            val holding = holdingOf(fund) ?: return@sumOf 0.0
            val estimate = estimates[fund.code]?.takeIf { it.isRealtime } ?: return@sumOf 0.0
            holding.todayProfit(navOf(fund), estimate.percent) ?: 0.0
        }

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

/** 持仓面板的三种用法：记一笔买入、记一笔卖出、直接照着 App 填份额与成本。 */
enum class HoldingMode(val label: String) {
    BUY("买入"),
    SELL("卖出"),
    EDIT("修改持仓")
}

/** 「我的持仓」底部弹层的状态，输入全部保留成字符串，方便带小数点输入。 */
data class HoldingSheetState(
    val open: Boolean = false,
    val fundCode: String = "",
    val fundName: String = "",
    val mode: HoldingMode = HoldingMode.BUY,
    val shares: String = "",
    val cost: String = "",
    val amount: String = "",
    val nav: String = "",
    val message: String? = null,
    /** 打开面板时已有的持仓，用于校验卖出份额、显示均价。 */
    val current: Holding = Holding()
) {
    val navValue: Double get() = nav.toDoubleOrNull() ?: 0.0
    val amountValue: Double get() = amount.toDoubleOrNull() ?: 0.0
    val sharesValue: Double get() = shares.toDoubleOrNull() ?: 0.0
    val costValue: Double get() = cost.toDoubleOrNull() ?: 0.0

    /** 买入按金额折算份额，卖出与直接修改按份额。 */
    val effectiveShares: Double
        get() = when (mode) {
            HoldingMode.BUY -> if (navValue > 0) amountValue / navValue else 0.0
            HoldingMode.SELL, HoldingMode.EDIT -> sharesValue
        }

    /** 本次成交金额（估算）；直接修改时就是成本。 */
    val effectiveAmount: Double
        get() = if (mode == HoldingMode.EDIT) costValue else effectiveShares * navValue

    val valid: Boolean
        get() = when (mode) {
            HoldingMode.BUY -> amountValue > 0 && navValue > 0
            HoldingMode.SELL -> sharesValue > 0 && navValue > 0 &&
                sharesValue <= current.shares + Holding.EPS
            HoldingMode.EDIT -> sharesValue >= 0 && costValue >= 0 &&
                (sharesValue > 0 || costValue == 0.0)
        }
}

class FundViewModel(
    private val repository: FundRepository,
    private val estimateRepository: EstimateRepository,
    private val indexRepository: IndexRepository,
    private val holdingStore: HoldingStore
) : ViewModel() {

    val scope: FundScope get() = repository.scope

    private val _state = MutableStateFlow(
        FundUiState(
            scope = repository.scope,
            watchlistEmpty = repository.codes().isEmpty(),
            overrides = indexRepository.trackOverrides(),
            holdings = holdingStore.all()
        )
    )
    val state: StateFlow<FundUiState> = _state.asStateFlow()

    private val _add = MutableStateFlow(AddFundState())
    val add: StateFlow<AddFundState> = _add.asStateFlow()

    private val _detail = MutableStateFlow<DetailState?>(null)
    val detail: StateFlow<DetailState?> = _detail.asStateFlow()

    private val _trackPicker = MutableStateFlow(TrackPickerState())
    val trackPicker: StateFlow<TrackPickerState> = _trackPicker.asStateFlow()

    private val _holdingSheet = MutableStateFlow(HoldingSheetState())
    val holdingSheet: StateFlow<HoldingSheetState> = _holdingSheet.asStateFlow()

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
        holdingStore.remove(code)
        _detail.value = null
        _state.update { current ->
            current.copy(
                all = current.all.filterNot { it.code == code },
                changes = current.changes.filterNot { it.code == code },
                estimates = current.estimates - code,
                overrides = current.overrides - code,
                holdings = current.holdings - code,
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
     *
     * 先用手上已有的「基金 → 指数」映射算一轮，卡片立刻有数字；映射需要读
     * 目录、偶尔还要联网，放到后面补，最多等几秒，拿不到就十分钟后再试。
     */
    fun refreshEstimates() {
        if (!scope.isPortfolio) return
        val funds = _state.value.all
        if (funds.isEmpty()) return
        estimateJob?.cancel()
        _state.update { it.copy(estimatesLoading = true) }
        estimateJob = viewModelScope.launch {
            val known = proxyCache?.second.orEmpty()
            publish(estimateRepository.safe(funds, known))

            if (!needsProxyRefresh()) return@launch
            val fresh = withTimeoutOrNull(PROXY_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { indexRepository.fundProxies() }
            }
            proxyCache = System.currentTimeMillis() to (fresh ?: known)
            if (fresh == null || sameProxies(known, fresh)) return@launch
            publish(estimateRepository.safe(funds, fresh))
        }
    }

    private suspend fun publish(batch: EstimateBatch) {
        _state.update {
            it.copy(
                estimates = batch.estimates,
                estimatesError = batch.error,
                estimatesLoading = false,
                estimatesAt = batch.at.takeIf { at -> at > 0 } ?: System.currentTimeMillis()
            )
        }
    }

    private fun needsProxyRefresh(): Boolean {
        val cached = proxyCache ?: return true
        return System.currentTimeMillis() - cached.first >= PROXY_TTL_MS
    }

    private fun sameProxies(
        old: Map<String, IndexEntry>,
        new: Map<String, IndexEntry>
    ): Boolean =
        old.size == new.size && old.all { (code, entry) -> new[code]?.secid == entry.secid }

    private suspend fun EstimateRepository.safe(
        funds: List<Fund>,
        proxies: Map<String, IndexEntry>
    ): EstimateBatch = runCatching { estimates(funds, proxies) }
        .getOrElse { EstimateBatch(error = it.message ?: "估算失败") }

    // ---------------------------------------------------------- detail

    fun openDetail(fund: Fund) {
        // 持仓页不看申购限额与基金档案，直接用列表里已有的数据渲染，
        // 少一次基金页请求，点开就是完整内容。
        if (scope.isPortfolio) {
            _detail.value = DetailState.Ready(fund, null)
            return
        }
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

    // ------------------------------------------------------------ 我的持仓

    fun openHoldingSheet(fund: Fund, mode: HoldingMode = HoldingMode.BUY) {
        val holding = holdingStore.get(fund.code) ?: Holding()
        val nav = _state.value.navOf(fund)
        _holdingSheet.value = HoldingSheetState(
            open = true,
            fundCode = fund.code,
            fundName = fund.name,
            mode = mode,
            shares = when (mode) {
                HoldingMode.SELL -> holding.shares.takeIf { it > 0 }?.let(::plainShares) ?: ""
                HoldingMode.EDIT -> plainShares(holding.shares)
                HoldingMode.BUY -> ""
            },
            cost = if (mode == HoldingMode.EDIT) plainMoney(holding.cost) else "",
            amount = "",
            nav = if (nav > 0) plainNav(nav) else "",
            current = holding
        )
    }

    fun closeHoldingSheet() {
        _holdingSheet.value = HoldingSheetState()
    }

    fun onHoldingModeChange(mode: HoldingMode) {
        val sheet = _holdingSheet.value
        if (sheet.mode == mode) return
        _holdingSheet.value = sheet.copy(
            mode = mode,
            message = null,
            shares = when (mode) {
                HoldingMode.SELL -> sheet.current.shares.takeIf { it > 0 }?.let(::plainShares) ?: ""
                HoldingMode.EDIT -> plainShares(sheet.current.shares)
                HoldingMode.BUY -> ""
            },
            cost = if (mode == HoldingMode.EDIT) plainMoney(sheet.current.cost) else ""
        )
    }

    fun onHoldingSharesChange(value: String) {
        _holdingSheet.update { it.copy(shares = value.filterNumeric(), message = null) }
    }

    fun onHoldingCostChange(value: String) {
        _holdingSheet.update { it.copy(cost = value.filterNumeric(), message = null) }
    }

    fun onHoldingAmountChange(value: String) {
        _holdingSheet.update { it.copy(amount = value.filterNumeric(), message = null) }
    }

    fun onHoldingNavChange(value: String) {
        _holdingSheet.update { it.copy(nav = value.filterNumeric(), message = null) }
    }

    /** 卖出面板上的「全部」：份额直接填满。 */
    fun fillAllShares() {
        val sheet = _holdingSheet.value
        if (sheet.current.shares > 0) {
            _holdingSheet.update { it.copy(shares = plainShares(sheet.current.shares)) }
        }
    }

    fun submitHolding() {
        val sheet = _holdingSheet.value
        if (sheet.fundCode.isBlank()) return
        if (!sheet.valid) {
            _holdingSheet.update { it.copy(message = invalidHint(it)) }
            return
        }

        when (sheet.mode) {
            HoldingMode.EDIT -> holdingStore.save(
                sheet.fundCode,
                sheet.current.withBase(sheet.sharesValue, sheet.costValue)
            )

            HoldingMode.BUY, HoldingMode.SELL -> holdingStore.apply(
                sheet.fundCode,
                Trade(
                    id = System.currentTimeMillis(),
                    kind = if (sheet.mode == HoldingMode.BUY) TradeKind.BUY else TradeKind.SELL,
                    shares = sheet.effectiveShares,
                    nav = sheet.navValue,
                    at = System.currentTimeMillis()
                )
            )
        }
        publishHoldings()
        closeHoldingSheet()
    }

    fun removeHolding(fundCode: String) {
        holdingStore.remove(fundCode)
        publishHoldings()
    }

    private fun publishHoldings() {
        _state.update { it.copy(holdings = holdingStore.all()) }
    }

    private fun invalidHint(sheet: HoldingSheetState): String = when (sheet.mode) {
        HoldingMode.BUY -> if (sheet.navValue <= 0) "请填写成交净值" else "请填写买入金额"
        HoldingMode.SELL -> when {
            sheet.sharesValue <= 0 -> "请填写卖出份额"
            sheet.navValue <= 0 -> "请填写成交净值"
            else -> "卖出份额不能超过持有份额"
        }
        HoldingMode.EDIT -> "份额与成本请填写非负数字"
    }

    private fun String.filterNumeric(): String =
        filter { it.isDigit() || it == '.' }
            .let { text ->
                val first = text.indexOf('.')
                if (first < 0) text
                else text.substring(0, first + 1) + text.substring(first + 1).replace(".", "")
            }

    private fun plainShares(value: Double): String =
        if (value <= 0) "" else String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')

    private fun plainMoney(value: Double): String =
        if (value <= 0) "" else String.format(Locale.US, "%.2f", value)

    private fun plainNav(value: Double): String =
        String.format(Locale.US, "%.4f", value)

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
                            overrides = indexRepository.trackOverrides(),
                            holdings = holdingStore.all()
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

        /** 解析映射最多等这么久，超时就先用已有的跑。 */
        private const val PROXY_TIMEOUT_MS = 8_000L

        fun factory(context: Context, scope: FundScope): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    FundViewModel(
                        repository = FundRepository(context.applicationContext, scope),
                        estimateRepository = EstimateRepository(context.applicationContext),
                        indexRepository = IndexRepository(context.applicationContext),
                        holdingStore = HoldingStore(context.applicationContext)
                    )
                }
            }
    }
}
