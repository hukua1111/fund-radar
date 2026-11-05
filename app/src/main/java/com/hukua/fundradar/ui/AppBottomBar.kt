package com.hukua.fundradar.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

const val TAB_FUND = "fund"
const val TAB_INDEX = "index"

/** Bottom navigation shared by the app and the screenshot tests. */
@Composable
fun AppBottomBar(selected: String, onSelect: (String) -> Unit) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp
    ) {
        NavigationBarItem(
            selected = selected == TAB_FUND,
            onClick = { onSelect(TAB_FUND) },
            icon = { Icon(FundTabIcon, contentDescription = null) },
            label = { Text("基金") },
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
