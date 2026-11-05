package com.hukua.fundradar.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hukua.fundradar.data.AiAdvisor
import com.hukua.fundradar.data.AiVerdict
import com.hukua.fundradar.data.EastMoneyApi
import com.hukua.fundradar.data.KlineSeries
import com.hukua.fundradar.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class KlineUiState(
    val code: String = "",
    val name: String = "",
    val range: Int = 120,
    val loading: Boolean = true,
    val error: String? = null,
    val series: KlineSeries? = null,
    val judging: Boolean = false,
    val verdict: AiVerdict? = null,
    val verdictError: String? = null,
    val aiConfigured: Boolean = false
)

class KlineViewModel(
    private val code: String,
    private val fundName: String,
    private val settings: SettingsStore
) : ViewModel() {

    private val _state = MutableStateFlow(
        KlineUiState(
            code = code,
            name = fundName,
            aiConfigured = settings.load().configured
        )
    )
    val state: StateFlow<KlineUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { EastMoneyApi.fetchKline(code, HISTORY) }
                .onSuccess { series ->
                    _state.update {
                        it.copy(
                            loading = false,
                            series = series,
                            name = series?.name?.takeIf { n -> n.isNotBlank() } ?: it.name,
                            error = if (series == null) "没有取到行情数据" else null
                        )
                    }
                }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = throwable.message ?: "行情加载失败"
                        )
                    }
                }
        }
    }

    fun setRange(range: Int) {
        _state.update { it.copy(range = range) }
    }

    fun judge() {
        val current = _state.value
        val series = current.series ?: return
        if (current.judging) return

        val config = settings.load()
        if (!config.configured) {
            _state.update {
                it.copy(aiConfigured = false, verdictError = "请先在设置里填写 AI 地址、模型和 Key")
            }
            return
        }

        _state.update { it.copy(judging = true, verdictError = null, aiConfigured = true) }
        viewModelScope.launch {
            runCatching { AiAdvisor.judge(config, current.name, code, series) }
                .onSuccess { verdict ->
                    _state.update { it.copy(judging = false, verdict = verdict) }
                }
                .onFailure { throwable ->
                    _state.update {
                        it.copy(judging = false, verdictError = throwable.message ?: "AI 判断失败")
                    }
                }
        }
    }

    fun consumeError() {
        _state.update { it.copy(verdictError = null) }
    }

    companion object {
        /** Fetched once; the range selector only slices this locally. */
        const val HISTORY = 250

        fun factory(context: Context, code: String, name: String): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    KlineViewModel(code, name, SettingsStore(context.applicationContext))
                }
            }
    }
}
