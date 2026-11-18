package com.hukua.fundradar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hukua.fundradar.data.DcaAction
import com.hukua.fundradar.data.DcaVerdict
import com.hukua.fundradar.data.IndexEntry
import com.hukua.fundradar.data.amountText
import com.hukua.fundradar.data.growthText
import com.hukua.fundradar.data.money
import com.hukua.fundradar.ui.theme.LocalMarketColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 空页面推荐的行业主题指数。
 *
 * 只放几个口径清楚的行业指数，剩下的交给搜索 —— 各家「新能源」「半导体」有好几个
 * 版本，与其猜，不如让用户自己搜到确切那一只。
 */
private val DCA_SUGGESTIONS = listOf(
    IndexEntry("399997", 0, "中证白酒"),
    IndexEntry("399989", 0, "中证医疗"),
    IndexEntry("399967", 0, "中证军工"),
    IndexEntry("399975", 0, "证券公司"),
    IndexEntry("931151", 2, "光伏产业"),
    IndexEntry("930697", 2, "家用电器"),
    IndexEntry("H30533", 2, "中国互联网50"),
    IndexEntry("000688", 1, "科创50"),
    IndexEntry("000300", 1, "沪深300")
)

/**
 * 「定投」页：观察的是**行业主题指数**，不是某只基金。
 *
 * 卡片上三块信息：这个行业值不值得按计划买（评分）、现在该干什么（动作）、
 * 按我的计划已经投出了什么（期数 / 投入 / 市值 / 收益）。
 */
@Composable
fun DcaScreen(
    viewModel: DcaViewModel,
    onOpenSettings: () -> Unit = {},
    onOpenDetail: (DcaRow) -> Unit = {},
    bottomBar: @Composable () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val error = state.error
    LaunchedEffect(error) {
        if (error != null) {
            snackbarHostState.showSnackbar("刷新失败：$error")
            viewModel.consumeError()
        }
    }

    DcaContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onRefresh = { viewModel.refresh(force = true) },
        onAddClick = viewModel::openPicker,
        onPickSuggestion = viewModel::addIndex,
        onSelectFilter = viewModel::setFilter,
        onOpenDetail = onOpenDetail,
        onOpenSettings = onOpenSettings,
        bottomBar = bottomBar
    )

    if (state.picker.open) {
        DcaIndexSheet(
            state = state.picker,
            existing = state.rows.map { it.secid }.toSet(),
            onQueryChange = viewModel::onPickerQueryChange,
            onPick = viewModel::addIndex,
            onDismiss = viewModel::closePicker
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DcaContent(
    state: DcaUiState,
    snackbarHostState: SnackbarHostState,
    onRefresh: () -> Unit,
    onAddClick: () -> Unit,
    onPickSuggestion: (IndexEntry) -> Unit,
    onSelectFilter: (DcaFilter) -> Unit,
    onOpenDetail: (DcaRow) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {}
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = bottomBar,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "定投",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "行业主题指数 · 值不值得投，现在要不要停",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                    IconButton(onClick = onAddClick) {
                        Icon(Icons.Filled.Add, contentDescription = "添加行业指数")
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        if (state.empty && !state.loading) {
            EmptyDca(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                onAddClick = onAddClick,
                onPickSuggestion = onPickSuggestion
            )
            return@Scaffold
        }

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "summary") { DcaSummaryCard(state) }

                if (state.rows.isNotEmpty()) {
                    item(key = "filter") {
                        DcaFilterBar(
                            selected = state.filter,
                            total = state.rows.size,
                            good = state.goodCount,
                            slow = state.pauseCount,
                            profit = state.profitCount,
                            onSelect = onSelectFilter
                        )
                    }
                }

                if (state.loading) {
                    items(3) { DcaSkeletonCard() }
                } else {
                    items(items = state.visible, key = { it.secid }) { row ->
                        DcaCard(row = row, onClick = { onOpenDetail(row) })
                    }
                }

                if (!state.loading && state.visible.isEmpty()) {
                    item(key = "no-match") {
                        Text(
                            text = "这个筛选下没有指数，换个筛选看看",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 28.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                if (state.updatedAt > 0) {
                    item(key = "updated") {
                        Text(
                            text = "更新于 ${timeText(state.updatedAt)} · 日线口径，盘中不重算",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyDca(
    modifier: Modifier,
    onAddClick: () -> Unit,
    onPickSuggestion: (IndexEntry) -> Unit
) {
    Column(
        modifier = modifier
            .padding(horizontal = 28.dp)
            .padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = "先挑几个行业指数",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "定投看的是指数本身，不是某只基金 —— 同一个行业指数下面有 ETF、场外指基、" +
                "增强基金好几种买法。先把行业加进来，再判断值不值得按月买。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAddClick, shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("添加行业指数")
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "常用行业指数",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DCA_SUGGESTIONS.forEach { entry ->
                SuggestionChip(onClick = { onPickSuggestion(entry) }, label = { Text(entry.name) })
            }
        }
    }
}

@Composable
private fun DcaSummaryCard(state: DcaUiState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SummaryCell("在观察", "${state.rows.size} 个", Modifier.weight(1f))
                SummaryCell("适合定投", "${state.goodCount} 个", Modifier.weight(1f))
                SummaryCell("建议减/停", "${state.pauseCount} 个", Modifier.weight(1f))
                SummaryCell("建议止盈", "${state.profitCount} 个", Modifier.weight(1f))
            }

            if (state.investedTotal > 0) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "按计划累计投入 ${money(state.investedTotal)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "市值 ${money(state.valueTotal)}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = amountText(state.profitTotal),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = growthColor(state.profitTotal)
                        )
                        Text(
                            text = state.profitPct?.let { growthText(it) } ?: "—",
                            style = MaterialTheme.typography.labelSmall,
                            color = growthColor(state.profitTotal)
                        )
                    }
                }
            } else if (state.rows.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "还没有指数设了定投计划 · 点进任意一个，设好频率和金额就能看到回放出来的持仓",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SummaryCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DcaFilterBar(
    selected: DcaFilter,
    total: Int,
    good: Int,
    slow: Int,
    profit: Int,
    onSelect: (DcaFilter) -> Unit
) {
    val options = DcaFilter.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            val count = when (option) {
                DcaFilter.ALL -> total
                DcaFilter.GOOD -> good
                DcaFilter.SLOW -> slow
                DcaFilter.PROFIT -> profit
            }
            SegmentedButton(
                selected = selected == option,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                enabled = count > 0,
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                icon = {},
                label = {
                    Text(
                        text = "${option.label} $count",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
            )
        }
    }
}

@Composable
private fun DcaCard(row: DcaRow, onClick: () -> Unit) {
    val report = row.report
    val verdict = report?.verdict ?: DcaVerdict.WATCH
    val action = report?.action ?: DcaAction.PAUSE
    val accent = verdictColor(verdict)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.displayName.ifBlank { row.entry.code },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "${row.entry.code} · ${marketLabel(row.entry.market)}行业指数",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    val change = row.changePct
                    Text(
                        text = change?.let { growthText(it) } ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = change?.let { growthColor(it) }
                            ?: MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "最新日线",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (report == null) {
                Text(
                    text = row.note.ifBlank { "行情暂不可用" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                ScoreBadge(report.score, verdict, accent)
                Spacer(Modifier.width(8.dp))
                ActionPill(action, actionColor(action))
                Spacer(Modifier.weight(1f))
                Text(
                    text = "评分 ${report.score}/100",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))
            ScoreBar(report.score, accent)
            Spacer(Modifier.height(10.dp))

            Text(
                text = report.actionReason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            Spacer(Modifier.height(10.dp))

            val sim = report.simulation
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (row.plan.enabled) "计划 · ${row.plan.cadenceText()}" else "还没设定投计划",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (row.plan.enabled) money(row.plan.amount) + " / 期" else "点开设置频率与金额",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = when {
                            sim == null -> "—"
                            sim.started -> "已投 ${sim.periods} 期 · 市值 ${money(sim.value)}"
                            else -> "还没扣过款"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (sim != null && sim.started) {
                        Text(
                            text = growthText(sim.profitPct),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = growthColor(sim.profitPct)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScoreBadge(score: Int, verdict: DcaVerdict, color: Color) {
    Surface(shape = RoundedCornerShape(12.dp), color = color.copy(alpha = 0.14f)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = score.toString(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = verdict.label,
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
    }
}

@Composable
private fun ScoreBar(score: Int, color: Color) {
    LinearProgressIndicator(
        progress = { score.coerceIn(0, 100) / 100f },
        modifier = Modifier
            .fillMaxWidth()
            .height(5.dp),
        color = color,
        trackColor = color.copy(alpha = 0.14f)
    )
}

@Composable
internal fun ActionPill(action: DcaAction, color: Color) {
    Surface(shape = RoundedCornerShape(10.dp), color = color.copy(alpha = 0.16f)) {
        Text(
            text = action.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
        )
    }
}

@Composable
internal fun verdictColor(verdict: DcaVerdict): Color = when (verdict) {
    DcaVerdict.GOOD -> LocalMarketColors.current.up
    DcaVerdict.WATCH -> Color(0xFFB07A16)
    DcaVerdict.POOR -> LocalMarketColors.current.down
}

@Composable
internal fun actionColor(action: DcaAction): Color = when (action) {
    DcaAction.START, DcaAction.CONTINUE -> MaterialTheme.colorScheme.primary
    DcaAction.REDUCE -> Color(0xFFB07A16)
    DcaAction.PAUSE -> LocalMarketColors.current.down
    DcaAction.TAKE_PROFIT -> Color(0xFFC2410C)
    DcaAction.STOP -> MaterialTheme.colorScheme.error
}

@Composable
private fun DcaSkeletonCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Box(
                modifier = Modifier
                    .height(16.dp)
                    .fillMaxWidth(0.45f)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        RoundedCornerShape(6.dp)
                    )
            )
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .height(12.dp)
                    .fillMaxWidth(0.9f)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        RoundedCornerShape(6.dp)
                    )
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .height(12.dp)
                    .fillMaxWidth(0.65f)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        RoundedCornerShape(6.dp)
                    )
            )
        }
    }
}

private fun timeText(timestamp: Long): String =
    SimpleDateFormat("HH:mm", Locale.US).format(Date(timestamp))

/**
 * 「添加行业指数」底部弹层。
 *
 * 搜索链路复用详情页那套（腾讯 smartbox → 东财行情），但文案完全不同：那里是
 * 「把这只基金的行情钉死」，这里是「我要观察这个行业」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DcaIndexSheet(
    state: TrackPickerState,
    existing: Set<String>,
    onQueryChange: (String) -> Unit,
    onPick: (IndexEntry) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = "添加行业指数",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "搜行业或主题，比如「白酒」「半导体」「光伏」；也可以直接输指数代码。" +
                    "沪深交易所指数、中证自编段（930/931/H30 开头）和港股指数都支持。" +
                    "指数比基金更适合判断定投价值：它有十几年的日线，能算清长期趋势和位置，" +
                    "也不会被单只基金的规模、换手带偏。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                placeholder = { Text("如 半导体、930697、H30533") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (state.searching) {
                    {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    }
                } else {
                    null
                }
            )

            if (state.message != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(12.dp))

            when {
                state.query.isBlank() -> {
                    Text(
                        text = "常用行业指数",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DCA_SUGGESTIONS.forEach { entry ->
                            SuggestionChip(
                                onClick = { onPick(entry) },
                                label = { Text(entry.name) }
                            )
                        }
                    }
                }

                state.results.isEmpty() && !state.searching -> {
                    Text(
                        text = "没有搜到。换个关键词，或直接输入指数代码（930697、H30533）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }

                else -> {
                    LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                        items(items = state.results, key = { it.secid }) { entry ->
                            val already = entry.secid in existing
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !already) { onPick(entry) }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = "${entry.code}  ·  " + marketLabel(entry.market),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    TextButton(onClick = { onPick(entry) }, enabled = !already) {
                                        Text(
                                            text = if (already) "已添加" else "添加",
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
