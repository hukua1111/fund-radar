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
import com.hukua.fundradar.ui.AddIndexSheet
import com.hukua.fundradar.ui.FundDetailScreen
import com.hukua.fundradar.ui.FundRadarScreen
import com.hukua.fundradar.ui.FundViewModel
import com.hukua.fundradar.ui.HoldingMode
import com.hukua.fundradar.ui.HoldingSheet
import com.hukua.fundradar.ui.IndexViewModel
import com.hukua.fundradar.ui.KlineScreen
import com.hukua.fundradar.ui.KlineViewModel
import com.hukua.fundradar.ui.SettingsScreen
import com.hukua.fundradar.ui.TAB_LIMIT
import com.hukua.fundradar.ui.TAB_PORTFOLIO
import com.hukua.fundradar.ui.TrackIndexSheet
import com.hukua.fundradar.ui.theme.FundRadarTheme
import com.hukua.fundradar.work.DailyScheduler
import kotlinx.coroutines.delay

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
                val indexAdd by indexViewModel.add.collectAsStateWithLifecycle()

                var route by rememberSaveable { mutableStateOf("list") }
                var tab by rememberSaveable { mutableStateOf(TAB_PORTFOLIO) }
                var settingsReturn by rememberSaveable { mutableStateOf("list") }
                var klineSecid by rememberSaveable { mutableStateOf("") }
                var klineCode by rememberSaveable { mutableStateOf("") }
                var klineName by rememberSaveable { mutableStateOf("") }
                // 从详情页进 K 线时，返回要回到那只基金，而不是基金列表。
                var klineReturn by rememberSaveable { mutableStateOf("list") }

                val isLimitTab = tab == TAB_LIMIT
                val activeViewModel = if (isLimitTab) limitViewModel else portfolioViewModel
                val detail = if (isLimitTab) limitDetail else portfolioDetail

                val openKline: (IndexEntry, String) -> Unit = { entry, trackedName ->
                    klineReturn = route
                    klineSecid = entry.secid
                    klineCode = entry.code
                    klineName = entry.name.ifBlank { trackedName }
                    route = "kline"
                }

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
                        "kline" -> route = klineReturn
                        else -> route = "list"
                    }
                }

                // 持仓页上的指数行情条和盘中估算一样是活的：进页面拉一次，
                // 留在页面上时按估值同样的节奏顺带刷新指数（指数条只有几条，
                // 3 分钟一次足够，不必跟着 60 秒的估算跑）。
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                LaunchedEffect(tab, route, lifecycle) {
                    if (route != "list") return@LaunchedEffect
                    when (tab) {
                        TAB_PORTFOLIO -> lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                            var ticks = 0
                            indexViewModel.load()
                            while (true) {
                                portfolioViewModel.refreshEstimates()
                                if (ticks > 0 && ticks % 3 == 0) indexViewModel.load()
                                ticks++
                                delay(if (isTradingTime()) 60_000L else 5 * 60_000L)
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
                            // 先看手动指定，再从指数条已解析好的列表里找。
                            val klineEntry = overrides[state.fund.code]
                                ?: indexState.items
                                    .firstOrNull { it.tracked.fundCodes.contains(state.fund.code) }
                                    ?.tracked?.entry

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
                                onOpenKline = klineEntry?.let { entry ->
                                    { openKline(entry, state.fund.trackIndex) }
                                },
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

                    "kline" -> {
                        val klineViewModel: KlineViewModel = viewModel(
                            key = "kline-$klineSecid",
                            factory = KlineViewModel.factory(this, klineSecid, klineCode, klineName)
                        )
                        KlineScreen(
                            viewModel = klineViewModel,
                            onBack = { route = klineReturn },
                            onOpenSettings = openSettings
                        )
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
                                indices = indexState,
                                onOpenKline = openKline,
                                onAddIndex = indexViewModel::openAdd,
                                onRemoveIndex = indexViewModel::removeIndex,
                                onRefreshIndices = indexViewModel::load,
                                bottomBar = bottomBar
                            )

                            if (indexAdd.open) {
                                AddIndexSheet(
                                    state = indexAdd,
                                    onQueryChange = indexViewModel::onQueryChange,
                                    onAddIndex = indexViewModel::addIndex,
                                    onDismiss = indexViewModel::closeAdd
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
