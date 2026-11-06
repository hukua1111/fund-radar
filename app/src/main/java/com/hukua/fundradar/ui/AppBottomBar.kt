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

/** Kept so old previews/tests keep compiling; the portfolio is the fund tab. */
const val TAB_FUND = TAB_PORTFOLIO

/**
 * Bottom navigation shared by the app and the screenshot tests.
 *
 * 只有两项：持仓和限购。指数不再单独占一格 —— 它只是自选基金跟踪标的的
 * 汇总，放在持仓页顶部横滑条里，看基金的时候顺手就能看，也更符合实际用法。
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
    }
}
