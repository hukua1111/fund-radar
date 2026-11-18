package com.hukua.fundradar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hukua.fundradar.data.DcaAction
import com.hukua.fundradar.data.DcaVerdict
import com.hukua.fundradar.data.Estimate
import com.hukua.fundradar.data.Fund
import com.hukua.fundradar.data.growthText
import com.hukua.fundradar.data.money
import com.hukua.fundradar.data.navText
import com.hukua.fundradar.data.signedAmountText
import com.hukua.fundradar.ui.theme.LocalMarketColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「定投」页：一份独立的基金清单，回答两个问题 ——
 *
 * 1. 这个行业 / 指数基金还值不值得按月买（左侧评分 + 五档依据）；
 * 2. 已经在投的这只，现在该继续、减半、暂停，还是止盈卖出（右侧动作）。
 *
 * 判断全部来自跟踪指数的日线本地计算，AI 只是可选的第二意见，点了才请求。
 */
@Composable
fun DcaScreen(
    fundViewModel: FundViewModel,
    dcaViewModel: DcaViewModel,
    onOpenSettings: () -> Unit = {},
    onOpenDetail: (Fund) -> Unit = {},
    bottomBar: @Composable () -> Unit = {}
) {
    val fundState by fundViewModel.state.collectAsStateWithLifecycle()
    val addState by fundViewModel.add.collectAsStateWithLifecycle()
    val dcaState by dcaViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 基金列表、持仓、估算都由持仓页那套 view model 负责，这里只把结果推给定投判断。
    val codesKey = fundState.all.joinToString(",") { it.code }
    LaunchedEffect(codesKey, fundState.holdings, fundState.estimates) {
        dcaViewModel.evaluate(fundState.all, fundState.holdings, fundState.estimates)
    }

    val error = dcaState.error ?: fundState.error
    LaunchedEffect(error) {
        if (error != null) {
            snackbarHostState.showSnackbar(error)
            dcaViewModel.consumeError()
            fundViewModel.consumeError()
        }
    }

    DcaContent(
        state = dcaState,
        estimates = fundState.estimates,
        loading = fundState.loading || dcaState.loading,
        snackbarHostState = snackbarHostState,
        onRefresh = {
            fundViewModel.refresh()
            dcaViewModel.refresh()
        },
        onAddClick = fundViewModel::openAdd,
        onSelectFilter = dcaViewModel::setFilter,
        onOpenSettings = onOpenSettings,
        onOpenDetail = { fund ->
            dcaViewModel.openDetail(fund.code)
            onOpenDetail(fund)
        },
        onAskAi = { fund ->
            dcaViewModel.askAi(fund.code)
            dcaViewModel.openDetail(fund.code)
            onOpenDetail(fund)
        },
        bottomBar = bottomBar
    )

    if (addState.open) {
        AddFundSheet(
            state = addState,
            onQueryChange = fundViewModel::onQueryChange,
            onAddFund = fundViewModel::addFund,
            onDismiss = fundViewModel::closeAdd
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DcaContent(
    state: DcaUiState,
    estimates: Map<String, Estimate> = emptyMap(),
    loading: Boolean = false,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onRefresh: () -> Unit = {},
    onAddClick: () -> Unit = {},
    onSelectFilter: (DcaFilter) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenDetail: (Fund) -> Unit = {},
    onAskAi: (Fund) -> Unit = {},
    bottomBar: @Composable () -> Unit = {}
) {
    Scaffold(
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
                            text = "定投价值 · 加减速 · 止盈提醒",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onAddClick) {
                        Icon(Icons.Filled.Add, contentDescription = "添加基金")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
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
        if (state.watchlistEmpty && !loading) {
            EmptyDca(innerPadding, onAddClick)
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

                item(key = "filter") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DcaFilter.entries.forEach { filter ->
                            FilterChip(
                                selected = state.filter == filter,
                                onClick = { onSelectFilter(filter) },
                                label = { Text(filter.label) },
                                leadingIcon = if (state.filter == filter) {
                                    {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                } else {
                                    null
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }
                }

                if (loading && state.rows.isEmpty()) {
                    items(count = 3, key = { "skeleton-$it" }) { DcaSkeletonCard() }
                } else if (state.visible.isEmpty()) {
                    item(key = "empty-filter") {
                        Text(
                            text = if (state.rows.isEmpty()) {
                                "正在读取跟踪指数与日线，稍等一下…"
                            } else {
                                "这个筛选下没有基金，换个分类看看"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 20.dp)
                        )
                    }
                } else {
                    items(items = state.visible, key = { it.fund.code }) { row ->
                        DcaCard(
                            row = row,
                            estimate = estimates[row.fund.code],
                            onClick = { onOpenDetail(row.fund) },
                            onAskAi = { onAskAi(row.fund) }
                        )
                    }
                }

                item(key = "footer") {
                    Text(
                        text = "定投评分看长期趋势、位置、波动与热度，全部由跟踪指数的日线本地计算；" +
                            "不含费率、持有期与申赎时点，仅供参考，不构成投资建议",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyDca(innerPadding: PaddingValues, onAddClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "还没有定投基金",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "定投是单独的一份清单，和持仓、限购互不影响。" +
                "加进来的基金会按跟踪指数判断值不值得按月买，已经投的会提示继续、减半、暂停还是止盈。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAddClick) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("添加定投基金")
        }
    }
}

@Composable
private fun DcaSummaryCard(state: DcaUiState) {
    val market = LocalMarketColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "定投 ${state.rows.size} 只",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    text = if (state.monthlyTotal > 0) "月度计划 " + money(state.monthlyTotal) else "未设计划",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                if (state.loading || state.refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "适合 ${state.goodCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "建议减/停 ${state.pauseCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "建议止盈 ${state.profitCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.profitCount > 0) market.down
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.holdingValue > 0) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "持仓 " + money(state.holdingValue),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(10.dp))
                    val profit = state.holdingProfit
                    Text(
                        text = "累计 " + signedAmountText(profit) + " 元",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (profit >= 0) market.up else market.down
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "更新于 " + timeText(state.updatedAt) +
                    if (state.live) " · 盘中有实时行情，收盘前评分可能变化"
                    else " · 已收盘定型",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DcaCard(
    row: DcaRow,
    estimate: Estimate?,
    onClick: () -> Unit,
    onAskAi: () -> Unit
) {
    val market = LocalMarketColors.current
    val report = row.report
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.fund.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = listOf(row.fund.code, row.indexName.ifBlank { row.fund.type })
                            .filter { it.isNotBlank() }
                            .joinToString("  ·  "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(10.dp))
                if (report != null) {
                    ScoreBadge(report.score, report.verdict, verdictColor(report.verdict))
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "净值 " + navText(row.fund.nav),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (estimate != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = growthText(estimate.percent),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (estimate.percent >= 0) market.up else market.down
                    )
                }
                Spacer(Modifier.weight(1f))
                if (report != null) {
                    ActionPill(report.action, actionColor(report.action))
                }
            }

            if (report != null) {
                Spacer(Modifier.height(10.dp))
                ScoreBar(report.score, verdictColor(report.verdict))
                Spacer(Modifier.height(8.dp))
                Text(
                    text = report.actionReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val meta = buildList {
                if (row.plan.enabled) {
                    add(
                        "月投 " + money(row.plan.monthlyAmount) + " · 已投 " +
                            row.plan.periods() + " 期"
                    )
                }
                val holding = row.holding
                if (holding != null) {
                    val value = holding.marketValue(row.fund.nav)
                    val pct = holding.profitPercent(row.fund.nav)
                    add(
                        "持仓 " + money(value) +
                            (pct?.let { " · " + growthText(it) } ?: "")
                    )
                }
            }
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = meta.joinToString("　|　"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (row.note.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = row.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "基准日 " + (report?.asOf?.ifBlank { "—" } ?: "—") +
                        if (report?.live == true) "（盘中）" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onAskAi, enabled = report != null) {
                    Text(if (row.plan.enabled) "AI 复核" else "AI 判断")
                }
            }
        }
    }
}

@Composable
private fun ScoreBadge(score: Int, verdict: DcaVerdict, color: Color) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = color.copy(alpha = 0.12f),
        contentColor = color
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
        ) {
            Text(
                text = score.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(text = verdict.label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ScoreBar(score: Int, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth((score.coerceIn(0, 100) / 100f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(color)
        )
    }
}

@Composable
internal fun ActionPill(action: DcaAction, color: Color) {
    Surface(
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = action.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
        )
    }
}

@Composable
internal fun verdictColor(verdict: DcaVerdict): Color = when (verdict) {
    DcaVerdict.GOOD -> MaterialTheme.colorScheme.primary
    DcaVerdict.WATCH -> MaterialTheme.colorScheme.tertiary
    DcaVerdict.POOR -> MaterialTheme.colorScheme.error
}

@Composable
internal fun actionColor(action: DcaAction): Color = when (action) {
    DcaAction.START, DcaAction.CONTINUE -> MaterialTheme.colorScheme.primary
    DcaAction.REDUCE, DcaAction.PAUSE -> MaterialTheme.colorScheme.tertiary
    DcaAction.TAKE_PROFIT, DcaAction.STOP -> LocalMarketColors.current.down
}

@Composable
private fun DcaSkeletonCard() {
    val block = MaterialTheme.colorScheme.surfaceContainerHighest
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Box(
                Modifier
                    .fillMaxWidth(0.55f)
                    .height(16.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(block)
            )
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth(0.9f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(block)
            )
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(block)
            )
        }
    }
}

private fun timeText(timestamp: Long): String {
    if (timestamp <= 0L) return "—"
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}
