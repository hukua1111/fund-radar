package com.hukua.fundradar.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hukua.fundradar.data.IndexQuote
import com.hukua.fundradar.data.IndexRepository
import com.hukua.fundradar.data.TrackedIndex
import com.hukua.fundradar.data.WatchlistStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    val watchlistEmpty: Boolean = false,
    /** 跟踪标的名，但没在指数目录里匹配到代码 */
    val unresolved: List<String> = emptyList(),
    val updatedAt: Long = 0L
)

class IndexViewModel(context: Context) : ViewModel() {

    private val repository = IndexRepository(context.applicationContext)
    private val watchlist = WatchlistStore(context.applicationContext)

    private val _state = MutableStateFlow(IndexUiState())
    val state: StateFlow<IndexUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        val codes = watchlist.codes()
        if (codes.isEmpty()) {
            _state.update {
                it.copy(loading = false, refreshing = false, watchlistEmpty = true, items = emptyList())
            }
            return
        }

        _state.update {
            it.copy(loading = it.items.isEmpty(), refreshing = it.items.isNotEmpty(), error = null)
        }

        viewModelScope.launch {
            runCatching {
                val tracked = repository.tracked()
                val gate = Semaphore(3)
                val quotes = coroutineScope {
                    tracked.map { item ->
                        async {
                            gate.withPermit { item to repository.quote(item.entry) }
                        }
                    }.awaitAll().toMap()
                }
                tracked.map { IndexItem(it, quotes[it]) }
            }.onSuccess { items ->
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        items = items,
                        error = null,
                        watchlistEmpty = false,
                        updatedAt = System.currentTimeMillis()
                    )
                }
            }.onFailure { throwable ->
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = throwable.message ?: "指数加载失败"
                    )
                }
            }
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
