package com.hukua.fundradar.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

const val TAB_PORTFOLIO = "portfolio"
const val TAB_LIMIT = "limit"
const val TAB_SIGNAL = "signal"

/** Kept so old previews/tests keep compiling; the portfolio is the fund tab. */
const val TAB_FUND = TAB_PORTFOLIO

/**
 * Bottom navigation shared by the app and the screenshot tests.
 *
 * 三个页签：持仓、限购、信号。信号页按跟踪指数的已收盘日线本地算买卖提示，
 * 与 AI 判断相互独立。
 */
@Composable
fun AppBottomBar(selected: String, onSelect: (String) -> Unit) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp
    ) {
        NavigationBarItem(
            selected = selected == TAB_PORTFOLIO,
            onClick = { onSelect(TAB_PORTFOLIO) },
            icon = { Icon(PortfolioTabIcon, contentDescription = null) },
            label = { Text("持仓") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                indicatorColor = MaterialTheme.colorScheme.secondaryContainer
            )
        )
        NavigationBarItem(
            selected = selected == TAB_LIMIT,
            onClick = { onSelect(TAB_LIMIT) },
            icon = { Icon(LimitTabIcon, contentDescription = null) },
            label = { Text("限购") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                indicatorColor = MaterialTheme.colorScheme.secondaryContainer
            )
        )
        NavigationBarItem(
            selected = selected == TAB_SIGNAL,
            onClick = { onSelect(TAB_SIGNAL) },
            icon = { Icon(IndexTabIcon, contentDescription = null) },
            label = { Text("信号") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                indicatorColor = MaterialTheme.colorScheme.secondaryContainer
            )
        )
    }
}
