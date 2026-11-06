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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.hukua.fundradar.data.KlineSeries
import com.hukua.fundradar.data.SwingAction
import com.hukua.fundradar.data.growthText
import com.hukua.fundradar.data.movingAverage
import com.hukua.fundradar.data.priceText
import com.hukua.fundradar.data.volumeText
import com.hukua.fundradar.ui.theme.LocalMarketColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

private val MA_COLORS = listOf(
    Color(0xFFF5A524), // MA5
    Color(0xFF2F80ED), // MA10
    Color(0xFF9B51E0), // MA20
    Color(0xFF8A8F98)  // MA60
)

/** 布林带：上/中/下轨同一色系，填充很淡，避免盖住蜡烛。 */
private val BOLL_COLOR = Color(0xFF4C8DFF)

data class MaLine(val period: Int, val color: Color, val values: List<Double?>)

@Composable
fun KlineScreen(
    viewModel: KlineViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.verdictError) {
        val message = state.verdictError
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.consumeError()
        }
    }

    KlineContent(
        state = state,
        snackbarHostState = snackbar,
        onBack = onBack,
        onRefresh = viewModel::load,
        onSelectRange = viewModel::setRange,
        onJudge = viewModel::judge,
        onOpenSettings = onOpenSettings
    )
}

/** Stateless K line screen, also used by the JVM screenshot tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KlineContent(
    state: KlineUiState,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onBack: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onSelectRange: (Int) -> Unit = {},
    onJudge: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = state.name.ifBlank { state.code },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = state.code + " · 指数日K · BOLL(20,2)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "重新加载")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 28.dp)
        ) {
            RangeRow(range = state.range, onSelect = onSelectRange)
            Spacer(Modifier.height(10.dp))
            ChartCard(state)
            Spacer(Modifier.height(12.dp))
            AiSection(
                state = state,
                onJudge = onJudge,
                onOpenSettings = onOpenSettings
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "K线、均线、布林带与成交量均由天天基金公开数据计算，" +
                    "长按图表可查看单日行情；AI 判断仅供参考，不构成投资建议。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RangeRow(range: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(60, 120, 250).forEach { option ->
            FilterChip(
                selected = range == option,
                onClick = { onSelect(option) },
                label = { Text("${option}日") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    }
}

@Composable
private fun ChartCard(state: KlineUiState) {
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
                    val visible = series.candles.takeLast(state.range)
                    val offset = series.candles.size - visible.size
                    val maValues = KlineSeries.MA_PERIODS.associateWith { period ->
                        series.ma(period).drop(offset)
                    }
                    val lines = KlineSeries.MA_PERIODS.mapIndexed { index, period ->
                        MaLine(period, MA_COLORS[index % MA_COLORS.size], maValues[period].orEmpty())
                    }
                    val rawBoll = series.boll()
                    val boll = BollingerBands(
                        period = rawBoll.period,
                        multiplier = rawBoll.multiplier,
                        middle = rawBoll.middle.drop(offset),
                        upper = rawBoll.upper.drop(offset),
                        lower = rawBoll.lower.drop(offset)
                    )

                    var selected by remember(state.range, series.code, visible.size) {
                        mutableStateOf<Int?>(null)
                    }
                    var showMa by rememberSaveable { mutableStateOf(true) }
                    var showBoll by rememberSaveable { mutableStateOf(true) }
                    var showVolume by rememberSaveable { mutableStateOf(true) }

                    val focus = (selected ?: visible.lastIndex).coerceIn(0, max(0, visible.lastIndex))

                    PriceHeader(candles = visible, index = focus)
                    Spacer(Modifier.height(8.dp))
                    IndicatorRow(
                        showMa = showMa,
                        showBoll = showBoll,
                        showVolume = showVolume,
                        onToggleMa = { showMa = !showMa },
                        onToggleBoll = { showBoll = !showBoll },
                        onToggleVolume = { showVolume = !showVolume }
                    )
                    Spacer(Modifier.height(6.dp))
                    CandleChart(
                        candles = visible,
                        maLines = if (showMa) lines else emptyList(),
                        boll = if (showBoll) boll else null,
                        showVolume = showVolume,
                        selected = selected,
                        onSelect = { selected = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(340.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    IndicatorLegend(
                        maValues = KlineSeries.MA_PERIODS.associateWith { period ->
                            maValues[period]?.getOrNull(focus)
                        },
                        boll = if (showBoll) boll else null,
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
    onToggleMa: () -> Unit,
    onToggleBoll: () -> Unit,
    onToggleVolume: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        IndicatorToggle("均线", showMa, onToggleMa)
        IndicatorToggle("布林带", showBoll, onToggleBoll)
        IndicatorToggle("成交量", showVolume, onToggleVolume)
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
    }
}

@Composable
private fun CandleChart(
    candles: List<Candle>,
    maLines: List<MaLine>,
    boll: BollingerBands?,
    showVolume: Boolean,
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

        val leftPad = leftPadPx
        val plotWidth = size.width - leftPad - axisWidthPx
        if (plotWidth <= 0f) return@Canvas

        val priceTop = topPad
        val priceBottom = size.height - bottomPad - (if (showVolume) volumeHeight + gap else 0f)
        val priceHeight = priceBottom - priceTop
        if (priceHeight <= 0f) return@Canvas
        val volTop = priceBottom + gap
        val volBottom = size.height - bottomPad

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
                end = Offset(x, volBottom),
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
    onOpenSettings: () -> Unit
) {
    if (!state.aiConfigured) {
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
                    text = "还没有配置 AI 接口。填入任意 OpenAI 兼容的地址与 Key 后，" +
                        "就能让它结合均线和布林带判断这个指数现在适不适合做波段。",
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
            Text("AI 分析中…")
        } else {
            Text(if (state.verdict == null) "AI 波段判断" else "重新判断")
        }
    }

    val verdict = state.verdict
    if (verdict != null) {
        Spacer(Modifier.height(12.dp))
        VerdictCard(
            action = verdict.action,
            confidence = verdict.confidence,
            reason = verdict.reason,
            risk = verdict.risk,
            timeText = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(verdict.at))
        )
    }
}

@Composable
private fun VerdictCard(
    action: SwingAction,
    confidence: Int,
    reason: String,
    risk: String,
    timeText: String
) {
    val market = LocalMarketColors.current
    val accent = when (action) {
        SwingAction.BUY -> market.up
        SwingAction.SELL -> market.down
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

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
                    text = "置信度 $confidence%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (reason.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (risk.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "风险提示：$risk",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "AI 生成，仅供参考，不构成投资建议",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
