package com.hukua.fundradar.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hukua.fundradar.data.Fund
import com.hukua.fundradar.data.FundExtra
import com.hukua.fundradar.data.FundRepository
import com.hukua.fundradar.data.LimitChange
import com.hukua.fundradar.data.LimitType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val updatedAt: Long = 0L,
    val fromCache: Boolean = false,
    /** True when the user has not added any fund yet. */
    val watchlistEmpty: Boolean = true,
    /** Funds whose purchase limit moved during the last refresh. */
    val changes: List<LimitChange> = emptyList()
) {
    val limitedCount: Int get() = all.count { it.limitType == LimitType.LIMITED }
    val suspendedCount: Int get() = all.count { it.limitType == LimitType.SUSPENDED }
    val openCount: Int get() = all.count { it.limitType == LimitType.OPEN }

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

class FundViewModel(private val repository: FundRepository) : ViewModel() {

    private val _state = MutableStateFlow(
        FundUiState(watchlistEmpty = repository.codes().isEmpty())
    )
    val state: StateFlow<FundUiState> = _state.asStateFlow()

    private val _add = MutableStateFlow(AddFundState())
    val add: StateFlow<AddFundState> = _add.asStateFlow()

    private val _detail = MutableStateFlow<DetailState?>(null)
    val detail: StateFlow<DetailState?> = _detail.asStateFlow()

    private var searchJob: Job? = null
    private var searchSeq = 0

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
        _detail.value = null
        _state.update { current ->
            current.copy(
                all = current.all.filterNot { it.code == code },
                changes = current.changes.filterNot { it.code == code },
                watchlistEmpty = repository.codes().isEmpty()
            ).derive()
        }
    }

    /** Hides the "limit changed" card without touching the data. */
    fun dismissChanges() {
        _state.update { it.copy(changes = emptyList()) }
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
                            changes = result.changes
                        ).derive()
                    }
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
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                FundViewModel(FundRepository(context.applicationContext))
            }
        }
    }
}
