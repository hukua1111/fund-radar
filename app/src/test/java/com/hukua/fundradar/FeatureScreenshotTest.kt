package com.hukua.fundradar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import com.hukua.fundradar.data.AiConfig
import com.hukua.fundradar.data.AiVerdict
import com.hukua.fundradar.data.Candle
import com.hukua.fundradar.data.IndexEntry
import com.hukua.fundradar.data.IndexQuote
import com.hukua.fundradar.data.KlineSeries
import com.hukua.fundradar.data.KlineSource
import com.hukua.fundradar.data.SwingAction
import com.hukua.fundradar.data.TrackedIndex
import com.hukua.fundradar.ui.AppBottomBar
import com.hukua.fundradar.ui.IndexContent
import com.hukua.fundradar.ui.IndexItem
import com.hukua.fundradar.ui.IndexUiState
import com.hukua.fundradar.ui.KlineContent
import com.hukua.fundradar.ui.KlineUiState
import com.hukua.fundradar.ui.SettingsContent
import com.hukua.fundradar.ui.TAB_INDEX
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
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun indexTab() {
        compose.setContent {
            FundRadarTheme(darkTheme = false) {
                IndexContent(
                    state = sampleIndexState(),
                    bottomBar = { AppBottomBar(selected = TAB_INDEX, onSelect = {}) }
                )
            }
        }
        compose.waitForIdle()
        save("index-tab")
        dump("tree-index.txt")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun indexTabEmpty() {
        compose.setContent {
            FundRadarTheme(darkTheme = false) {
                IndexContent(
                    state = IndexUiState(loading = false, watchlistEmpty = true),
                    bottomBar = { AppBottomBar(selected = TAB_INDEX, onSelect = {}) }
                )
            }
        }
        compose.waitForIdle()
        save("index-empty")
    }

    private fun sampleIndexState(): IndexUiState {
        val coal = IndexEntry("399998", 0, "中证煤炭")
        val liquor = IndexEntry("399997", 0, "中证白酒")
        val semi = IndexEntry("980017", 0, "国证半导体芯片")
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
                    tracked = TrackedIndex(semi, "国证半导体芯片指数", listOf("008888")),
                    quote = IndexQuote(semi, 6120.55, 0.0, "2026-11-05")
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
                volume = 1_000_000.0
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
