package com.hukua.fundradar.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hukua.fundradar.data.IndexRepository
import com.hukua.fundradar.data.TrackedIndex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「基金 → 跟踪指数」的解析结果。
 *
 * 「相关指数」横滑条已经去掉：每只基金的卡片脚注和详情页都已经写了自己跟踪哪个
 * 指数，再单独列一条只是重复。这里保留的只有映射关系 —— 基金详情里的
 * 「看这个指数的 K 线」需要知道点开的是哪个 secid。
 */
data class IndexUiState(
    val loading: Boolean = true,
    val tracked: List<TrackedIndex> = emptyList(),
    val updatedAt: Long = 0L
) {
    /** 这只基金对应的指数；没认出来就是 null。 */
    fun entryFor(fundCode: String): TrackedIndex? =
        tracked.firstOrNull { it.fundCodes.contains(fundCode) }
}

class IndexViewModel(context: Context) : ViewModel() {

    private val repository = IndexRepository(context.applicationContext)

    private val _state = MutableStateFlow(IndexUiState())
    val state: StateFlow<IndexUiState> = _state.asStateFlow()

    init {
        load()
    }

    /**
     * 只解析名字，不取行情：行情是给横滑条看的，条子没了就不用再为十几个指数
     * 各发一次 K 线请求。
     */
    fun load() {
        _state.value = _state.value.copy(loading = _state.value.tracked.isEmpty())

        viewModelScope.launch {
            val result = runCatching { repository.tracked() }.getOrNull()
            _state.value = if (result == null) {
                IndexUiState(loading = false, tracked = _state.value.tracked)
            } else {
                IndexUiState(
                    loading = false,
                    tracked = result.items,
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { IndexViewModel(context.applicationContext) }
        }
    }
}
