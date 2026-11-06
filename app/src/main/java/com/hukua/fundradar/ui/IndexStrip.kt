package com.hukua.fundradar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hukua.fundradar.data.IndexEntry
import com.hukua.fundradar.data.growthText
import com.hukua.fundradar.data.priceText
import com.hukua.fundradar.ui.theme.LocalMarketColors

/**
 * 持仓页顶部的「相关指数」横滑条。
 *
 * 指数原先单独占一个底栏 tab，但它其实只是自选基金跟踪标的去重后的集合，
 * 离开这些基金谁也不会天天去翻。并到持仓页之后：一眼能看到今天指数怎么走，
 * 点一张卡直接进 K 线与 AI 判断，底栏也回到两项。
 */
@Composable
fun IndexStrip(
    state: IndexUiState,
    onOpenKline: (IndexEntry, String) -> Unit,
    onAddClick: () -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "相关指数",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (state.items.isEmpty()) {
                    "暂无"
                } else {
                    "${state.items.size} 个 · 点开看K线"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(8.dp))

        if (state.items.isEmpty()) {
            // 空态不要只摆一个小方块：整条横幅说清楚「指数是跟着基金来的」，
            // 再加一个明确的添加按钮。
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onAddClick),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "添加指数",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "也可以先加基金，它跟踪的指数会自动出现在这里",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items = state.items, key = { it.tracked.entry.secid }) { item ->
                    IndexMiniCard(
                        item = item,
                        onClick = { onOpenKline(item.tracked.entry, item.tracked.trackedName) },
                        onRemove = if (item.tracked.manual) {
                            { onRemove(item.tracked.entry.secid) }
                        } else {
                            null
                        }
                    )
                }
                item(key = "add") { AddIndexCard(onClick = onAddClick) }
            }
        }

        if (state.unresolved.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "另有 ${state.unresolved.size} 只基金的「" +
                    state.unresolved.joinToString("、").take(20) +
                    "」没对上指数，可在基金详情里手动指定。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun IndexMiniCard(
    item: IndexItem,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?
) {
    val market = LocalMarketColors.current
    val quote = item.quote
    val change = quote?.changePct ?: 0.0
    val changeColor: Color = when {
        quote == null -> MaterialTheme.colorScheme.onSurfaceVariant
        change > 0 -> market.up
        change < 0 -> market.down
        else -> market.flat
    }
    val related = when {
        item.tracked.manual -> "手动添加"
        item.tracked.fundCodes.size == 1 -> "1 只基金跟踪"
        else -> "${item.tracked.fundCodes.size} 只基金跟踪"
    }

    Surface(
        modifier = Modifier
            .width(148.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        )
    ) {
        Box(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 11.dp)) {
            Column {
                Text(
                    text = item.tracked.entry.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // 留出右上角移除按钮的位置
                    modifier = Modifier.padding(end = if (onRemove != null) 18.dp else 0.dp)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.tracked.entry.code,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = quote?.let { priceText(it.latest) } ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                    color = changeColor
                )
                Text(
                    text = quote?.let { growthText(it.changePct) } ?: "暂无行情",
                    style = MaterialTheme.typography.labelMedium,
                    color = changeColor
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = related,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (onRemove != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            CircleShape
                        )
                        .clickable(onClick = onRemove),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "移除 ${item.tracked.entry.name}",
                        modifier = Modifier.size(11.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AddIndexCard(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .width(84.dp)
            .height(112.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "添加指数",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
