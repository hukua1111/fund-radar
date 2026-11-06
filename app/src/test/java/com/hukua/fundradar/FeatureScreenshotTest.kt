package com.hukua.fundradar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import com.hukua.fundradar.data.AiConfig
import com.hukua.fundradar.data.AiVerdict
import com.hukua.fundradar.data.Candle
import com.hukua.fundradar.data.Estimate
import com.hukua.fundradar.data.EstimateSource
import com.hukua.fundradar.data.Fund
import com.hukua.fundradar.data.FundScope
import com.hukua.fundradar.data.FundExtra
import com.hukua.fundradar.data.Holding
import com.hukua.fundradar.data.IndexEntry
import com.hukua.fundradar.data.IndexQuote
import com.hukua.fundradar.data.KlineSeries
import com.hukua.fundradar.data.KlineSource
import com.hukua.fundradar.data.SwingAction
import com.hukua.fundradar.data.TrackedIndex
import com.hukua.fundradar.ui.AddIndexSheetContent
import com.hukua.fundradar.ui.CostInput
import com.hukua.fundradar.ui.DetailState
import com.hukua.fundradar.ui.FundDetailScreen
import com.hukua.fundradar.ui.FundRadarContent
import com.hukua.fundradar.ui.FundUiState
import com.hukua.fundradar.ui.HoldingMode
import com.hukua.fundradar.ui.HoldingSheetContent
import com.hukua.fundradar.ui.HoldingSheetState
import com.hukua.fundradar.ui.IndexAddState
import com.hukua.fundradar.ui.IndexItem
import com.hukua.fundradar.ui.IndexStrip
import com.hukua.fundradar.ui.IndexUiState
import com.hukua.fundradar.ui.KlineContent
import com.hukua.fundradar.ui.KlineUiState
import com.hukua.fundradar.ui.SettingsContent
import com.hukua.fundradar.ui.TrackIndexSheetContent
import com.hukua.fundradar.ui.TrackPickerState
import com.hukua.fundradar.ui.theme.FundRadarTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Screenshots for the K line and settings screens. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class FeatureScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun klineScreenWithVerdict() {
        compose.setContent {
            FundRadarTheme(darkTheme = false) {
                KlineContent(state = sampleState(verdict = sampleVerdict()))
            }
        }
        compose.waitForIdle()
        save("kline")
        dump("tree-kline.txt")
    }

    @Test
    fun klineScreenDark() {
        compose.setContent {
            FundRadarTheme(darkTheme = true) {
                KlineContent(state = sampleState(verdict = sampleVerdict()))
            }
        }
        compose.waitForIdle()
        save("kline-dark")
    }

    @Test
    fun klineScreenWithoutAi() {
        compose.setContent {
            FundRadarTheme(darkTheme = false) {
                KlineContent(state = sampleState(verdict = null, aiConfigured = false))
            }
        }
        compose.waitForIdle()
        save("kline-no-ai")
    }

    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun settingsScreen() {
        compose.setContent {
            FundRadarTheme(darkTheme = false) {
                SettingsContent(
                    config = AiConfig(
                        baseUrl = "https://api.deepseek.com/v1",
                        apiKey = "sk-demo-key",
                        model = "deepseek-chat",
                        barkUrl = "https://api.day.app/demoKey123",
                        dailyEnabled = true,
                        hour = 14,
                        minute = 35
                    )
                )
            }
        }
        compose.waitForIdle()
        save("settings")
        dump("tree-settings.txt")
    }

    @Test
    @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    fun portfolioWithIndexStrip() {
        // 指数并进持仓页之后的完整样子：概览卡 → 相关指数横滑条 → 基金卡。
        val coal = Fund(
            code = "008280",
            name = "国泰中证煤炭ETF联接C",
            type = "指数型-股票",
            nav = 2.1532,
            dayGrowth = -3.36,
            buyStatus = "开放申购",
            navDate = "2026-11-05",
            trackIndex = "中证煤炭指数"
        )
        val liquor = Fund(
            code = "161725",
            name = "招商中证白酒指数(LOF)A",
            type = "指数型-股票",
            nav = 1.0412,
            dayGrowth = -1.02,
            buyStatus = "开放申购",
            navDate = "2026-11-05",
            trackIndex = "中证白酒指数"
        )
        val funds = listOf(coal, liquor)
        compose.setContent {
            FundRadarTheme(darkTheme = false) {
                FundRadarContent(
                    state = FundUiState(
                        all = funds,
                        visible = funds,
                        scope = FundScope.PORTFOLIO,
                        loading = false,
                        watchlistEmpty = false,
                        estimates = mapOf(
                            coal.code to Estimate(
                                percent = -3.21,
                                nav = 2.0841,
                                source = EstimateSource.INDEX,
                                basis = "中证煤炭",
                                quoteTime = "11-06 14:02",
                                etfCode = "515220",
                                etfName = "煤炭ETF国泰",
                                etfPercent = -3.14
                            ),
                            liquor.code to Estimate(
                                percent = -1.18,
                                nav = 1.0289,
                                source = EstimateSource.INDEX,
                                basis = "中证白酒",
                                quoteTime = "11-06 14:02"
                            )
                        ),
                        holdings = mapOf(
                            coal.code to Holding(shares = 702.97, cost = 1602.42),
                            liquor.code to Holding(shares = 3000.0, cost = 3120.0)
                        )
                    ),
                    indices = sampleIndexState()
                )
            }
        }
        compose.waitForIdle()
        save("portfolio-indices")
        dump("tree-portfolio-indices.txt")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun indexStrip() {
        compose.setContent {
            FundRadarTheme(darkTheme = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    IndexStrip(
                        state = sampleIndexState(),
                        onOpenKline = { _, _ -> },
                        onAddClick = {},
                        onRemove = {},
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
        compose.waitForIdle()
        save("index-strip")
        dump("tree-index-strip.txt")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun indexStripEmpty() {
        compose.setContent {
            FundRadarTheme(darkTheme = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    IndexStrip(
                        state = IndexUiState(loading = false, watchlistEmpty = true),
                        onOpenKline = { _, _ -> },
                        onAddClick = {},
                        onRemove = {},
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
        compose.waitForIdle()
        save("index-strip-empty")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun addIndexSheet() {
        compose.setContent {
            FundRadarTheme(darkTheme = false) {
                AddIndexSheetContent(
                    state = IndexAddState(
                        open = true,
                        query = "白酒",
                        results = listOf(
                            IndexEntry("399997", 0, "中证白酒"),
                            IndexEntry("399996", 0, "中证白酒指数")
                        ),
                        adding = "399996"
                    ),
                    onQueryChange = {},
                    onAddIndex = {}
                )
            }
        }
        compose.waitForIdle()
        save("add-index-sheet")
        dump("tree-add-index.txt")
    }

    @Test
    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun fundDetailScreen() {
        val fund = Fund(
            code = "025833",
            name = "天弘电网设备特高压指数C",
            type = "指数型-股票",
            nav = 1.0188,
            accNav = 1.0188,
            dayGrowth = 1.85,
            buyStatus = "开放申购",
            sellStatus = "开放赎回",
            minBuy = 10.0,
            maxBuy = null,
            rate = "0.00%",
            sourceRate = "1.20%",
            riskLevel = 4,
            navDate = "2026-11-05",
            trackIndex = "中证电网设备主题指数"
        )
        compose.setContent {
            FundRadarTheme(darkTheme = false) {
                FundDetailScreen(
                    state = DetailState.Ready(
                        fund,
                        FundExtra(
                            company = "天弘基金",
                            manager = "张磊",
                            established = "2025-06-18",
                            scale = "12.4 亿元"
                        )
                    ),
                    onBack = {},
                    estimate = Estimate(
                        percent = 1.85,
                        nav = 1.0377,
                        source = EstimateSource.HOLDINGS,
                        basis = "10 只重仓股",
                        quoteTime = "11-05 15:00",
                        coverage = 61.8
                    ),
                    showLimitBadge = false,
                    removeLabel = "从持仓移除",
                    showFundFacts = false,
                    holding = Holding(
                        shares = 12_000.0,
                        cost = 11_800.0,
                        realized = 360.0,
                        trades = listOf(
                            com.hukua.fundradar.data.Trade(
                                id = 2L,
                                kind = com.hukua.fundradar.data.TradeKind.BUY,
                                shares = 6000.0,
                                nav = 1.0020,
                                at = System.currentTimeMillis()
                            )
                        )
                    )
                )
            }
        }
        compose.waitForIdle()
        save("fund-detail")
        dump("tree-fund-detail.txt")
    }

    @Test
    @Config(qualifiers = "w411dp-h1200dp-xxhdpi")
    fun fundDetailWithLimitFacts() {
        val fund = Fund(
            code = "161725",
            name = "招商中证白酒指数(LOF)A",
            type = "指数型-股票",
            nav = 0.5676,
            accNav = 2.2837,
            dayGrowth = 5.58,
            buyStatus = "限大额",
            sellStatus = "开放赎回",
            minBuy = 10.0,
            maxBuy = 500_000.0,
            rate = "0.10%",
            sourceRate = "1.00%",
            riskLevel = 4,
            navDate = "2026-10-30",
            trackIndex = "中证白酒指数"
        )
        compose.setContent {
            FundRadarTheme(darkTheme = false) {
                FundDetailScreen(
                    state = DetailState.Ready(
                        fund,
                        FundExtra(
                            company = "招商基金",
                            manager = "侯昊",
                            established = "2015-05-27",
                            scale = "512.6 亿元"
                        )
                    ),
                    onBack = {},
                    showLimitBadge = true,
                    removeLabel = "从限购清单移除",
                    showFundFacts = true
                )
            }
        }
        compose.waitForIdle()
        save("fund-detail-limit")
        dump("tree-fund-detail-limit.txt")
    }

    @Test
    @Config(qualifiers = "w411dp-h1200dp-xxhdpi")
    fun fundDetailEtfEstimate() {
        // 指数优先之后，联接基金的详情页也要把目标 ETF 摆出来当对照。
        val fund = Fund(
            code = "008280",
            name = "国泰中证煤炭ETF联接C",
            type = "指数型-股票",
            nav = 2.1532,
            accNav = 2.1532,
            dayGrowth = -3.36,
            buyStatus = "开放申购",
            sellStatus = "开放赎回",
            minBuy = 10.0,
            maxBuy = 500_000.0,
            rate = "0.00%",
            sourceRate = "0.00%",
            riskLevel = 4,
            navDate = "2026-11-05",
            trackIndex = "中证煤炭指数"
        )
        compose.setContent {
            FundRadarTheme(darkTheme = false) {
                FundDetailScreen(
                    state = DetailState.Ready(fund, null),
                    onBack = {},
                    estimate = Estimate(
                        percent = -3.21,
                        nav = 2.0841,
                        source = EstimateSource.INDEX,
                        basis = "中证煤炭",
                        quoteTime = "11-06 14:02",
                        etfCode = "515220",
                        etfName = "煤炭ETF国泰",
                        etfPercent = -3.14
                    ),
                    showLimitBadge = false,
                    removeLabel = "从持仓移除",
                    showFundFacts = false,
                    holding = Holding(
                        shares = 702.97,
                        cost = 1602.42,
                        realized = 0.0
                    )
                )
            }
        }
        compose.waitForIdle()
        save("fund-detail-index")
        dump("tree-fund-detail-index.txt")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun holdingSheet() {        compose.setContent {
            FundRadarTheme(darkTheme = false) {
                androidx.compose.material3.Surface(
                    color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    HoldingSheetContent(
                        state = HoldingSheetState(
                            open = true,
                            fundCode = "025833",
                            fundName = "天弘电网设备特高压指数C",
                            mode = HoldingMode.BUY,
                            amount = "2000",
                            nav = "1.0377",
                            current = Holding(shares = 12000.0, cost = 11800.0)
                        ),
                        onModeChange = {},
                        onSharesChange = {},
                        onCostChange = {},
                        onAmountChange = {},
                        onNavChange = {},
                        onFillAll = {},
                        onSubmit = {}
                    )
                }
            }
        }
        compose.waitForIdle()
        save("holding-sheet")
        dump("tree-holding-sheet.txt")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun holdingSheetEdit() {
        compose.setContent {
            FundRadarTheme(darkTheme = false) {
                androidx.compose.material3.Surface(
                    color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    HoldingSheetContent(
                        state = HoldingSheetState(
                            open = true,
                            fundCode = "008280",
                            fundName = "国泰中证煤炭ETF联接C",
                            mode = HoldingMode.EDIT,
                            shares = "702.97",
                            cost = "2.2795",
                            costInput = CostInput.PRICE,
                            nav = "2.1532",
                            current = Holding(shares = 702.97, cost = 1602.42)
                        ),
                        onModeChange = {},
                        onSharesChange = {},
                        onCostChange = {},
                        onAmountChange = {},
                        onNavChange = {},
                        onFillAll = {},
                        onSubmit = {}
                    )
                }
            }
        }
        compose.waitForIdle()
        save("holding-sheet-edit")
    }

    @Test
    @Config(qualifiers = "w411dp-h1200dp-xxhdpi")
    fun fundDetailWithTrackOverride() {
        val fund = Fund(
            code = "025833",
            name = "天弘电网设备特高压指数C",
            type = "指数型-股票",
            nav = 1.0188,
            accNav = 1.0188,
            dayGrowth = 1.85,
            buyStatus = "开放申购",
            sellStatus = "开放赎回",
            minBuy = 10.0,
            rate = "0.00%",
            sourceRate = "1.20%",
            riskLevel = 4,
            navDate = "2026-11-05",
            trackIndex = "中证电网设备主题指数"
        )
        compose.setContent {
            FundRadarTheme(darkTheme = false) {
                FundDetailScreen(
                    state = DetailState.Ready(fund, null),
                    onBack = {},
                    showLimitBadge = false,
                    removeLabel = "从持仓移除",
                    trackOverride = IndexEntry("931994", 2, "中证电网设备主题"),
                    onEditTrack = {},
                    onClearTrack = {}
                )
            }
        }
        compose.waitForIdle()
        save("fund-detail-track")
        dump("tree-fund-detail-track.txt")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun trackIndexSheet() {
        compose.setContent {
            FundRadarTheme(darkTheme = false) {
                TrackIndexSheetContent(
                    state = TrackPickerState(
                        open = true,
                        fundCode = "025833",
                        fundName = "天弘电网设备特高压指数C",
                        query = "电网",
                        results = listOf(
                            IndexEntry("931994", 2, "中证电网设备主题"),
                            IndexEntry("931008", 2, "中证电网设备")
                        ),
                        current = IndexEntry("931994", 2, "中证电网设备主题")
                    ),
                    onQueryChange = {},
                    onPick = {},
                    onClearCurrent = {}
                )
            }
        }
        compose.waitForIdle()
        save("track-picker")
        dump("tree-track-picker.txt")
    }

    private fun sampleIndexState(): IndexUiState {
        val coal = IndexEntry("399998", 0, "中证煤炭")
        val liquor = IndexEntry("399997", 0, "中证白酒")
        val semi = IndexEntry("980017", 0, "国证半导体芯片")
        val hand = IndexEntry("000300", 1, "沪深300")
        return IndexUiState(
            loading = false,
            items = listOf(
                IndexItem(
                    tracked = TrackedIndex(coal, "中证煤炭指数", listOf("008279", "161724")),
                    quote = IndexQuote(coal, 2292.63, 2.39, "2026-11-05")
                ),
                IndexItem(
                    tracked = TrackedIndex(liquor, "中证白酒指数", listOf("161725", "012414")),
                    quote = IndexQuote(liquor, 8941.20, -1.27, "2026-11-05")
                ),
                IndexItem(
                    tracked = TrackedIndex(
                        semi,
                        "国证半导体芯片指数",
                        listOf("008888"),
                        overridden = true
                    ),
                    quote = IndexQuote(semi, 6120.55, 0.0, "2026-11-05")
                ),
                IndexItem(
                    tracked = TrackedIndex(hand, "手动添加", emptyList(), manual = true),
                    quote = IndexQuote(hand, 4012.88, 0.64, "2026-11-05")
                )
            ),
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun sampleVerdict() = AiVerdict(
        action = SwingAction.BUY,
        confidence = 72,
        reason = "MA5 上穿 MA20，价格站在 MA20 上方 2.1%，近 5 日连续放量反弹。",
        risk = "场外申赎 T+1，注意仓位",
        raw = "",
        at = System.currentTimeMillis()
    )

    private fun sampleState(
        verdict: AiVerdict?,
        aiConfigured: Boolean = true
    ) = KlineUiState(
        code = "161725",
        name = "招商中证白酒指数(LOF)A",
        range = 120,
        loading = false,
        series = sampleSeries(),
        fromCache = true,
        bars = 160,
        added = 1,
        fetchedAt = System.currentTimeMillis(),
        verdict = verdict,
        aiConfigured = aiConfigured
    )

    private fun sampleSeries(): KlineSeries {
        val dates = generateSequence(LocalDate.of(2026, 1, 5)) { it.plusDays(1) }
            .filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }
            .take(160)
            .toList()

        fun price(index: Int): Double =
            1.00 + 0.10 * sin(index / 12.0) + 0.035 * cos(index / 3.4) + index * 0.0004

        val candles = dates.mapIndexed { index, date ->
            val close = price(index)
            val open = if (index == 0) close else price(index - 1)
            Candle(
                date = date.toString(),
                open = open,
                close = close,
                high = maxOf(open, close) + 0.006,
                low = minOf(open, close) - 0.006,
                volume = 900_000.0 + 620_000.0 * abs(sin(index / 7.0))
            )
        }
        return KlineSeries("161725", "招商中证白酒指数(LOF)A", KlineSource.EXCHANGE, candles)
    }

    private fun dump(name: String) {
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, name).writeText(compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 40))
    }

    private fun save(name: String) {
        val root: View = compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(root.width.coerceAtLeast(1080), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(root.height.coerceAtLeast(1920), View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)

        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))

        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
