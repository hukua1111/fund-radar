package com.hukua.fundradar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hukua.fundradar.data.IndexEntry
import com.hukua.fundradar.data.SettingsStore
import com.hukua.fundradar.ui.AppBottomBar
import com.hukua.fundradar.ui.FundDetailScreen
import com.hukua.fundradar.ui.FundRadarScreen
import com.hukua.fundradar.ui.FundViewModel
import com.hukua.fundradar.ui.IndexScreen
import com.hukua.fundradar.ui.IndexViewModel
import com.hukua.fundradar.ui.KlineScreen
import com.hukua.fundradar.ui.KlineViewModel
import com.hukua.fundradar.ui.SettingsScreen
import com.hukua.fundradar.ui.TAB_FUND
import com.hukua.fundradar.ui.TAB_INDEX
import com.hukua.fundradar.ui.theme.FundRadarTheme
import com.hukua.fundradar.work.DailyScheduler

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Keeps the daily job alive after a reinstall or cache clear.
        runCatching { DailyScheduler.apply(this, SettingsStore(this).load()) }

        setContent {
            FundRadarTheme {
                val viewModel: FundViewModel = viewModel(factory = FundViewModel.factory(this))
                val indexViewModel: IndexViewModel = viewModel(factory = IndexViewModel.factory(this))
                val detail by viewModel.detail.collectAsStateWithLifecycle()

                var route by rememberSaveable { mutableStateOf("list") }
                var tab by rememberSaveable { mutableStateOf(TAB_FUND) }
                var settingsReturn by rememberSaveable { mutableStateOf("list") }
                var klineSecid by rememberSaveable { mutableStateOf("") }
                var klineCode by rememberSaveable { mutableStateOf("") }
                var klineName by rememberSaveable { mutableStateOf("") }

                val openKline: (IndexEntry, String) -> Unit = { entry, trackedName ->
                    klineSecid = entry.secid
                    klineCode = entry.code
                    klineName = entry.name.ifBlank { trackedName }
                    route = "kline"
                }

                val openSettings: () -> Unit = {
                    settingsReturn = route
                    route = "settings"
                }

                // Detail is a page of its own now, not a bottom sheet: back from
                // it lands on the fund list, never on a stale sheet.
                val backToList: () -> Unit = {
                    viewModel.closeDetail()
                    route = "list"
                }

                BackHandler(enabled = route != "list") {
                    when (route) {
                        "settings" -> route = settingsReturn
                        "fund" -> backToList()
                        else -> route = "list"
                    }
                }

                // Index quotes are cheap but change intraday; refresh on tab entry.
                LaunchedEffect(tab, route) {
                    if (route == "list" && tab == TAB_INDEX) indexViewModel.load()
                }

                val bottomBar: @Composable () -> Unit = {
                    AppBottomBar(selected = tab, onSelect = { tab = it })
                }

                when (route) {
                    "settings" -> SettingsScreen(onBack = { route = settingsReturn })

                    "fund" -> {
                        val state = detail
                        if (state == null) {
                            // Removed from the watchlist (or restored without a
                            // selection): nothing to show, fall back to the list.
                            LaunchedEffect(Unit) { route = "list" }
                        } else {
                            FundDetailScreen(
                                state = state,
                                onBack = backToList,
                                onRemove = { code ->
                                    viewModel.removeFund(code)
                                    route = "list"
                                }
                            )
                        }
                    }

                    "kline" -> {
                        val klineViewModel: KlineViewModel = viewModel(
                            key = "kline-$klineSecid",
                            factory = KlineViewModel.factory(this, klineSecid, klineCode, klineName)
                        )
                        KlineScreen(
                            viewModel = klineViewModel,
                            onBack = { route = "list" },
                            onOpenSettings = openSettings
                        )
                    }

                    else -> when (tab) {
                        TAB_INDEX -> IndexScreen(
                            viewModel = indexViewModel,
                            onOpenSettings = openSettings,
                            onOpenKline = openKline,
                            bottomBar = bottomBar
                        )

                        else -> FundRadarScreen(
                            viewModel = viewModel,
                            onOpenSettings = openSettings,
                            onOpenDetail = { fund ->
                                viewModel.openDetail(fund)
                                route = "fund"
                            },
                            bottomBar = bottomBar
                        )
                    }
                }
            }
        }
    }
}
