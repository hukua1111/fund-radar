package com.hukua.fundradar.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hukua.fundradar.data.AiAdvisor
import com.hukua.fundradar.data.AiContext
import com.hukua.fundradar.data.AiContextBuilder
import com.hukua.fundradar.data.AiVerdict
import com.hukua.fundradar.data.IndexEntry
import com.hukua.fundradar.data.KlineRepository
import com.hukua.fundradar.data.KlineSeries
import com.hukua.fundradar.data.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class KlineUiState(
    val code: String = "",
    val name: String = "",
    val secid: String = "",
    /** 默认视窗长度（交易日根数），滑块首次出现时用它。 */
    val range: Int = 120,
    val loading: Boolean = true,
    val error: String? = null,
    val series: KlineSeries? = null,
    /** 当前画面是不是磁盘缓存，没发过网络请求。 */
    val fromCache: Boolean = false,
    /** 本地缓存的交易日根数。 */
    val bars: Int = 0,
    /** 最近一次增量补进来的根数。 */
    val added: Int = 0,
    val fetchedAt: Long = 0L,
    val judging: Boolean = false,
    /** 正在抓大盘/重仓/账户数据，还没开始请求模型。 */
    val preparing: Boolean = false,
    /** 这一轮给 AI 带上了哪些数据，比如「大盘 6 个指数 · 重仓 10 只」。 */
    val contextSummary: String = "",
    val verdict: AiVerdict? = null,
    val verdictError: String? = null,
    val aiConfigured: Boolean = false
)

/** K line + AI swing judgement for one index. */
class KlineViewModel(
    private val context: Context,
    private val secid: String,
    private val code: String,
    private val indexName: String,
    private val settings: SettingsStore,
    private val repository: KlineRepository
) : ViewModel() {

    private val _state = MutableStateFlow(
        KlineUiState(
            code = code,
            name = indexName,
            secid = secid,
            aiConfigured = settings.load().configured
        )
    )
    val state: StateFlow<KlineUiState> = _state.asStateFlow()

    private var activeJob: Job? = null

    init {
        // 磁盘上有就直接画出来，再去补最新的几根。
        repository.cached(secid)?.let { cached ->
            val series = cached.series
            if (series != null) {
                _state.update {
                    it.copy(
                        loading = false,
                        series = series,
                        name = it.name.ifBlank { series.name },
                        fromCache = true,
                        bars = series.candles.size,
                        fetchedAt = cached.fetchedAt
                    )
                }
            }
        }
        load()
    }

    fun load(force: Boolean = false) {
        if (!force && activeJob?.isActive == true) return
        if (_state.value.series == null) {
            _state.update { it.copy(loading = true, error = null) }
        }
        activeJob = viewModelScope.launch {
            val result = repository.load(secid, force)
            val series = result.series
            _state.update {
                it.copy(
                    loading = false,
                    series = series ?: it.series,
                    name = it.name.ifBlank { series?.name.orEmpty() },
                    fromCache = result.fromCache,
                    bars = (series ?: it.series)?.candles?.size ?: 0,
                    added = result.added,
                    fetchedAt = result.fetchedAt.takeIf { time -> time > 0 } ?: it.fetchedAt,
                    error = result.error ?: if (series == null) "没有取到指数行情" else null
                )
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

        _state.update { it.copy(judging = true, preparing = true, verdictError = null, aiConfigured = true) }
        viewModelScope.launch {
            // 先把「大盘 → 板块 → 个股 → 账户」的数据凑齐，再交给模型。
            // 任何一个来源失败都不阻断判断，缺什么会在提示词里写清楚。
            val aiContext = runCatching {
                withTimeoutOrNull(CONTEXT_TIMEOUT_MS) {
                    AiContextBuilder(context).build(entry(), series)
                }
            }.getOrNull() ?: AiContext()

            val summary = buildList {
                if (aiContext.pulse.isNotEmpty()) add("大盘 ${aiContext.pulse.size} 个指数")
                if (aiContext.constituents.isNotEmpty()) add("重仓 ${aiContext.constituents.size} 只")
                if (aiContext.accounts.isNotEmpty()) add("账户 ${aiContext.accounts.size} 只基金")
                if (aiContext.relatedFunds.isNotEmpty()) add("关联基金 ${aiContext.relatedFunds.size} 只")
            }.joinToString(" · ")

            _state.update { it.copy(preparing = false, contextSummary = summary) }

            runCatching {
                AiAdvisor.judge(config, current.name, code, series, subject = "指数", context = aiContext)
            }
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

    /** 把 `1.000300` 拆回 IndexEntry，上下文构建时要用代码再查一次行情。 */
    private fun entry(): IndexEntry {
        val market = secid.substringBefore('.').toIntOrNull() ?: 1
        return IndexEntry(code, market, indexName.ifBlank { code })
    }

    fun consumeError() {
        _state.update { it.copy(verdictError = null) }
    }

    companion object {
        /** 首次拉取的历史长度；之后只补最后几根。 */
        const val HISTORY = 250

        /** 凑上下文最多等这么久，超时就用手上已有的数据先问模型。 */
        private const val CONTEXT_TIMEOUT_MS = 15_000L

        fun factory(
            context: Context,
            secid: String,
            code: String,
            name: String
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val appContext = context.applicationContext
                KlineViewModel(
                    context = appContext,
                    secid = secid,
                    code = code,
                    indexName = name,
                    settings = SettingsStore(appContext),
                    repository = KlineRepository(appContext)
                )
            }
        }
    }
}
