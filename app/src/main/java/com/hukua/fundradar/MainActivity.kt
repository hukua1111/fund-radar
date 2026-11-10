package com.hukua.fundradar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hukua.fundradar.data.FundScope
import com.hukua.fundradar.data.IndexEntry
import com.hukua.fundradar.data.SettingsStore
import com.hukua.fundradar.data.isTradingTime
import com.hukua.fundradar.ui.AppBottomBar
import com.hukua.fundradar.ui.FundDetailScreen
import com.hukua.fundradar.ui.FundRadarScreen
import com.hukua.fundradar.ui.FundViewModel
import com.hukua.fundradar.ui.HoldingMode
import com.hukua.fundradar.ui.HoldingSheet
import com.hukua.fundradar.ui.IndexViewModel
import com.hukua.fundradar.ui.KlineViewModel
import com.hukua.fundradar.ui.SettingsScreen
import com.hukua.fundradar.ui.TAB_LIMIT
import com.hukua.fundradar.ui.TAB_PORTFOLIO
import com.hukua.fundradar.ui.TrackIndexSheet
import com.hukua.fundradar.ui.theme.FundRadarTheme
import com.hukua.fundradar.work.DailyScheduler
import kotlinx.coroutines.delay

/** 交易时段列表刷新间隔：指数行情一次批量才 0.4 KB。 */
private const val LIST_TICK_MS = 5_000L

/** 收盘 / 午休后的列表刷新间隔，只为发现「又开盘了」。 */
private const val LIST_IDLE_TICK_MS = 60_000L

/** 停留在详情页时，单只指数的刷新间隔（一次请求约 0.3 KB）。 */
private const val DETAIL_TICK_MS = 3_000L

private const val DETAIL_IDLE_TICK_MS = 30_000L

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Keeps the daily job alive after a reinstall or cache clear.
        runCatching { DailyScheduler.apply(this, SettingsStore(this).load()) }

        setContent {
            FundRadarTheme {
                // Two independent lists, one view model each.
                val portfolioViewModel: FundViewModel = viewModel(
                    key = "fund-portfolio",
                    factory = FundViewModel.factory(this, FundScope.PORTFOLIO)
                )
                val limitViewModel: FundViewModel = viewModel(
                    key = "fund-limit",
                    factory = FundViewModel.factory(this, FundScope.LIMIT)
                )
                val indexViewModel: IndexViewModel = viewModel(factory = IndexViewModel.factory(this))

                val portfolioDetail by portfolioViewModel.detail.collectAsStateWithLifecycle()
                val limitDetail by limitViewModel.detail.collectAsStateWithLifecycle()
                val portfolioState by portfolioViewModel.state.collectAsStateWithLifecycle()
                val limitState by limitViewModel.state.collectAsStateWithLifecycle()
                val portfolioTrackPicker by portfolioViewModel.trackPicker.collectAsStateWithLifecycle()
                val limitTrackPicker by limitViewModel.trackPicker.collectAsStateWithLifecycle()
                val portfolioHoldingSheet by portfolioViewModel.holdingSheet.collectAsStateWithLifecycle()
                val limitHoldingSheet by limitViewModel.holdingSheet.collectAsStateWithLifecycle()
                val indexState by indexViewModel.state.collectAsStateWithLifecycle()

                var route by rememberSaveable { mutableStateOf("list") }
                var tab by rememberSaveable { mutableStateOf(TAB_PORTFOLIO) }
                var settingsReturn by rememberSaveable { mutableStateOf("list") }

                val isLimitTab = tab == TAB_LIMIT
                val activeViewModel = if (isLimitTab) limitViewModel else portfolioViewModel
                val detail = if (isLimitTab) limitDetail else portfolioDetail

                val openSettings: () -> Unit = {
                    settingsReturn = route
                    route = "settings"
                }

                // Detail is a page of its own now, not a bottom sheet: back from
                // it lands on the fund list, never on a stale sheet.
                val backToList: () -> Unit = {
                    activeViewModel.closeDetail()
                    route = "list"
                }

                BackHandler(enabled = route != "list") {
                    when (route) {
                        "settings" -> route = settingsReturn
                        "fund" -> backToList()
                        else -> route = "list"
                    }
                }

                // 指数不再单独占一条横滑栏（基金卡片和详情里已经有跟踪标的），
                // 但详情页的「看这个指数的 K 线」仍要知道这只基金对应哪个指数，
                // 所以进持仓页时后台解析一次「基金 → 指数」，不再跟着估算轮询。
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                LaunchedEffect(tab, route, lifecycle) {
                    if (route != "list") return@LaunchedEffect
                    when (tab) {
                        TAB_PORTFOLIO -> lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                            indexViewModel.load()
                            // 指数行情 5 秒一轮：一次是东财批量（40 只 0.4 KB），
                            // 一次是腾讯的 ETF/重仓股批量，两家的公开接口都扛得住
                            // 这个频率（实测连发 40 次腾讯 0 失败、东财单主机偶发
                            // 空回复，已做主机轮换与备用源）。非交易时段降到 60 秒。
                            while (true) {
                                portfolioViewModel.refreshEstimates()
                                delay(if (isTradingTime()) LIST_TICK_MS else LIST_IDLE_TICK_MS)
                            }
                        }
                    }
                }

                val bottomBar: @Composable () -> Unit = {
                    AppBottomBar(selected = tab, onSelect = { tab = it })
                }

                when (route) {
                    "settings" -> SettingsScreen(onBack = { route = settingsReturn })

                    "fund" -> {
                        val state = detail
                        if (state == null) {
                            // Removed from the list (or restored without a
                            // selection): nothing to show, fall back.
                            LaunchedEffect(Unit) { route = "list" }
                        } else {
                            val overrides = if (isLimitTab) limitState.overrides else portfolioState.overrides
                            val picker = if (isLimitTab) limitTrackPicker else portfolioTrackPicker
                            val holdingSheet = if (isLimitTab) limitHoldingSheet else portfolioHoldingSheet
                            // 详情页里「看 K 线」要知道这只基金对应的指数：
                            // 先看手动指定，再看后台解析出来的映射。
                            val klineEntry = overrides[state.fund.code]
                                ?: indexState.entryFor(state.fund.code)?.entry

                            // K 线直接铺在详情页里：为这只基金对应的指数建一个
                            // view model，进页面就加载，返回列表就随页面一起停。
                            val klineViewModel: KlineViewModel? = klineEntry?.let { entry ->
                                viewModel(
                                    key = "kline-${entry.secid}",
                                    factory = KlineViewModel.factory(
                                        this,
                                        entry.secid,
                                        entry.code,
                                        entry.name.ifBlank { state.fund.trackIndex }
                                    )
                                )
                            }
                            val klineState = klineViewModel?.state?.collectAsStateWithLifecycle()

                            // 停留在详情页时每 3 秒拉一次这个指数的点位；
                            // 收盘后拉到 30 秒一次就够。
                            LaunchedEffect(klineViewModel) {
                                val vm = klineViewModel ?: return@LaunchedEffect
                                var ticks = 0
                                while (true) {
                                    vm.refreshQuote()
                                    // 盘中半小时补一次当日 K 线，量很小（只补最后几根）。
                                    if (ticks > 0 && ticks % 10 == 0 && isTradingTime()) {
                                        vm.load(force = true)
                                    }
                                    // 估值本身按 15 秒走一轮就够，指数点位才是 3 秒。
                                    if (ticks > 0 && ticks % 5 == 0) {
                                        activeViewModel.refreshEstimates()
                                    }
                                    ticks++
                                    delay(if (isTradingTime()) DETAIL_TICK_MS else DETAIL_IDLE_TICK_MS)
                                }
                            }

                            FundDetailScreen(
                                state = state,
                                onBack = backToList,
                                onRemove = { code ->
                                    activeViewModel.removeFund(code)
                                    route = "list"
                                },
                                estimate = if (isLimitTab) {
                                    null
                                } else {
                                    portfolioState.estimates[state.fund.code]
                                },
                                showLimitBadge = isLimitTab,
                                removeLabel = if (isLimitTab) "从限购清单移除" else "从持仓移除",
                                trackOverride = overrides[state.fund.code],
                                trackedIndex = klineEntry,
                                klineState = klineState?.value,
                                onKlineRange = { range -> klineViewModel?.setRange(range) },
                                onKlineRefresh = { klineViewModel?.load(force = true) },
                                onKlineJudge = { klineViewModel?.judge() },
                                onOpenSettings = openSettings,
                                onEditTrack = { activeViewModel.openTrackPicker(state.fund) },
                                onClearTrack = { activeViewModel.clearTrack(state.fund.code) },
                                showFundFacts = isLimitTab,
                                holding = if (isLimitTab) null else {
                                    portfolioState.holdings[state.fund.code]
                                },
                                onBuy = {
                                    if (!isLimitTab) {
                                        activeViewModel.openHoldingSheet(state.fund, HoldingMode.BUY)
                                    }
                                },
                                onSell = {
                                    if (!isLimitTab) {
                                        activeViewModel.openHoldingSheet(state.fund, HoldingMode.SELL)
                                    }
                                },
                                onEditHolding = {
                                    if (!isLimitTab) {
                                        activeViewModel.openHoldingSheet(state.fund, HoldingMode.EDIT)
                                    }
                                },
                                onRemoveHolding = { activeViewModel.removeHolding(state.fund.code) }
                            )

                            if (holdingSheet.open) {
                                HoldingSheet(
                                    state = holdingSheet,
                                    onModeChange = activeViewModel::onHoldingModeChange,
                                    onSharesChange = activeViewModel::onHoldingSharesChange,
                                    onCostChange = activeViewModel::onHoldingCostChange,
                                    onAmountChange = activeViewModel::onHoldingAmountChange,
                                    onNavChange = activeViewModel::onHoldingNavChange,
                                    onFillAll = activeViewModel::fillAllShares,
                                    onSubmit = activeViewModel::submitHolding,
                                    onDismiss = activeViewModel::closeHoldingSheet,
                                    onCostInputChange = activeViewModel::onHoldingCostInputChange
                                )
                            }

                            if (picker.open) {
                                TrackIndexSheet(
                                    state = picker,
                                    onQueryChange = activeViewModel::onTrackQueryChange,
                                    onPick = activeViewModel::assignTrack,
                                    onClearCurrent = {
                                        val code = picker.fundCode
                                        activeViewModel.closeTrackPicker()
                                        activeViewModel.clearTrack(code)
                                    },
                                    onDismiss = activeViewModel::closeTrackPicker
                                )
                            }
                        }
                    }

                    else -> when (tab) {
                        TAB_LIMIT -> FundRadarScreen(
                            viewModel = limitViewModel,
                            onOpenSettings = openSettings,
                            onOpenDetail = { fund ->
                                limitViewModel.openDetail(fund)
                                route = "fund"
                            },
                            bottomBar = bottomBar
                        )

                        else -> {
                            FundRadarScreen(
                                viewModel = portfolioViewModel,
                                onOpenSettings = openSettings,
                                onOpenDetail = { fund ->
                                    portfolioViewModel.openDetail(fund)
                                    route = "fund"
                                },
                                bottomBar = bottomBar
                            )
                        }
                    }
                }
            }
        }
    }
}
