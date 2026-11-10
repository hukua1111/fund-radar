package com.hukua.fundradar.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hukua.fundradar.data.IndexEntry

/** Handy indices shown before the user types anything. */
internal val INDEX_SUGGESTIONS = listOf(
    IndexEntry("399997", 0, "中证白酒"),
    IndexEntry("000300", 1, "沪深300"),
    IndexEntry("000905", 1, "中证500"),
    IndexEntry("399006", 0, "创业板指"),
    IndexEntry("000688", 1, "科创50"),
    IndexEntry("399989", 0, "中证医疗")
)

/**
 * 「指定跟踪指数」底部弹层。
 *
 * 用在基金详情页：上游给的跟踪标的名和行情目录对不上时，用户在这里搜一个
 * 指数，把这只基金的行情来源钉死，估算和 K 线就都跟着走。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackIndexSheet(
    state: TrackPickerState,
    onQueryChange: (String) -> Unit,
    onPick: (IndexEntry) -> Unit,
    onClearCurrent: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        TrackIndexSheetContent(
            state = state,
            onQueryChange = onQueryChange,
            onPick = onPick,
            onClearCurrent = onClearCurrent
        )
    }
}

/** 弹层正文，拆出来方便截图测试直接渲染。 */
@Composable
fun TrackIndexSheetContent(
    state: TrackPickerState,
    onQueryChange: (String) -> Unit,
    onPick: (IndexEntry) -> Unit,
    onClearCurrent: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp)
    ) {
        Text(
            text = "指定跟踪指数",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "基金名里的「跟踪标的」只是一段文字，和行情里的指数简称常常差几个字。" +
                "在这里搜一个指数，这只基金的涨跌和 K 线就按它的行情来算。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (state.current != null) {
            Spacer(Modifier.height(12.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "当前已指定",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${state.current.name}  ${state.current.code}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = onClearCurrent) {
                        Text("恢复自动", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            placeholder = { Text("如 电网设备 或 931994") },
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
                            onClick = { onPick(entry) },
                            label = { Text(entry.name) }
                        )
                    }
                }
            }

            state.results.isEmpty() && !state.searching -> {
                Text(
                    text = "没有搜到，换个关键词或直接输入 6 位指数代码",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }

            else -> {
                LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                    items(items = state.results, key = { it.secid }) { entry ->
                        TrackIndexRow(
                            entry = entry,
                            selected = entry.secid == state.current?.secid,
                            onClick = { onPick(entry) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackIndexRow(
    entry: IndexEntry,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
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
            Text(
                text = if (selected) "当前" else "选用",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    }
}

/** 0 深市 / 1 沪市 / 2 中证指数公司自有代码。 */
internal fun marketLabel(market: Int): String = when (market) {
    1 -> "沪市"
    2 -> "中证指数"
    else -> "深市"
}
