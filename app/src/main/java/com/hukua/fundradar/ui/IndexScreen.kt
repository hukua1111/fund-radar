package com.hukua.fundradar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hukua.fundradar.data.IndexEntry
import com.hukua.fundradar.data.growthText
import com.hukua.fundradar.data.priceText
import com.hukua.fundradar.ui.theme.LocalMarketColors

@Composable
fun IndexScreen(
    viewModel: IndexViewModel,
    onOpenSettings: () -> Unit,
    onOpenKline: (IndexEntry, String) -> Unit,
    bottomBar: @Composable () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val error = state.error
    LaunchedEffect(error) {
        if (error != null) {
            snackbarHostState.showSnackbar("加载失败：$error")
            viewModel.consumeError()
        }
    }

    IndexContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onRefresh = viewModel::load,
        onOpenSettings = onOpenSettings,
        onOpenKline = onOpenKline,
        bottomBar = bottomBar
    )
}

/** Stateless index screen, also used by the JVM screenshot tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndexContent(
    state: IndexUiState,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onRefresh: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenKline: (IndexEntry, String) -> Unit = { _, _ -> },
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
                            text = "指数",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "自选基金跟踪的指数 · 点开看K线",
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
        when {
            state.watchlistEmpty -> {
                IndexEmpty(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    title = "还没有自选基金",
                    body = "先去「基金」页面添加几只指数基金，它们跟踪的指数会自动出现在这里。"
                )
            }

            state.loading && state.items.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }

            state.items.isEmpty() -> {
                IndexEmpty(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    title = "没有跟踪指数",
                    body = "自选里的基金都是主动管理型，没有跟踪标的。换成指数基金后这里会显示对应的指数。"
                )
            }

            else -> {
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
                        items(state.items, key = { it.tracked.entry.secid }) { item ->
                            IndexCard(
                                item = item,
                                onClick = {
                                    onOpenKline(item.tracked.entry, item.tracked.trackedName)
                                }
                            )
                        }
                        item(key = "footer") {
                            Text(
                                text = "指数行情来自东方财富公开数据，K 线与均线由本地计算。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IndexCard(item: IndexItem, onClick: () -> Unit) {
    val market = LocalMarketColors.current
    val quote = item.quote
    val change = quote?.changePct ?: 0.0
    val changeColor = when {
        quote == null -> MaterialTheme.colorScheme.onSurfaceVariant
        change > 0 -> market.up
        change < 0 -> market.down
        else -> market.flat
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.tracked.entry.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = buildString {
                            append(item.tracked.entry.code)
                            append("  ·  ")
                            append(item.tracked.fundCodes.size)
                            append(" 只基金跟踪")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = quote?.let { priceText(it.latest) } ?: "—",
                        style = MaterialTheme.typography.titleLarge,
                        color = changeColor
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = quote?.let { growthText(it.changePct) } ?: "暂无行情",
                        style = MaterialTheme.typography.labelMedium,
                        color = changeColor
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.tracked.trackedName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "K线 · AI 波段判断 →",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun IndexEmpty(modifier: Modifier, title: String, body: String) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Icon(
                    imageVector = IndexTabIcon,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(18.dp)
                        .size(28.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
