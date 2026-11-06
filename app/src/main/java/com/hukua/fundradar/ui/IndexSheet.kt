package com.hukua.fundradar.ui

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
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
 * 添加指数的底部弹层。
 *
 * 指数并进持仓页之后，这个入口就是横滑条末尾的「＋ 添加指数」；手动加的
 * 指数只有行情和 K 线，不会凭空多出一只基金。
 */
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
                text = "${entry.code}  ·  " + marketLabel(entry.market),
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
