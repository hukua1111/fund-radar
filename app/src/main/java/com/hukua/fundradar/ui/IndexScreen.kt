package com.hukua.fundradar.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hukua.fundradar.data.IndexEntry
import com.hukua.fundradar.data.growthText
import com.hukua.fundradar.data.priceText
import com.hukua.fundradar.ui.theme.LocalMarketColors

/** Handy indices shown before the user types anything. */
private val INDEX_SUGGESTIONS = listOf(
    IndexEntry("399997", 0, "中证白酒"),
    IndexEntry("000300", 1, "沪深300"),
    IndexEntry("000905", 1, "中证500"),
    IndexEntry("399006", 0, "创业板指"),
    IndexEntry("000688", 1, "科创50"),
    IndexEntry("399989", 0, "中证医疗")
)

@Composable
fun IndexScreen(
    viewModel: IndexViewModel,
    onOpenSettings: () -> Unit,
    onOpenKline: (IndexEntry, String) -> Unit,
    bottomBar: @Composable () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val addState by viewModel.add.collectAsStateWithLifecycle()
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
        onAddClick = viewModel::openAdd,
        onRemoveIndex = viewModel::removeIndex,
        bottomBar = bottomBar
    )

    if (addState.open) {
        AddIndexSheet(
            state = addState,
            onQueryChange = viewModel::onQueryChange,
            onAddIndex = viewModel::addIndex,
            onDismiss = viewModel::closeAdd
        )
    }
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
    onAddClick: () -> Unit = {},
    onRemoveIndex: (String) -> Unit = {},
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
                    IconButton(onClick = onAddClick) {
                        Icon(Icons.Filled.Add, contentDescription = "添加指数")
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
                    body = "先去「基金」页面添加几只指数基金，它们跟踪的指数会自动出现在这里；" +
                        "也可以直接添加你想盯的指数。",
                    onAddClick = onAddClick
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
                    body = "自选里的基金都是主动管理型，没有跟踪标的。换成指数基金后这里会显示对应的指数，" +
                        "也可以手动添加。",
                    onAddClick = onAddClick
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
                                },
                                onRemove = if (item.tracked.manual) {
                                    { onRemoveIndex(item.tracked.entry.secid) }
                                } else {
                                    null
                                }
                            )
                        }
                        item(key = "footer") {
                            Text(
                                text = "指数行情来自东方财富公开数据，K 线与均线由本地计算。" +
                                    if (state.manualCount > 0) " 其中 ${state.manualCount} 只是手动添加。" else "",
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
private fun IndexCard(item: IndexItem, onClick: () -> Unit, onRemove: (() -> Unit)? = null) {
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
                            append(
                                if (item.tracked.manual) {
                                    "手动添加"
                                } else {
                                    "${item.tracked.fundCodes.size} 只基金跟踪"
                                }
                            )
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
                    text = if (item.tracked.manual) "不随自选基金变化" else item.tracked.trackedName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (onRemove != null) {
                    Spacer(Modifier.width(4.dp))
                    TextButton(
                        onClick = onRemove,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("移除", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.width(4.dp))
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
private fun IndexEmpty(
    modifier: Modifier,
    title: String,
    body: String,
    onAddClick: (() -> Unit)? = null
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(horizontal = 36.dp),
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (onAddClick != null) {
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onAddClick,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.height(46.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("添加指数")
                }
            }
        }
    }
}

// ------------------------------------------------------------- add sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddIndexSheet(
    state: IndexAddState,
    onQueryChange: (String) -> Unit,
    onAddIndex: (IndexEntry) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        AddIndexSheetContent(
            state = state,
            onQueryChange = onQueryChange,
            onAddIndex = onAddIndex
        )
    }
}

/** Sheet body, split out so it can be rendered without a dialog window. */
@Composable
fun AddIndexSheetContent(
    state: IndexAddState,
    onQueryChange: (String) -> Unit,
    onAddIndex: (IndexEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp)
    ) {
        Text(
            text = "添加指数",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "输入指数代码或名称，点右侧 + 加入列表；基金跟踪的指数会自动出现，不需要重复添加",
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
            placeholder = { Text("如 399997 或 中证白酒") },
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
                    text = "常用指数",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    INDEX_SUGGESTIONS.forEach { entry ->
                        SuggestionChip(
                            onClick = { onAddIndex(entry) },
                            label = { Text(entry.name) }
                        )
                    }
                }
            }

            state.results.isEmpty() && !state.searching -> {
                Text(
                    text = "没有找到匹配的指数，换个代码或名称试试",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }

            else -> {
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    items(items = state.results, key = { it.secid }) { entry ->
                        AddIndexRow(
                            entry = entry,
                            added = state.existing.contains(entry.secid),
                            adding = state.adding == entry.secid,
                            onAdd = { onAddIndex(entry) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddIndexRow(
    entry: IndexEntry,
    added: Boolean,
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
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${entry.code}  ·  " + if (entry.market == 1) "沪市" else "深市",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        if (added) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "已在列表",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
        } else {
            IconButton(onClick = onAdd, enabled = !adding) {
                if (adding) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Add, contentDescription = "添加 ${entry.name}")
                }
            }
        }
    }
}
