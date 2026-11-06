package com.hukua.fundradar.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hukua.fundradar.data.IndexEntry
import com.hukua.fundradar.data.IndexQuote
import com.hukua.fundradar.data.IndexRepository
import com.hukua.fundradar.data.TrackedIndex
import com.hukua.fundradar.data.WatchlistStore
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class IndexItem(
    val tracked: TrackedIndex,
    val quote: IndexQuote?
)

data class IndexUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val items: List<IndexItem> = emptyList(),
    val error: String? = null,
    /** No watchlist fund *and* no hand added index, i.e. a blank tab. */
    val watchlistEmpty: Boolean = false,
    /** 跟踪标的名，但没在指数目录里匹配到代码 */
    val unresolved: List<String> = emptyList(),
    val updatedAt: Long = 0L
) {
    val manualCount: Int get() = items.count { it.tracked.manual }
}

/** State of the "add index" bottom sheet. */
data class IndexAddState(
    val open: Boolean = false,
    val query: String = "",
    val searching: Boolean = false,
    val results: List<IndexEntry> = emptyList(),
    val adding: String? = null,
    val message: String? = null,
    /** secids already on the tab, shown with a check instead of a + button. */
    val existing: Set<String> = emptySet()
)

class IndexViewModel(context: Context) : ViewModel() {

    private val repository = IndexRepository(context.applicationContext)
    private val watchlist = WatchlistStore(context.applicationContext)

    private val _state = MutableStateFlow(IndexUiState())
    val state: StateFlow<IndexUiState> = _state.asStateFlow()

    private val _add = MutableStateFlow(IndexAddState())
    val add: StateFlow<IndexAddState> = _add.asStateFlow()

    private var searchJob: Job? = null
    private var searchSeq = 0

    init {
        load()
    }

    fun load() {
        val watchlistEmpty = watchlist.codes().isEmpty()

        _state.update {
            it.copy(
                loading = it.items.isEmpty(),
                refreshing = it.items.isNotEmpty(),
                error = null
            )
        }

        viewModelScope.launch {
            runCatching {
                val tracked = repository.tracked()
                val gate = Semaphore(3)
                val quotes = coroutineScope {
                    tracked.items.map { item ->
                        async {
                            gate.withPermit { item to repository.quote(item.entry) }
                        }
                    }.awaitAll().toMap()
                }
                tracked to tracked.items.map { IndexItem(it, quotes[it]) }
            }.onSuccess { (tracked, items) ->
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        items = items,
                        unresolved = tracked.unresolved,
                        error = if (items.isEmpty() && tracked.catalogFailed) {
                            "指数目录下载失败，检查网络后重试"
                        } else {
                            null
                        },
                        watchlistEmpty = watchlistEmpty && items.isEmpty(),
                        updatedAt = System.currentTimeMillis()
                    )
                }
            }.onFailure { throwable ->
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = throwable.message ?: "指数加载失败",
                        watchlistEmpty = watchlistEmpty && it.items.isEmpty()
                    )
                }
            }
        }
    }

    // ------------------------------------------------------- add by hand

    fun openAdd() {
        val existing = _state.value.items.map { it.tracked.entry.secid }.toSet()
        _add.value = IndexAddState(open = true, existing = existing)
    }

    fun closeAdd() {
        searchSeq++
        searchJob?.cancel()
        _add.value = IndexAddState()
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

    fun addIndex(entry: IndexEntry) {
        val state = _add.value
        if (state.adding != null) return
        if (state.existing.contains(entry.secid)) {
            _add.update { it.copy(message = "这个指数已经在列表里了") }
            return
        }

        _add.update { it.copy(adding = entry.secid, message = null) }
        viewModelScope.launch {
            val resolved = repository.addIndex(entry)
            if (resolved == null) {
                _add.update {
                    it.copy(adding = null, message = "没有取到这只指数的行情，检查代码后重试")
                }
                return@launch
            }
            _add.value = IndexAddState()
            load()
        }
    }

    fun removeIndex(secid: String) {
        repository.removeIndex(secid)
        _state.update { current ->
            current.copy(
                items = current.items.filterNot {
                    it.tracked.manual && it.tracked.entry.secid == secid
                },
                watchlistEmpty = watchlist.codes().isEmpty() && current.items.size <= 1
            )
        }
    }

    fun consumeError() {
        _state.update { it.copy(error = null) }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { IndexViewModel(context.applicationContext) }
        }
    }
}
