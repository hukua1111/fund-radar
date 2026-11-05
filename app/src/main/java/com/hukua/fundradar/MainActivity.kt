package com.hukua.fundradar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hukua.fundradar.data.Fund
import com.hukua.fundradar.data.SettingsStore
import com.hukua.fundradar.ui.FundDetailSheet
import com.hukua.fundradar.ui.FundRadarScreen
import com.hukua.fundradar.ui.FundViewModel
import com.hukua.fundradar.ui.KlineScreen
import com.hukua.fundradar.ui.KlineViewModel
import com.hukua.fundradar.ui.SettingsScreen
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
                val detail by viewModel.detail.collectAsStateWithLifecycle()

                var route by rememberSaveable { mutableStateOf("list") }
                var settingsReturn by rememberSaveable { mutableStateOf("list") }
                var klineCode by rememberSaveable { mutableStateOf("") }
                var klineName by rememberSaveable { mutableStateOf("") }

                val openKline: (Fund) -> Unit = { fund ->
                    klineCode = fund.code
                    klineName = fund.name
                    route = "kline"
                }

                val openSettings: () -> Unit = {
                    settingsReturn = route
                    route = "settings"
                }

                BackHandler(enabled = route != "list") {
                    route = if (route == "settings") settingsReturn else "list"
                }

                when (route) {
                    "settings" -> SettingsScreen(onBack = { route = settingsReturn })

                    "kline" -> {
                        val klineViewModel: KlineViewModel = viewModel(
                            key = "kline-$klineCode",
                            factory = KlineViewModel.factory(this, klineCode, klineName)
                        )
                        KlineScreen(
                            viewModel = klineViewModel,
                            onBack = { route = "list" },
                            onOpenSettings = openSettings
                        )
                    }

                    else -> {
                        FundRadarScreen(
                            viewModel = viewModel,
                            onOpenSettings = openSettings
                        )
                        detail?.let { state ->
                            FundDetailSheet(
                                state = state,
                                onDismiss = viewModel::closeDetail,
                                onRemove = viewModel::removeFund,
                                onOpenKline = { fund ->
                                    viewModel.closeDetail()
                                    openKline(fund)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
