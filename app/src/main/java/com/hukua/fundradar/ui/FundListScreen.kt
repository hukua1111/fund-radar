package com.hukua.fundradar.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hukua.fundradar.data.Fund
import com.hukua.fundradar.data.FundScope
import com.hukua.fundradar.data.LimitChange
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Funds suggested on the empty screen so the first run is not a blank page. */
private val SUGGESTIONS = listOf(
    "008280" to "国泰煤炭ETF联接",
    "020640" to "广发半导体设备",
    "025833" to "天弘电网设备",
    "025491" to "平安卫星产业"
)

@Composable
fun FundRadarScreen(
    viewModel: FundViewModel,
    onOpenSettings: () -> Unit = {},
    onOpenDetail: (Fund) -> Unit = viewModel::openDetail,
    bottomBar: @Composable () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val addState by viewModel.add.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val error = state.error
    LaunchedEffect(error) {
        if (error != null) {
            snackbarHostState.showSnackbar("刷新失败：$error")
            viewModel.consumeError()
        }
    }

    FundRadarContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onRefresh = viewModel::refresh,
        onSelectFilter = viewModel::setFilter,
        onOpenDetail = onOpenDetail,
        onAddClick = viewModel::openAdd,
        onSuggestion = viewModel::addFund,
        onDismissChanges = viewModel::dismissChanges,
        onImportPortfolio = viewModel::importPortfolio,
        onOpenSettings = onOpenSettings,
        bottomBar = bottomBar
    )

    if (addState.open) {
        AddFundSheet(
            state = addState,
            onQueryChange = viewModel::onQueryChange,
            onAddFund = viewModel::addFund,
            onDismiss = viewModel::closeAdd
        )
    }
}

/**
 * Stateless main screen: everything it needs comes from [state], so it can be
 * rendered in previews and JVM screenshot tests.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FundRadarContent(
    state: FundUiState,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onRefresh: () -> Unit = {},
    onSelectFilter: (FundFilter) -> Unit = {},
    onOpenDetail: (Fund) -> Unit = {},
    onAddClick: () -> Unit = {},
    onSuggestion: (String) -> Unit = {},
    onDismissChanges: () -> Unit = {},
    onImportPortfolio: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    bottomBar: @Composable () -> Unit = {}
) {
    val portfolio = state.scope.isPortfolio
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = bottomBar,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "基金雷达",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (portfolio) "持仓概况 · 盘中估算涨跌"
                            else "限购监控 · 申购状态与单日额度",
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
                        Icon(Icons.Filled.Add, contentDescription = "添加基金")
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
            EmptyWatchlist(
                scope = state.scope,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                onAddClick = onAddClick,
                onSuggestion = onSuggestion,
                onImportPortfolio = onImportPortfolio
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
                if (!portfolio && state.changes.isNotEmpty()) {
                    item(key = "changes") {
                        ChangesCard(
                            changes = state.changes,
                            onOpen = { code ->
                                state.all.firstOrNull { it.code == code }?.let(onOpenDetail)
                            },
                            onDismiss = onDismissChanges
                        )
                    }
                }

                item(key = "summary") { SummaryCard(state) }

                if (!portfolio) {
                    item(key = "filters") {
                        FilterRow(
                            selected = state.filter,
                            onSelect = onSelectFilter
                        )
                    }
                }

                when {
                    state.loading -> {
                        items(count = 4, key = { "skeleton-$it" }) { SkeletonCard() }
                    }

                    state.visible.isEmpty() -> {
                        item(key = "empty") {
                            EmptyState(
                                message = state.error
                                    ?: "当前分类下暂无基金，换个筛选条件试试",
                                isError = state.error != null,
                                onRetry = onRefresh
                            )
                        }
                    }

                    else -> {
                        items(items = state.visible, key = { it.code }) { fund ->
                            FundCard(
                                fund = fund,
                                onClick = { onOpenDetail(fund) },
                                estimate = state.estimates[fund.code],
                                showLimitBadge = !portfolio
                            )
                        }
                        item(key = "footer") { Footer() }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- add sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFundSheet(
    state: AddFundState,
    onQueryChange: (String) -> Unit,
    onAddFund: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        AddFundSheetContent(
            state = state,
            onQueryChange = onQueryChange,
            onAddFund = onAddFund
        )
    }
}

/** Sheet body, split out so it can be rendered without a dialog window. */
@Composable
fun AddFundSheetContent(
    state: AddFundState,
    onQueryChange: (String) -> Unit,
    onAddFund: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp)
    ) {
            Text(
                text = "添加基金",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "输入基金代码或名称，点右侧 + 加入列表",
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
                placeholder = { Text("如 161725 或 招商白酒") },
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
                        text = "试试这些",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SUGGESTIONS.forEach { (code, label) ->
                            SuggestionChip(
                                onClick = { onAddFund(code) },
                                label = { Text(label) }
                            )
                        }
                    }
                }

                state.results.isEmpty() && !state.searching -> {
                    Text(
                        text = "没有找到匹配的基金",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }

                else -> {
                    LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                        items(items = state.results, key = { it.code }) { fund ->
                            AddResultRow(
                                fund = fund,
                                watched = state.watched.contains(fund.code),
                                adding = state.adding == fund.code,
                                onAdd = { onAddFund(fund.code) }
                            )
                        }
                    }
                }
            }
        }
}

@Composable
private fun AddResultRow(
    fund: Fund,
    watched: Boolean,
    adding: Boolean,
    onAdd: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fund.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = fund.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Spacer(Modifier.width(8.dp))
        LimitBadge(fund = fund)
        Spacer(Modifier.width(4.dp))
        if (watched) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "已添加",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
        } else {
            IconButton(onClick = onAdd, enabled = !adding) {
                if (adding) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Add, contentDescription = "添加 ${fund.name}")
                }
            }
        }
    }
}

// ---------------------------------------------------------------- pieces

@Composable
private fun EmptyWatchlist(
    scope: FundScope,
    modifier: Modifier = Modifier,
    onAddClick: () -> Unit,
    onSuggestion: (String) -> Unit,
    onImportPortfolio: () -> Unit
) {
    val portfolio = scope.isPortfolio
    Column(
        modifier = modifier.padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(34.dp)
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = if (portfolio) "还没有添加持仓" else "还没有监控限购的基金",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (portfolio) {
                "添加你持有的基金，这里只看概况和盘中估算涨跌，不判断限购"
            } else {
                "限购清单和持仓分开维护，只放你真正在意申购额度的基金"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onAddClick,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.height(48.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (portfolio) "添加基金" else "添加监控")
        }
        if (!portfolio) {
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onImportPortfolio) {
                Text("从持仓导入全部")
            }
        }
        Spacer(Modifier.height(if (portfolio) 28.dp else 18.dp))
        Text(
            text = "试试这些",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SUGGESTIONS.forEach { (code, label) ->
                SuggestionChip(
                    onClick = { onSuggestion(code) },
                    label = { Text(label) }
                )
            }
        }
    }
}

@Composable
private fun ChangesCard(
    changes: List<LimitChange>,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val hasNegative = changes.any { it.isNegative }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (hasNegative) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "限购变化",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${changes.size} 只",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("清除", style = MaterialTheme.typography.labelMedium)
                }
            }

            changes.forEachIndexed { index, change ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                }
                val accent = if (change.isNegative) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(change.code) }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = change.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = change.oldLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "  →  ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = change.newLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = accent
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(
    selected: FundFilter,
    onSelect: (FundFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FundFilter.entries.forEach { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = { Text(filter.label) },
                leadingIcon = if (selected == filter) {
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

/**
 * Compact header strip. The purchase limit is a secondary concern now — it
 * shows up as one small pill next to the count instead of three big numbers,
 * and only when there is something to report.
 */
@Composable
private fun SummaryCard(state: FundUiState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = (if (state.scope.isPortfolio) "持仓 " else "监控 ") +
                        state.all.size + " 只",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (state.scope.isPortfolio) {
                    if (state.estimatesLoading) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp
                        )
                    } else if (state.estimatedCount > 0) {
                        Spacer(Modifier.width(10.dp))
                        MiniPill(
                            text = "估算 " + state.estimatedCount,
                            container = MaterialTheme.colorScheme.secondaryContainer,
                            content = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                } else {
                    Spacer(Modifier.width(10.dp))
                    if (state.limitedCount > 0) {
                        MiniPill(
                            text = "限大额 " + state.limitedCount,
                            container = MaterialTheme.colorScheme.tertiaryContainer,
                            content = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    if (state.suspendedCount > 0) {
                        MiniPill(
                            text = "暂停 " + state.suspendedCount,
                            container = MaterialTheme.colorScheme.errorContainer,
                            content = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = buildString {
                    if (state.scope.isPortfolio) {
                        append("估算于 ")
                        append(timeText(state.estimatesAt.takeIf { it > 0 } ?: state.updatedAt))
                    } else {
                        append("更新于 ")
                        append(timeText(state.updatedAt))
                    }
                    append(" · 天天基金公开数据")
                    if (state.fromCache) append(" · 离线缓存")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.scope.isPortfolio) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = state.estimatesError
                        ?: "按目标 ETF / 重仓股 / 标的指数估算，每分钟自动刷新",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.estimatesError != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    }
                )
            }
        }
    }
}

@Composable
private fun MiniPill(text: String, container: Color, content: Color) {
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun EmptyState(
    message: String,
    isError: Boolean,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = if (isError) Icons.Filled.Info else Icons.Filled.Search,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onRetry) {
            Text("重新加载")
        }
    }
}

@Composable
private fun Footer() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "数据来自天天基金公开页面，仅供参考，不构成投资建议",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SkeletonCard() {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    val block = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = alpha)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(16.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(block)
                )
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier
                        .width(72.dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(50))
                        .background(block)
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth(0.45f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(block)
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(4) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(26.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(block)
                    )
                }
            }
        }
    }
}

private fun timeText(timestamp: Long): String {
    if (timestamp <= 0L) return "—"
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
}
