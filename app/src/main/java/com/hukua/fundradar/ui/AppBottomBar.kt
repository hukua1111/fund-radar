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
const val TAB_INDEX = "index"

/** Kept so old previews/tests keep compiling; the portfolio is the fund tab. */
const val TAB_FUND = TAB_PORTFOLIO

/** Bottom navigation shared by the app and the screenshot tests. */
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
            selected = selected == TAB_INDEX,
            onClick = { onSelect(TAB_INDEX) },
            icon = { Icon(IndexTabIcon, contentDescription = null) },
            label = { Text("指数") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                indicatorColor = MaterialTheme.colorScheme.secondaryContainer
            )
        )
    }
}
