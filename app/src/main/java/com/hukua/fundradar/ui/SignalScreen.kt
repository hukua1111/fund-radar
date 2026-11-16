package com.hukua.fundradar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.hukua.fundradar.data.Fund
import com.hukua.fundradar.data.SignalAction
import com.hukua.fundradar.data.TradeSignal
import com.hukua.fundradar.ui.theme.LocalMarketColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「信号」页：持有基金的买入 / 卖出信号 + 未持有基金的买入信号。
 *
 * 全部来自跟踪指数的日线（盘中含实时 bar）、由 [com.hukua.fundradar.data.SignalEngine]
 * 本地计算，与 AI 判断互不依赖。盘中信号收盘前可能变化，页面有明确提示；
 * 页面只摆信号和依据，买不买由用户决定。
 */
@Composable
fun SignalScreen(
    viewModel: SignalViewModel,
    onOpenSettings: () -> Unit = {},
    onOpenDetail: (Fund) -> Unit = {},
    bottomBar: @Composable () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val error = state.error
    LaunchedEffect(error) {
        if (error != null) {
            snackbarHostState.showSnackbar(error)
            viewModel.consumeError()
        }
    }

    SignalContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onRefresh = { viewModel.load(force = true) },
        onOpenSettings = onOpenSettings,
        onOpenDetail = onOpenDetail,
        bottomBar = bottomBar
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalContent(
    state: SignalUiState,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onRefresh: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenDetail: (Fund) -> Unit = {},
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
                            text = "买卖信号",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "按跟踪指数 · 盘中实时 / 收盘定型 · 本地计算",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
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
        if (state.watchlistEmpty && !state.loading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "还没有添加基金",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "先到「持仓」页添加基金，这里会按每只基金跟踪的指数给出买卖信号",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
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
                item(key = "summary") { SignalSummaryCard(state) }

                if (state.loading) {
                    items(count = 3, key = { "skeleton-$it" }) { SignalSkeletonCard() }
                } else {
                    val held = state.heldRows
                    val unheld = state.unheldRows

                    if (held.isNotEmpty()) {
                        item(key = "header-held") {
                            SignalSectionHeader(title = "持有基金", count = held.size, caption = "买入 / 卖出信号")
                        }
                        items(items = held, key = { "held-${it.fund.code}" }) { row ->
                            SignalCard(row = row, onClick = { onOpenDetail(row.fund) })
                        }
                    }

                    if (unheld.isNotEmpty()) {
                        item(key = "header-unheld") {
                            SignalSectionHeader(title = "未持有基金", count = unheld.size, caption = "只看买入信号")
                        }
                        items(items = unheld, key = { "unheld-${it.fund.code}" }) { row ->
                            SignalCard(row = row, onClick = { onOpenDetail(row.fund) })
                        }
                    }

                    item(key = "footer") {
                        Text(
                            text = "信号由跟踪指数的日线本地计算（盘中含实时行情，收盘前可能变化），" +
                                "与 AI 判断相互独立；不含费率、赎回期与申赎时点，仅供参考，不构成投资建议",
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
}

@Composable
private fun SignalSummaryCard(state: SignalUiState) {
    val market = LocalMarketColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "买入信号 ${state.buyCount}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (state.buyCount > 0) market.up else MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = "卖出信号 ${state.sellCount}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (state.sellCount > 0) market.down else MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                if (state.loading || state.refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "更新于 " + timeText(state.updatedAt) +
                    if (state.live) " · 盘中实时计算，收盘前信号可能变化或消失"
                    else " · 已收盘定型，信号当天不再变化",
                style = MaterialTheme.typography.labelSmall,
                color = if (state.live) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SignalSectionHeader(title: String, count: Int, caption: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 2.dp, end = 2.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "$count 只",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SignalCard(row: SignalRow, onClick: () -> Unit) {
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
                        text = row.fund.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.width(8.dp))
                val report = row.report
                if (report != null && report.signals.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (report.hasBuy) SignalPill("买入", LocalMarketColors.current.up)
                        if (report.hasSell) SignalPill("卖出", LocalMarketColors.current.down)
                    }
                }
            }

            if (row.indexName.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "跟踪指数：" + row.indexName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val report = row.report
            when {
                report == null -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = row.note.ifBlank { "暂无信号数据" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                report.signals.isNotEmpty() -> {
                    report.signals.forEach { signal ->
                        Spacer(Modifier.height(10.dp))
                        SignalDetail(signal)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "基准日 ${report.asOf}" + (if (report.live) "（盘中实时）" else "") +
                            " · ${report.summary}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }

                else -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (report.note.isNotBlank()) report.note
                        else "无信号 · " + report.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (report.asOf.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "基准日 ${report.asOf}" + (if (report.live) "（盘中实时）" else ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SignalDetail(signal: TradeSignal) {
    val color = when (signal.action) {
        SignalAction.BUY -> LocalMarketColors.current.up
        SignalAction.SELL -> LocalMarketColors.current.down
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SignalPill(signal.action.label, color)
            Spacer(Modifier.width(8.dp))
            Text(
                text = signal.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = signal.reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SignalPill(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun SignalSkeletonCard() {
    val block = MaterialTheme.colorScheme.surfaceContainerHighest
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Box(
                Modifier
                    .fillMaxWidth(0.6f)
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
        }
    }
}

private fun timeText(timestamp: Long): String {
    if (timestamp <= 0L) return "—"
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}