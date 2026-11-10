package com.hukua.fundradar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hukua.fundradar.data.BollingerBands
import com.hukua.fundradar.data.Candle
import com.hukua.fundradar.data.AiPrompt
import com.hukua.fundradar.data.AiVerdict
import com.hukua.fundradar.data.KlineSeries
import com.hukua.fundradar.data.Macd
import com.hukua.fundradar.data.SwingAction
import com.hukua.fundradar.data.Trend
import com.hukua.fundradar.data.growthText
import com.hukua.fundradar.data.movingAverage
import com.hukua.fundradar.data.priceText
import com.hukua.fundradar.data.todayText
import com.hukua.fundradar.data.volumeText
import com.hukua.fundradar.ui.theme.LocalMarketColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val MA_COLORS = listOf(
    Color(0xFFF5A524), // MA5
    Color(0xFF2F80ED), // MA10
    Color(0xFF9B51E0), // MA20
    Color(0xFF8A8F98)  // MA60
)

/** 布林带：上/中/下轨同一色系，填充很淡，避免盖住蜡烛。 */
private val BOLL_COLOR = Color(0xFF4C8DFF)

/** MACD：DIF 快线用金色，DEA 慢线用蓝色，柱子跟随涨跌色。 */
private val DIF_COLOR = Color(0xFFF5A524)
private val DEA_COLOR = Color(0xFF2F80ED)

data class MaLine(val period: Int, val color: Color, val values: List<Double?>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WindowPicker(
    dates: List<String>,
    start: Int,
    end: Int,
    onPreset: (Int?) -> Unit,
    onRange: (Int, Int) -> Unit
) {
    val total = dates.size
    if (total < 2) return
    val count = end - start + 1

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PRESET_WINDOWS.forEach { (bars, label) ->
                val selected = if (bars == null) {
                    count == total
                } else {
                    end == total - 1 && count == min(bars, total)
                }
                FilterChip(
                    selected = selected,
                    onClick = { onPreset(bars) },
                    modifier = Modifier.height(30.dp),
                    label = {
                        Text(text = label, style = MaterialTheme.typography.labelSmall)
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    border = null
                )
            }
        }

        RangeSlider(
            value = start.toFloat()..end.toFloat(),
            onValueChange = { range ->
                var newStart = range.start.roundToInt().coerceIn(0, total - 1)
                var newEnd = range.endInclusive.roundToInt().coerceIn(0, total - 1)
                if (newEnd - newStart < MIN_WINDOW - 1) {
                    if (newStart != start) {
                        newStart = (newEnd - MIN_WINDOW + 1).coerceAtLeast(0)
                    } else {
                        newEnd = (newStart + MIN_WINDOW - 1).coerceAtMost(total - 1)
                    }
                }
                if (newEnd > newStart) onRange(newStart, newEnd)
            },
            valueRange = 0f..(total - 1).toFloat()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = dates[start].takeLast(5) + " → " + dates[end].takeLast(5),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "$count 个交易日",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** 本地缓存状态：一眼看出这次是补了几根还是读了磁盘。 */
@Composable
private fun CacheFooter(state: KlineUiState) {
    val series = state.series ?: return
    val bars = max(state.bars, series.candles.size)
    val time = if (state.fetchedAt > 0) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(state.fetchedAt))
    } else {
        ""
    }
    val text = buildString {
        append(if (state.fromCache) "本地缓存" else "已更新")
        append(" · ")
        append(bars)
        append(" 根日K")
        if (state.sourceLabel.isNotBlank()) {
            append(" · ")
            append(state.sourceLabel)
        }
        if (state.added > 0) {
            append(" · 本次新增 ")
            append(state.added)
            append(" 根")
        }
        if (time.isNotBlank()) {
            append(" · ")
            append(time)
        }
        // 官网是 T-1，当日那根靠东财补；补失败时把日期说清楚，别让人以为没更新。
        val asOf = state.dataAsOf
        if (asOf.isNotBlank() && asOf != todayText()) {
            append(" · 最新一根 ")
            append(asOf)
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val error = state.error
    if (error != null) {
        Spacer(Modifier.height(2.dp))
        Text(
            text = error,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

/** 快捷时间跨度：根数与标题。null 表示全部。 */
private val PRESET_WINDOWS = listOf(
    20 to "1月",
    60 to "3月",
    120 to "6月",
    250 to "1年",
    null to "全部"
)

/** 最少显示这么多根，避免滑块缩成一个点。 */
private const val MIN_WINDOW = 12

@Composable
internal fun ChartCard(
    state: KlineUiState,
    onPreset: (Int) -> Unit,
    /** 详情页内嵌时压一压高度，别把一页全占了。 */
    compact: Boolean = false
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            val series = state.series
            when {
                series == null && state.loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                }

                series == null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = state.error ?: "暂无数据",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> {
                    val total = series.candles.size
                    var startBar by rememberSaveable(series.code) { mutableIntStateOf(0) }
                    var endBar by rememberSaveable(series.code) { mutableIntStateOf(0) }
                    var initialized by rememberSaveable(series.code) { mutableStateOf(false) }
                    var followEnd by rememberSaveable(series.code) { mutableStateOf(true) }

                    // 首次进来按默认视窗（最新 N 根）；之后增量补了新数据，
                    // 只要右滑块还贴在末尾就跟着一起走。
                    LaunchedEffect(total) {
                        if (total <= 0) return@LaunchedEffect
                        if (!initialized) {
                            startBar = (total - min(state.range, total)).coerceAtLeast(0)
                            endBar = total - 1
                            initialized = true
                        } else if (followEnd) {
                            endBar = total - 1
                            if (startBar > endBar) startBar = 0
                        }
                    }

                    val start = startBar.coerceIn(0, max(0, total - 1))
                    val end = endBar.coerceIn(start, max(0, total - 1))
                    val visible = series.candles.subList(start, end + 1)
                    val offset = start

                    // 均线与布林带都在完整历史上算好，再切视窗——否则左边缘
                    // 会是一段空白。
                    val allMa = remember(series) {
                        KlineSeries.MA_PERIODS.associateWith { period -> series.ma(period) }
                    }
                    val allBoll = remember(series) { series.boll() }
                    val allMacd = remember(series) { series.macd() }

                    val lines = KlineSeries.MA_PERIODS.mapIndexed { index, period ->
                        MaLine(
                            period = period,
                            color = MA_COLORS[index % MA_COLORS.size],
                            values = allMa.getValue(period).drop(offset).take(visible.size)
                        )
                    }
                    val boll = BollingerBands(
                        period = allBoll.period,
                        multiplier = allBoll.multiplier,
                        middle = allBoll.middle.drop(offset).take(visible.size),
                        upper = allBoll.upper.drop(offset).take(visible.size),
                        lower = allBoll.lower.drop(offset).take(visible.size)
                    )
                    val macd = Macd(
                        fast = allMacd.fast,
                        slow = allMacd.slow,
                        signal = allMacd.signal,
                        dif = allMacd.dif.drop(offset).take(visible.size),
                        dea = allMacd.dea.drop(offset).take(visible.size),
                        hist = allMacd.hist.drop(offset).take(visible.size)
                    )

                    var selected by remember(series.code, start, end) {
                        mutableStateOf<Int?>(null)
                    }
                    var showMa by rememberSaveable { mutableStateOf(true) }
                    var showBoll by rememberSaveable { mutableStateOf(true) }
                    var showVolume by rememberSaveable { mutableStateOf(true) }
                    var showMacd by rememberSaveable { mutableStateOf(true) }

                    val focus = (selected ?: visible.lastIndex).coerceIn(0, max(0, visible.lastIndex))

                    PriceHeader(candles = visible, index = focus)
                    Spacer(Modifier.height(8.dp))
                    IndicatorRow(
                        showMa = showMa,
                        showBoll = showBoll,
                        showVolume = showVolume,
                        showMacd = showMacd,
                        onToggleMa = { showMa = !showMa },
                        onToggleBoll = { showBoll = !showBoll },
                        onToggleVolume = { showVolume = !showVolume },
                        onToggleMacd = { showMacd = !showMacd }
                    )
                    Spacer(Modifier.height(6.dp))
                    CandleChart(
                        candles = visible,
                        maLines = if (showMa) lines else emptyList(),
                        boll = if (showBoll) boll else null,
                        showVolume = showVolume,
                        macd = if (showMacd) macd else null,
                        selected = selected,
                        onSelect = { selected = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(
                                when {
                                    compact && showMacd -> 300.dp
                                    compact -> 250.dp
                                    showMacd -> 400.dp
                                    else -> 340.dp
                                }
                            )
                    )
                    Spacer(Modifier.height(2.dp))
                    WindowPicker(
                        dates = series.candles.map { it.date },
                        start = start,
                        end = end,
                        onPreset = { bars ->
                            val length = if (bars == null) total else min(bars, total)
                            startBar = (total - length).coerceAtLeast(0)
                            endBar = total - 1
                            followEnd = true
                            onPreset(bars ?: total)
                        },
                        onRange = { newStart, newEnd ->
                            startBar = newStart
                            endBar = newEnd
                            followEnd = newEnd >= total - 1
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    IndicatorLegend(
                        maValues = KlineSeries.MA_PERIODS.associateWith { period ->
                            allMa.getValue(period).getOrNull(offset + focus)
                        },
                        boll = if (showBoll) boll else null,
                        macd = if (showMacd) macd else null,
                        focus = focus,
                        latestClose = visible.getOrNull(focus)?.close,
                        showMa = showMa
                    )
                }
            }
        }
    }
}

/**
 * Price + 单日 OHLC。长按图表时整块跟着手指走，松手回到最后一根。
 */
@Composable
private fun PriceHeader(candles: List<Candle>, index: Int) {
    val market = LocalMarketColors.current
    val candle = candles.getOrNull(index) ?: return
    val previous = candles.getOrNull(index - 1)?.close ?: candle.open
    val change = if (previous > 0) (candle.close - previous) / previous * 100.0 else 0.0
    val color = when {
        change > 0 -> market.up
        change < 0 -> market.down
        else -> market.flat
    }

    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = priceText(candle.close),
                style = MaterialTheme.typography.headlineSmall,
                color = color
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = growthText(change),
                style = MaterialTheme.typography.bodyMedium,
                color = color
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = candle.date,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))
        Row {
            QuoteCell("开", priceText(candle.open), Modifier.weight(1f))
            QuoteCell("高", priceText(candle.high), Modifier.weight(1f))
            QuoteCell("低", priceText(candle.low), Modifier.weight(1f))
            QuoteCell("量", volumeText(candle.volume), Modifier.weight(1f))
        }
    }
}

@Composable
private fun QuoteCell(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

@Composable
private fun IndicatorRow(
    showMa: Boolean,
    showBoll: Boolean,
    showVolume: Boolean,
    showMacd: Boolean,
    onToggleMa: () -> Unit,
    onToggleBoll: () -> Unit,
    onToggleVolume: () -> Unit,
    onToggleMacd: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        IndicatorToggle("均线", showMa, onToggleMa)
        IndicatorToggle("布林带", showBoll, onToggleBoll)
        IndicatorToggle("成交量", showVolume, onToggleVolume)
        IndicatorToggle("MACD", showMacd, onToggleMacd)
    }
}

@Composable
private fun IndicatorToggle(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.height(30.dp),
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
        ),
        border = null
    )
}

@Composable
private fun IndicatorLegend(
    maValues: Map<Int, Double?>,
    boll: BollingerBands?,
    macd: Macd?,
    focus: Int,
    latestClose: Double?,
    showMa: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (showMa) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                KlineSeries.MA_PERIODS.forEachIndexed { index, period ->
                    val value = maValues[period]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MA_COLORS[index % MA_COLORS.size])
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "MA$period " + (value?.let { priceText(it) } ?: "—"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (boll != null) {
            Spacer(Modifier.height(4.dp))
            val upper = boll.upper.getOrNull(focus)
            val middle = boll.middle.getOrNull(focus)
            val lower = boll.lower.getOrNull(focus)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "BOLL 上 " + (upper?.let { priceText(it) } ?: "—"),
                    style = MaterialTheme.typography.labelSmall,
                    color = BOLL_COLOR
                )
                Text(
                    text = "中 " + (middle?.let { priceText(it) } ?: "—"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "下 " + (lower?.let { priceText(it) } ?: "—"),
                    style = MaterialTheme.typography.labelSmall,
                    color = BOLL_COLOR
                )
            }

            if (upper != null && lower != null && latestClose != null && upper > lower) {
                val position = ((latestClose - lower) / (upper - lower) * 100).coerceIn(0.0, 100.0)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "价格位于布林带 " + String.format(Locale.US, "%.0f%%", position) + " 位置",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (macd != null) {
            Spacer(Modifier.height(4.dp))
            val dif = macd.dif.getOrNull(focus)
            val dea = macd.dea.getOrNull(focus)
            val hist = macd.hist.getOrNull(focus)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "MACD(${macd.fast},${macd.slow},${macd.signal})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "DIF " + (dif?.let { macdText(it) } ?: "—"),
                    style = MaterialTheme.typography.labelSmall,
                    color = DIF_COLOR
                )
                Text(
                    text = "DEA " + (dea?.let { macdText(it) } ?: "—"),
                    style = MaterialTheme.typography.labelSmall,
                    color = DEA_COLOR
                )
                Text(
                    text = "柱 " + (hist?.let { macdText(it) } ?: "—"),
                    style = MaterialTheme.typography.labelSmall,
                    color = if ((hist ?: 0.0) >= 0) {
                        LocalMarketColors.current.up
                    } else {
                        LocalMarketColors.current.down
                    }
                )
            }
            if (dif != null && dea != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = macdStateText(dif, dea, hist),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** MACD 的数值量级很小，统一保留四位小数并带符号。 */
private fun macdText(value: Double): String = String.format(Locale.US, "%+.4f", value)

/**
 * 一句人话解释当前 MACD 状态：零轴上下、金叉死叉、柱子放大还是缩短。
 * 只是把数字翻译成中文，不做任何预测。
 */
private fun macdStateText(dif: Double, dea: Double, hist: Double?): String {
    val zone = if (dif >= 0 && dea >= 0) "零轴上方" else if (dif < 0 && dea < 0) "零轴下方" else "零轴附近"
    val cross = when {
        dif > dea && hist != null && hist > 0 -> "DIF 在 DEA 之上（金叉后）"
        dif < dea && hist != null && hist < 0 -> "DIF 在 DEA 之下（死叉后）"
        else -> "DIF 与 DEA 粘合"
    }
    return "$zone · $cross"
}

@Composable
private fun CandleChart(
    candles: List<Candle>,
    maLines: List<MaLine>,
    boll: BollingerBands?,
    showVolume: Boolean,
    macd: Macd?,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    val market = LocalMarketColors.current
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceColor = MaterialTheme.colorScheme.surface
    val cardColor = MaterialTheme.colorScheme.surfaceContainerLow
    val textMeasurer = rememberTextMeasurer()

    val density = LocalDensity.current
    val axisWidthPx = with(density) { 56.dp.toPx() }
    val leftPadPx = with(density) { 2.dp.toPx() }

    Canvas(
        modifier = modifier.pointerInput(candles.size, axisWidthPx, leftPadPx) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    onSelect(indexAt(offset.x, size.width.toFloat(), candles.size, leftPadPx, axisWidthPx))
                },
                onDrag = { change, _ ->
                    change.consume()
                    onSelect(indexAt(change.position.x, size.width.toFloat(), candles.size, leftPadPx, axisWidthPx))
                },
                onDragEnd = { onSelect(null) },
                onDragCancel = { onSelect(null) }
            )
        }
    ) {
        if (candles.isEmpty()) return@Canvas

        val bottomPad = 16.dp.toPx()
        val topPad = 6.dp.toPx()
        val gap = 12.dp.toPx()
        val volumeHeight = if (showVolume) 52.dp.toPx() else 0f
        val macdHeight = if (macd != null) 58.dp.toPx() else 0f

        val leftPad = leftPadPx
        val plotWidth = size.width - leftPad - axisWidthPx
        if (plotWidth <= 0f) return@Canvas

        val subHeight = (if (showVolume) volumeHeight + gap else 0f) +
            (if (macd != null) macdHeight + gap else 0f)
        val priceTop = topPad
        val priceBottom = size.height - bottomPad - subHeight
        val priceHeight = priceBottom - priceTop
        if (priceHeight <= 0f) return@Canvas
        val volTop = priceBottom + gap
        val volBottom = volTop + volumeHeight
        val macdTop = if (showVolume) volBottom + gap else priceBottom + gap
        val macdBottom = macdTop + macdHeight

        var min = candles.minOf { it.low }
        var max = candles.maxOf { it.high }
        maLines.forEach { line ->
            line.values.filterNotNull().forEach { value ->
                if (value < min) min = value
                if (value > max) max = value
            }
        }
        boll?.let { band ->
            (band.upper + band.lower).filterNotNull().forEach { value ->
                if (value < min) min = value
                if (value > max) max = value
            }
        }
        if (max - min < 1e-6) {
            max += 0.01
            min -= 0.01
        }
        val padding = (max - min) * 0.06
        min -= padding
        max += padding
        val span = (max - min).toFloat()

        fun yOf(value: Double): Float = priceTop + ((max - value) / span).toFloat() * priceHeight

        val slot = plotWidth / candles.size
        fun centerX(index: Int): Float = leftPad + slot * (index + 0.5f)

        // 网格 + 右侧价格标签
        val steps = 4
        for (i in 0..steps) {
            val value = max - (max - min) * i / steps
            val y = yOf(value)
            drawLine(
                color = gridColor,
                start = Offset(leftPad, y),
                end = Offset(leftPad + plotWidth, y),
                strokeWidth = 1f
            )
            val layout = textMeasurer.measure(
                text = priceText(value),
                style = TextStyle(fontSize = 8.sp, color = labelColor)
            )
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    x = leftPad + plotWidth + 4.dp.toPx(),
                    y = y - layout.size.height / 2f
                )
            )
        }

        // 布林带填充：上轨走一遍，下轨倒着回来，闭合成一条带
        if (boll != null) {
            val fill = Path()
            var opened = false
            candles.indices.forEach { index ->
                val upper = boll.upper.getOrNull(index) ?: return@forEach
                val x = centerX(index)
                if (!opened) {
                    fill.moveTo(x, yOf(upper))
                    opened = true
                } else {
                    fill.lineTo(x, yOf(upper))
                }
            }
            if (opened) {
                for (index in candles.indices.reversed()) {
                    val lower = boll.lower.getOrNull(index) ?: continue
                    fill.lineTo(centerX(index), yOf(lower))
                }
                fill.close()
                drawPath(path = fill, color = BOLL_COLOR.copy(alpha = 0.13f))
            }
        }

        // 蜡烛
        candles.forEachIndexed { index, candle ->
            val centerX = centerX(index)
            val color = if (candle.isUp) market.up else market.down
            val wickWidth = max(1f, slot * 0.12f)
            drawLine(
                color = color,
                start = Offset(centerX, yOf(candle.high)),
                end = Offset(centerX, yOf(candle.low)),
                strokeWidth = wickWidth
            )
            val bodyWidth = max(1.5f, slot * 0.62f)
            val top = minOf(yOf(candle.open), yOf(candle.close))
            val bodyHeight = max(1f, abs(yOf(candle.close) - yOf(candle.open)))
            drawRect(
                color = color,
                topLeft = Offset(centerX - bodyWidth / 2f, top),
                size = Size(bodyWidth, bodyHeight)
            )
        }

        // 均线
        maLines.forEach { line ->
            var previous: Offset? = null
            line.values.forEachIndexed { index, value ->
                if (value == null) {
                    previous = null
                    return@forEachIndexed
                }
                val point = Offset(centerX(index), yOf(value))
                previous?.let { from ->
                    drawLine(
                        color = line.color,
                        start = from,
                        end = point,
                        strokeWidth = 1.4.dp.toPx()
                    )
                }
                previous = point
            }
        }

        // 布林带三条轨：上下轨虚线，中轨淡实线
        if (boll != null) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))
            listOf(
                boll.upper to 1.1.dp.toPx(),
                boll.lower to 1.1.dp.toPx(),
                boll.middle to 1.dp.toPx()
            ).forEach { (values, stroke) ->
                var previous: Offset? = null
                values.forEachIndexed { index, value ->
                    if (value == null) {
                        previous = null
                        return@forEachIndexed
                    }
                    val point = Offset(centerX(index), yOf(value))
                    previous?.let { from ->
                        drawLine(
                            color = BOLL_COLOR.copy(alpha = 0.85f),
                            start = from,
                            end = point,
                            strokeWidth = stroke,
                            pathEffect = dash
                        )
                    }
                    previous = point
                }
            }
        }

        // 成交量副图
        if (showVolume) {
            drawLine(
                color = gridColor,
                start = Offset(leftPad, volBottom),
                end = Offset(leftPad + plotWidth, volBottom),
                strokeWidth = 1f
            )

            val maxVolume = candles.maxOfOrNull { it.volume } ?: 0.0
            val volumeSpan = volBottom - volTop
            if (maxVolume > 0) {
                val bodyWidth = max(1.5f, slot * 0.62f)
                candles.forEachIndexed { index, candle ->
                    val height = (candle.volume / maxVolume * volumeSpan).toFloat()
                    if (height <= 0f) return@forEachIndexed
                    val color = if (candle.isUp) market.up else market.down
                    drawRect(
                        color = color.copy(alpha = 0.55f),
                        topLeft = Offset(centerX(index) - bodyWidth / 2f, volBottom - height),
                        size = Size(bodyWidth, height)
                    )
                }

                // 量的 5 日均线
                val volMa = movingAverage(candles.map { it.volume }, 5)
                var previous: Offset? = null
                volMa.forEachIndexed { index, value ->
                    if (value == null) {
                        previous = null
                        return@forEachIndexed
                    }
                    val y = volBottom - (value / maxVolume * volumeSpan).toFloat()
                    val point = Offset(centerX(index), y)
                    previous?.let { from ->
                        drawLine(
                            color = MA_COLORS[0].copy(alpha = 0.9f),
                            start = from,
                            end = point,
                            strokeWidth = 1.2.dp.toPx()
                        )
                    }
                    previous = point
                }

                val layout = textMeasurer.measure(
                    text = volumeText(maxVolume),
                    style = TextStyle(fontSize = 8.sp, color = labelColor)
                )
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(leftPad + plotWidth + 4.dp.toPx(), volTop)
                )
            } else {
                val layout = textMeasurer.measure(
                    text = "无成交量数据",
                    style = TextStyle(fontSize = 8.sp, color = labelColor)
                )
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(leftPad + 2.dp.toPx(), volTop + 2.dp.toPx())
                )
            }
        }

        // MACD 副图：零轴 + 柱状线 + DIF/DEA
        if (macd != null) {
            val values = macd.dif + macd.dea + macd.hist
            val usable = values.filterNotNull()
            if (usable.isNotEmpty()) {
                val span = max(abs(usable.min()), abs(usable.max()))
                val scale = if (span > 1e-9) span else 1.0
                fun yOfMacd(value: Double): Float =
                    macdTop + macdHeight / 2f - (value / scale * (macdHeight / 2f) * 0.92f).toFloat()

                val zeroY = macdTop + macdHeight / 2f
                drawLine(
                    color = gridColor,
                    start = Offset(leftPad, zeroY),
                    end = Offset(leftPad + plotWidth, zeroY),
                    strokeWidth = 1f
                )

                val bodyWidth = max(1.2f, slot * 0.5f)
                macd.hist.forEachIndexed { index, value ->
                    if (value == null) return@forEachIndexed
                    val y = yOfMacd(value)
                    val color = if (value >= 0) market.up else market.down
                    drawRect(
                        color = color.copy(alpha = 0.72f),
                        topLeft = Offset(centerX(index) - bodyWidth / 2f, min(y, zeroY)),
                        size = Size(bodyWidth, max(1f, abs(y - zeroY)))
                    )
                }

                listOf(macd.dif to DIF_COLOR, macd.dea to DEA_COLOR).forEach { (series, color) ->
                    var previous: Offset? = null
                    series.forEachIndexed { index, value ->
                        if (value == null) {
                            previous = null
                            return@forEachIndexed
                        }
                        val point = Offset(centerX(index), yOfMacd(value))
                        previous?.let { from ->
                            drawLine(
                                color = color,
                                start = from,
                                end = point,
                                strokeWidth = 1.3.dp.toPx()
                            )
                        }
                        previous = point
                    }
                }

                val layout = textMeasurer.measure(
                    text = "MACD",
                    style = TextStyle(fontSize = 8.sp, color = labelColor)
                )
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(leftPad + plotWidth + 4.dp.toPx(), macdTop)
                )
            }
        }

        // 日期轴：首、末，长按时中间再补一个选中日期
        val dateStyle = TextStyle(fontSize = 8.sp, color = labelColor)
        val first = candles.first().date.takeLast(5)
        val last = candles.last().date.takeLast(5)
        val firstLayout = textMeasurer.measure(first, dateStyle)
        val lastLayout = textMeasurer.measure(last, dateStyle)
        drawText(
            textLayoutResult = firstLayout,
            topLeft = Offset(leftPad, size.height - firstLayout.size.height)
        )
        drawText(
            textLayoutResult = lastLayout,
            topLeft = Offset(
                x = leftPad + plotWidth - lastLayout.size.width,
                y = size.height - lastLayout.size.height
            )
        )

        // 十字光标
        val focus = selected?.takeIf { it in candles.indices }
        if (focus != null) {
            val candle = candles[focus]
            val x = centerX(focus)
            val y = yOf(candle.close)
            val crossColor = labelColor.copy(alpha = 0.55f)
            drawLine(
                color = crossColor,
                start = Offset(x, priceTop),
                end = Offset(x, if (macd != null) macdBottom else volBottom),
                strokeWidth = 1f
            )
            drawLine(
                color = crossColor,
                start = Offset(leftPad, y),
                end = Offset(leftPad + plotWidth, y),
                strokeWidth = 1f
            )

            // 右侧价格牌
            val tagStyle = TextStyle(fontSize = 8.sp, color = surfaceColor)
            val tagLayout = textMeasurer.measure(priceText(candle.close), tagStyle)
            val tagHeight = tagLayout.size.height + 4.dp.toPx()
            drawRect(
                color = labelColor,
                topLeft = Offset(leftPad + plotWidth + 2.dp.toPx(), y - tagHeight / 2f),
                size = Size(axisWidthPx - 2.dp.toPx(), tagHeight)
            )
            drawText(
                textLayoutResult = tagLayout,
                topLeft = Offset(
                    x = leftPad + plotWidth + 6.dp.toPx(),
                    y = y - tagLayout.size.height / 2f
                )
            )

            // 底部选中日期，盖在首末标签之上
            val focusLayout = textMeasurer.measure(candle.date.takeLast(5), dateStyle)
            val padding = 3.dp.toPx()
            val tagX = (x - focusLayout.size.width / 2f)
                .coerceIn(leftPad, leftPad + plotWidth - focusLayout.size.width)
            drawRect(
                color = cardColor,
                topLeft = Offset(tagX - padding, size.height - focusLayout.size.height),
                size = Size(focusLayout.size.width + padding * 2, focusLayout.size.height.toFloat())
            )
            drawText(
                textLayoutResult = focusLayout,
                topLeft = Offset(tagX, size.height - focusLayout.size.height)
            )
        }
    }
}

/** 把手指的 x 坐标换算成第几根 K 线。 */
private fun indexAt(
    x: Float,
    width: Float,
    count: Int,
    leftPad: Float,
    axisWidth: Float
): Int {
    if (count <= 0) return 0
    val plotWidth = (width - leftPad - axisWidth).coerceAtLeast(1f)
    val slot = plotWidth / count
    return ((x - leftPad) / slot).toInt().coerceIn(0, count - 1)
}

@Composable
private fun AiSection(
    state: KlineUiState,
    onJudge: () -> Unit,
    onOpenSettings: () -> Unit,
    dense: Boolean = false
) {
    if (!state.aiConfigured) {
        if (dense) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "AI 波段判断需要先在设置里填接口",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onOpenSettings,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp,
                        vertical = 0.dp
                    )
                ) {
                    Text("去设置", style = MaterialTheme.typography.labelMedium)
                }
            }
            return
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "AI 波段判断",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (dense) {
                        "填好 AI 地址与 Key 后，可以让它结合均线与布林带判断这个指数适不适合做波段。"
                    } else {
                        "还没有配置 AI 接口。填入任意 OpenAI 兼容的地址与 Key 后，" +
                            "就能让它结合均线和布林带判断这个指数现在适不适合做波段。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onOpenSettings, shape = RoundedCornerShape(14.dp)) {
                    Text("去设置")
                }
            }
        }
        return
    }

    Button(
        onClick = onJudge,
        enabled = !state.judging && state.series != null,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        if (state.judging) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.width(10.dp))
            Text(if (state.preparing) "正在汇总大盘与持仓…" else "AI 分析中…")
        } else {
            Text(if (state.verdict == null) "AI 波段判断" else "重新判断")
        }
    }

    if (state.contextSummary.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = "本轮带上：" + state.contextSummary,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    val verdict = state.verdict
    if (verdict != null) {
        Spacer(Modifier.height(12.dp))
        VerdictCard(
            verdict = verdict,
            timeText = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(verdict.at)),
            prompt = state.prompt
        )
    }
}

@Composable
private fun VerdictCard(
    verdict: AiVerdict,
    timeText: String,
    prompt: String
) {
    val market = LocalMarketColors.current
    val action = verdict.action
    val accent = when (action) {
        SwingAction.BUY -> market.up
        SwingAction.SELL -> market.down
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    var showPrompt by remember { mutableStateOf(false) }
    // 结论有保质期：买入给 10 个交易日，卖出和观望给 5 个。过期后本地不再
    // 要求「涨 15 分」才准改口，界面上也要说清楚，否则用户会一直照着旧结论做。
    val validDays = if (action == SwingAction.BUY) AiPrompt.VALID_DAYS_BUY else AiPrompt.VALID_DAYS_OTHER

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = accent.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = action.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = accent,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "置信度 ${verdict.confidence}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (verdict.trend != Trend.UNKNOWN) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "趋势 ${verdict.trend.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = "有效期 $validDays 个交易日，到期后按新数据重评（不再沿用旧结论）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 本地把它拦下来的时候要说清楚，否则用户会觉得「怎么没变化」。
            if (verdict.kept) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Text(
                        text = buildString {
                            append("维持上次结论")
                            val intended = verdict.intended
                            if (intended != SwingAction.UNKNOWN && intended != action) {
                                append("：模型这次倾向「").append(intended.label).append("」，但证据没到切换门槛")
                            } else {
                                append("：数据没有实质变化")
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
            }

            if (verdict.reason.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = verdict.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (verdict.risk.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "风险提示：${verdict.risk}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "AI 生成，仅供参考，不构成投资建议",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (prompt.isNotBlank()) {
                    TextButton(
                        onClick = { showPrompt = !showPrompt },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp,
                            vertical = 0.dp
                        )
                    ) {
                        Text(
                            if (showPrompt) "收起" else "查看本轮数据",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            // 直接展开在卡片里：用户能一眼看到模型拿到了什么、被立了什么规矩，
            // 不用跳弹窗，也方便截图留证。
            if (showPrompt && prompt.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp)
                    )
                }
            }
        }
    }
}

/**
 * 嵌在基金详情里的 K 线块：图表 + 区间滑块 + 数据时间 + AI 判断。
 *
 * 以前是「跟踪标的」卡片上的一个按钮，点开再进一整页；现在直接铺在详情页里，
 * 想看均线和布林带不用再跳一次。高度压到 300dp（带 MACD），外层页面照常滚动。
 */
@Composable
fun KlineCard(
    state: KlineUiState,
    modifier: Modifier = Modifier,
    onSelectRange: (Int) -> Unit = {},
    onRefresh: () -> Unit = {},
    onJudge: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "跟踪指数 K 线",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = buildString {
                        append(state.name.ifBlank { state.code })
                        if (state.name.isNotBlank() && state.code.isNotBlank()) append(" ")
                        if (state.name.isNotBlank()) append(state.code)
                        append(" · 日K · BOLL(20,2) · MACD")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "重新加载 K 线")
            }
        }
        Spacer(Modifier.height(6.dp))
        ChartCard(state = state, onPreset = onSelectRange, compact = true)
        CacheFooter(state)
        Spacer(Modifier.height(10.dp))
        AiSection(
            state = state,
            onJudge = onJudge,
            onOpenSettings = onOpenSettings,
            dense = true
        )
    }
}
