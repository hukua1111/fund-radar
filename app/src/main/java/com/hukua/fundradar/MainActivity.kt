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
import com.hukua.fundradar.data.DcaPlan
import com.hukua.fundradar.data.FundScope
import com.hukua.fundradar.data.IndexEntry
import com.hukua.fundradar.data.SettingsStore
import com.hukua.fundradar.data.isTradingTime
import com.hukua.fundradar.ui.AppBottomBar
import com.hukua.fundradar.ui.DcaDetailScreen
import com.hukua.fundradar.ui.DcaPlanSheet
import com.hukua.fundradar.ui.DcaScreen
import com.hukua.fundradar.ui.DcaViewModel
import com.hukua.fundradar.ui.FundDetailScreen
import com.hukua.fundradar.ui.FundRadarScreen
import com.hukua.fundradar.ui.FundViewModel
import com.hukua.fundradar.ui.HoldingMode
import com.hukua.fundradar.ui.HoldingSheet
import com.hukua.fundradar.ui.IndexViewModel
import com.hukua.fundradar.ui.KlineViewModel
import com.hukua.fundradar.ui.SettingsScreen
import com.hukua.fundradar.ui.SignalScreen
import com.hukua.fundradar.ui.SignalViewModel
import com.hukua.fundradar.ui.TAB_DCA
import com.hukua.fundradar.ui.TAB_LIMIT
import com.hukua.fundradar.ui.TAB_PORTFOLIO
import com.hukua.fundradar.ui.TAB_SIGNAL
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

/** 信号页交易时段重算间隔：K 线缓存盘中 2 分钟 TTL，1 分钟一轮足够跟上。 */
private const val SIGNAL_TICK_MS = 60_000L

/** 非交易时段信号页重算间隔，只为发现「又开盘了 / 收盘定型了」。 */
private const val SIGNAL_IDLE_TICK_MS = 5 * 60_000L

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
                // 定投页自己的清单：和持仓、限购互不共享，但持仓记录仍读同一份。
                val dcaFundViewModel: FundViewModel = viewModel(
                    key = "fund-dca",
                    factory = FundViewModel.factory(this, FundScope.DCA)
                )
                val dcaViewModel: DcaViewModel = viewModel(factory = DcaViewModel.factory(this))
                val indexViewModel: IndexViewModel = viewModel(factory = IndexViewModel.factory(this))
                val signalViewModel: SignalViewModel = viewModel(factory = SignalViewModel.factory(this))

                val portfolioDetail by portfolioViewModel.detail.collectAsStateWithLifecycle()
                val limitDetail by limitViewModel.detail.collectAsStateWithLifecycle()
                val portfolioState by portfolioViewModel.state.collectAsStateWithLifecycle()
                val limitState by limitViewModel.state.collectAsStateWithLifecycle()
                val portfolioTrackPicker by portfolioViewModel.trackPicker.collectAsStateWithLifecycle()
                val limitTrackPicker by limitViewModel.trackPicker.collectAsStateWithLifecycle()
                val portfolioHoldingSheet by portfolioViewModel.holdingSheet.collectAsStateWithLifecycle()
                val limitHoldingSheet by limitViewModel.holdingSheet.collectAsStateWithLifecycle()
                val indexState by indexViewModel.state.collectAsStateWithLifecycle()

                val dcaFundState by dcaFundViewModel.state.collectAsStateWithLifecycle()
                val dcaState by dcaViewModel.state.collectAsStateWithLifecycle()
                val dcaHoldingSheet by dcaFundViewModel.holdingSheet.collectAsStateWithLifecycle()

                var route by rememberSaveable { mutableStateOf("list") }
                var tab by rememberSaveable { mutableStateOf(TAB_PORTFOLIO) }
                var settingsReturn by rememberSaveable { mutableStateOf("list") }
                var planSheetCode by rememberSaveable { mutableStateOf<String?>(null) }
                var planSheetName by rememberSaveable { mutableStateOf("") }

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
                        "dca" -> {
                            dcaViewModel.closeDetail()
                            route = "list"
                        }
                        else -> route = "list"
                    }
                }

                // 指数不再单独占一条横滑栏（基金卡片和详情里已经有跟踪标的），
                // 但详情页的「看这个指数的 K 线」仍要知道这只基金对应哪个指数，
                // 所以进持仓页时后台解析一次「基金 → 指数」，不再跟着估算轮询。
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                LaunchedEffect(tab, route, lifecycle) {
                    if (route != "list" && route != "dca") return@LaunchedEffect
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

                        // 定投页的估算跟持仓页同一套：卡片上的涨跌、持仓收益、止盈判断
                        // 都要用到，所以也按 5 秒一轮走。
                        TAB_DCA -> lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                            while (true) {
                                dcaFundViewModel.refreshEstimates()
                                delay(if (isTradingTime()) LIST_TICK_MS else LIST_IDLE_TICK_MS)
                            }
                        }

                        // 信号页实时口径：盘中含当日实时 bar，交易时段每分钟重算
                        // （K 线缓存 2 分钟 TTL，实际网络请求约两分钟一次）；
                        // 非交易时段降频，只为发现开盘 / 收盘定型。手动刷新走页内按钮。
                        TAB_SIGNAL -> lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                            while (true) {
                                signalViewModel.load()
                                delay(if (isTradingTime()) SIGNAL_TICK_MS else SIGNAL_IDLE_TICK_MS)
                            }
                        }
                    }
                }

                // 定投详情页里记完一笔买卖，结论要跟着新的成本走 —— 列表页的
                // 重算挂在 DcaScreen 里，详情页不在组合中，这里补一次。
                LaunchedEffect(route, dcaFundState.holdings, dcaFundState.all) {
                    if (route == "dca") {
                        dcaViewModel.evaluate(
                            dcaFundState.all,
                            dcaFundState.holdings,
                            dcaFundState.estimates
                        )
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

                    "dca" -> {
                        val code = dcaState.selected
                        val fund = dcaFundState.all.firstOrNull { it.code == code }
                            ?: dcaState.rowOf(code)?.fund
                        if (fund == null) {
                            LaunchedEffect(Unit) { route = "list" }
                        } else {
                            DcaDetailScreen(
                                fund = fund,
                                row = dcaState.rowOf(fund.code),
                                estimate = dcaFundState.estimates[fund.code],
                                advice = dcaState.advice[fund.code],
                                holding = dcaFundState.holdings[fund.code]
                                    ?.takeUnless { it.isEmpty },
                                aiLoading = dcaState.aiLoading == fund.code,
                                aiError = dcaState.aiError[fund.code],
                                onBack = {
                                    dcaViewModel.closeDetail()
                                    route = "list"
                                },
                                onAskAi = { dcaViewModel.askAi(fund.code) },
                                onEditPlan = {
                                    planSheetCode = fund.code
                                    planSheetName = fund.name
                                },
                                onRemove = {
                                    // 只从定投清单移除，持仓记录留在原处。
                                    dcaFundViewModel.removeFromList(fund.code)
                                    dcaViewModel.forget(fund.code)
                                    route = "list"
                                },
                                onBuy = { dcaFundViewModel.openHoldingSheet(fund, HoldingMode.BUY) },
                                onSell = { dcaFundViewModel.openHoldingSheet(fund, HoldingMode.SELL) },
                                onEditHolding = {
                                    dcaFundViewModel.openHoldingSheet(fund, HoldingMode.EDIT)
                                },
                                onRemoveHolding = { dcaFundViewModel.removeHolding(fund.code) }
                            )

                            if (dcaHoldingSheet.open) {
                                HoldingSheet(
                                    state = dcaHoldingSheet,
                                    onModeChange = dcaFundViewModel::onHoldingModeChange,
                                    onSharesChange = dcaFundViewModel::onHoldingSharesChange,
                                    onCostChange = dcaFundViewModel::onHoldingCostChange,
                                    onAmountChange = dcaFundViewModel::onHoldingAmountChange,
                                    onNavChange = dcaFundViewModel::onHoldingNavChange,
                                    onFillAll = dcaFundViewModel::fillAllShares,
                                    onSubmit = dcaFundViewModel::submitHolding,
                                    onDismiss = dcaFundViewModel::closeHoldingSheet,
                                    onCostInputChange = dcaFundViewModel::onHoldingCostInputChange
                                )
                            }

                            val sheetCode = planSheetCode
                            if (sheetCode != null) {
                                DcaPlanSheet(
                                    fundName = planSheetName,
                                    initial = dcaState.plans[sheetCode] ?: DcaPlan(),
                                    onSave = { plan ->
                                        dcaViewModel.savePlan(sheetCode, plan)
                                        planSheetCode = null
                                    },
                                    onDismiss = { planSheetCode = null }
                                )
                            }
                        }
                    }

                    else -> when (tab) {
                        TAB_DCA -> DcaScreen(
                            fundViewModel = dcaFundViewModel,
                            dcaViewModel = dcaViewModel,
                            onOpenSettings = openSettings,
                            onOpenDetail = { fund ->
                                dcaViewModel.openDetail(fund.code)
                                route = "dca"
                            },
                            bottomBar = bottomBar
                        )

                        TAB_LIMIT -> FundRadarScreen(
                            viewModel = limitViewModel,
                            onOpenSettings = openSettings,
                            onOpenDetail = { fund ->
                                limitViewModel.openDetail(fund)
                                route = "fund"
                            },
                            bottomBar = bottomBar
                        )

                        TAB_SIGNAL -> SignalScreen(
                            viewModel = signalViewModel,
                            onOpenSettings = openSettings,
                            onOpenDetail = { fund ->
                                portfolioViewModel.openDetail(fund)
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
