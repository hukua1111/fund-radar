package com.hukua.fundradar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hukua.fundradar.data.Candle
import com.hukua.fundradar.data.KlineSeries
import com.hukua.fundradar.data.KlineSource
import com.hukua.fundradar.data.SwingAction
import com.hukua.fundradar.data.growthText
import com.hukua.fundradar.data.priceText
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
                            text = state.series?.let {
                                state.code + " · " + if (it.source == KlineSource.EXCHANGE) "场内K线" else "场外净值"
                            } ?: state.code,
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
            Spacer(Modifier.height(12.dp))
            ChartCard(state)
            Spacer(Modifier.height(12.dp))
            AiSection(
                state = state,
                onJudge = onJudge,
                onOpenSettings = onOpenSettings
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "K线与均线由天天基金公开数据计算，AI 判断仅供参考，不构成投资建议。",
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
                            .height(240.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                }

                series == null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
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

                    PriceHeader(series = series, candle = visible.lastOrNull())
                    Spacer(Modifier.height(8.dp))
                    CandleChart(
                        candles = visible,
                        maLines = lines,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    MaLegend(
                        maValues = KlineSeries.MA_PERIODS.associateWith { period ->
                            maValues[period]?.lastOrNull()
                        },
                        latestClose = visible.lastOrNull()?.close
                    )
                }
            }
        }
    }
}

@Composable
private fun PriceHeader(series: KlineSeries, candle: Candle?) {
    val market = LocalMarketColors.current
    val color = when {
        candle == null -> MaterialTheme.colorScheme.onSurface
        candle.close >= candle.open -> market.up
        else -> market.down
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = candle?.let { priceText(it.close) } ?: "—",
            style = MaterialTheme.typography.headlineSmall,
            color = color
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = candle?.let { growthText(it.changePct) } ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = color
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = candle?.date ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MaLegend(maValues: Map<Int, Double?>, latestClose: Double?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
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

    val ma20 = maValues[20]
    if (ma20 != null && latestClose != null && ma20 > 0) {
        val diff = (latestClose - ma20) / ma20 * 100.0
        Spacer(Modifier.height(6.dp))
        Text(
            text = "现价相对 MA20 " + growthText(diff),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CandleChart(
    candles: List<Candle>,
    maLines: List<MaLine>,
    modifier: Modifier = Modifier
) {
    val market = LocalMarketColors.current
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier) {
        if (candles.isEmpty()) return@Canvas

        val rightPad = 54.dp.toPx()
        val bottomPad = 16.dp.toPx()
        val topPad = 6.dp.toPx()
        val leftPad = 2.dp.toPx()

        val plotWidth = size.width - leftPad - rightPad
        val plotHeight = size.height - topPad - bottomPad
        if (plotWidth <= 0f || plotHeight <= 0f) return@Canvas

        var min = candles.minOf { it.low }
        var max = candles.maxOf { it.high }
        maLines.forEach { line ->
            line.values.filterNotNull().forEach { value ->
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

        fun yOf(value: Double): Float = topPad + ((max - value) / span).toFloat() * plotHeight

        val slot = plotWidth / candles.size

        // horizontal grid + price labels
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

        // candles
        candles.forEachIndexed { index, candle ->
            val centerX = leftPad + slot * (index + 0.5f)
            val color = if (candle.close >= candle.open) market.up else market.down
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

        // moving averages
        maLines.forEach { line ->
            var previous: Offset? = null
            line.values.forEachIndexed { index, value ->
                if (value == null) {
                    previous = null
                    return@forEachIndexed
                }
                val point = Offset(leftPad + slot * (index + 0.5f), yOf(value))
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

        // date labels
        val dateStyle = TextStyle(fontSize = 8.sp, color = labelColor)
        val first = candles.first().date.takeLast(5)
        val last = candles.last().date.takeLast(5)
        val firstLayout = textMeasurer.measure(first, dateStyle)
        drawText(
            textLayoutResult = firstLayout,
            topLeft = Offset(leftPad, size.height - firstLayout.size.height)
        )
        val lastLayout = textMeasurer.measure(last, dateStyle)
        drawText(
            textLayoutResult = lastLayout,
            topLeft = Offset(
                x = leftPad + plotWidth - lastLayout.size.width,
                y = size.height - lastLayout.size.height
            )
        )
    }
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
                    text = "还没有配置 AI 接口。填入任意 OpenAI 兼容的地址与 Key 后，就能让它结合 MA20 等均线给出波段建议。",
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
