package com.hukua.fundradar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import com.hukua.fundradar.data.ChangeKind
import com.hukua.fundradar.data.Estimate
import com.hukua.fundradar.data.EstimateSource
import com.hukua.fundradar.data.Fund
import com.hukua.fundradar.data.FundScope
import com.hukua.fundradar.data.LimitChange
import com.hukua.fundradar.ui.AddFundSheetContent
import com.hukua.fundradar.ui.AddFundState
import com.hukua.fundradar.ui.AppBottomBar
import com.hukua.fundradar.ui.FundRadarContent
import com.hukua.fundradar.ui.FundUiState
import com.hukua.fundradar.ui.TAB_FUND
import com.hukua.fundradar.ui.TAB_LIMIT
import com.hukua.fundradar.ui.theme.FundRadarTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the main screen on the JVM (Robolectric native graphics) so the
 * layout can be inspected without an emulator. PNGs land in build/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun mainScreenLight() = render(dark = false, name = "main-light")

    @Test
    fun mainScreenDark() = render(dark = true, name = "main-dark")

    @Test
    fun limitTabLight() = render(dark = false, name = "limit-light", scope = FundScope.LIMIT)

    @Test
    fun loadingState() {
        compose.setContent {
            FundRadarTheme(darkTheme = false) {
                FundScreen(FundUiState(loading = true, watchlistEmpty = false))
            }
        }
        compose.waitForIdle()
        save("loading")
    }

    @Test
    fun emptyWatchlist() {
        compose.setContent {
            FundRadarTheme(darkTheme = false) {
                FundScreen(FundUiState(loading = false, watchlistEmpty = true))
            }
        }
        compose.waitForIdle()
        save("empty")
        File("build/screenshots").mkdirs()
        File("build/screenshots/tree-empty.txt")
            .writeText(compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 30))
    }

    @Test
    fun addSheet() {
        val results = listOf(
            Fund(
                code = "161725", name = "招商中证白酒指数(LOF)A", type = "指数型-股票",
                nav = 0.5676, dayGrowth = 5.58, buyStatus = "限大额"
            ),
            Fund(
                code = "012414", name = "招商中证白酒指数(LOF)C", type = "指数型-股票",
                nav = 0.5647, dayGrowth = 5.61, buyStatus = "限大额"
            )
        )
        compose.setContent {
            FundRadarTheme(darkTheme = false) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        AddFundSheetContent(
                            state = AddFundState(
                                open = true,
                                query = "白酒",
                                results = results,
                                watched = setOf("161725")
                            ),
                            onQueryChange = {},
                            onAddFund = {}
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        save("add-sheet")
        File("build/screenshots").mkdirs()
        File("build/screenshots/tree-add.txt")
            .writeText(compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 30))
    }

    @Test
    fun changesSection() {
        val state = sampleState(FundScope.LIMIT).copy(
            changes = listOf(
                LimitChange(
                    code = "161725",
                    name = "招商中证白酒指数(LOF)A",
                    oldLabel = "限大额 50万",
                    newLabel = "暂停申购",
                    kind = ChangeKind.SUSPENDED
                ),
                LimitChange(
                    code = "110022",
                    name = "易方达消费行业股票",
                    oldLabel = "限大额 10万",
                    newLabel = "限大额 50万",
                    kind = ChangeKind.RELAXED
                )
            )
        )
        compose.setContent {
            FundRadarTheme(darkTheme = false) {
                FundScreen(state)
            }
        }
        compose.waitForIdle()
        save("changes")
        File("build/screenshots").mkdirs()
        File("build/screenshots/tree-changes.txt")
            .writeText(compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 30))
    }

    private fun render(dark: Boolean, name: String, scope: FundScope = FundScope.PORTFOLIO) {
        compose.setContent {
            FundRadarTheme(darkTheme = dark) {
                FundScreen(sampleState(scope))
            }
        }
        compose.waitForIdle()
        save(name)
    }

    @Composable
    private fun FundScreen(state: FundUiState) {
        FundRadarContent(
            state = state,
            bottomBar = { AppBottomBar(selected = TAB_FUND, onSelect = {}) }
        )
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

    @Test
    fun dumpLayoutTree() {
        compose.setContent {
            FundRadarTheme(darkTheme = false) {
                FundScreen(sampleState())
            }
        }
        compose.waitForIdle()
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "tree.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 30))
    }

    private fun sampleState(scope: FundScope = FundScope.PORTFOLIO): FundUiState {
        val funds = listOf(
            Fund(
                code = "161725", name = "招商中证白酒指数(LOF)A", type = "指数型-股票",
                nav = 0.5676, accNav = 2.2837, dayGrowth = 5.58, buyStatus = "限大额",
                sellStatus = "开放赎回", minBuy = 10.0, maxBuy = 500_000.0,
                rate = "0.10%", sourceRate = "1.00%", riskLevel = 4, navDate = "2026-10-30"
            ),
            Fund(
                code = "012414", name = "招商中证白酒指数(LOF)C", type = "指数型-股票",
                nav = 0.5647, accNav = 0.6497, dayGrowth = 5.61, buyStatus = "限大额",
                sellStatus = "开放赎回", minBuy = 10.0, maxBuy = 300_000.0,
                rate = "0.00%", sourceRate = "0.00%", riskLevel = 4, navDate = "2026-10-30"
            ),
            Fund(
                code = "000001", name = "华夏成长混合", type = "混合型-灵活",
                nav = 0.9190, accNav = 3.4920, dayGrowth = -5.06, buyStatus = "暂停申购",
                sellStatus = "开放赎回", minBuy = 10.0, maxBuy = null,
                rate = "0.15%", sourceRate = "1.50%", riskLevel = 4, navDate = "2026-10-30"
            ),
            Fund(
                code = "110022", name = "易方达消费行业股票", type = "股票型",
                nav = 2.8710, accNav = 2.8710, dayGrowth = 2.35, buyStatus = "开放申购",
                sellStatus = "开放赎回", minBuy = 10.0, maxBuy = 100_000_000_000.0,
                rate = "0.15%", sourceRate = "1.50%", riskLevel = 4, navDate = "2026-10-30"
            ),
            Fund(
                code = "009941", name = "格林稳健价值混合C", type = "混合型-偏股",
                nav = 0.4543, accNav = 0.4543, dayGrowth = 0.0, buyStatus = "开放申购",
                sellStatus = "开放赎回", minBuy = 10.0, maxBuy = 100_000_000_000.0,
                rate = "0.00%", sourceRate = "", riskLevel = 3, navDate = "2026-10-30"
            )
        )
        return FundUiState(
            all = funds,
            visible = funds,
            scope = scope,
            loading = false,
            watchlistEmpty = false,
            updatedAt = System.currentTimeMillis(),
            estimates = if (scope.isPortfolio) estimates() else emptyMap(),
            estimatesAt = System.currentTimeMillis()
        )
    }

    private fun estimates(): Map<String, Estimate> = mapOf(
        "161725" to Estimate(
            percent = 5.58,
            nav = 0.5992,
            source = EstimateSource.ETF,
            basis = "酒ETF 512690",
            quoteTime = "11-05 15:00"
        ),
        "012414" to Estimate(
            percent = 5.61,
            nav = 0.5964,
            source = EstimateSource.ETF,
            basis = "酒ETF 512690",
            quoteTime = "11-05 15:00"
        ),
        "110022" to Estimate(
            percent = 2.31,
            nav = 2.9373,
            source = EstimateSource.HOLDINGS,
            basis = "10 只重仓股",
            quoteTime = "11-05 15:00",
            coverage = 62.4
        )
    )
}
