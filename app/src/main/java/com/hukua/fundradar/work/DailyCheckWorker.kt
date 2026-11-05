package com.hukua.fundradar.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hukua.fundradar.data.AiAdvisor
import com.hukua.fundradar.data.BatchItem
import com.hukua.fundradar.data.BarkNotifier
import com.hukua.fundradar.data.EastMoneyApi
import com.hukua.fundradar.data.FundRepository
import com.hukua.fundradar.data.SettingsStore
import com.hukua.fundradar.data.SwingAction
import com.hukua.fundradar.data.WatchlistStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs once a day at the configured time: pulls the K line of every watchlist
 * fund, asks the AI in a single batched request, and pushes the summary through
 * Bark. Always schedules the next run, even when it fails.
 */
class DailyCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = SettingsStore(applicationContext)
        val config = store.load()
        if (!config.dailyEnabled) return Result.success()

        if (!config.configured) {
            notify(config, "AI 未配置", "请在「设置」里填写 AI 地址、模型和 Key")
            scheduleNext(config)
            return Result.success()
        }

        val codes = WatchlistStore(applicationContext).codes().take(MAX_FUNDS)
        if (codes.isEmpty()) {
            notify(config, "自选为空", "先添加几只基金，再开启每日判断")
            scheduleNext(config)
            return Result.success()
        }

        val repository = FundRepository(applicationContext)
        val items = codes.mapNotNull { code ->
            val series = runCatching { EastMoneyApi.fetchKline(code, 250) }.getOrNull()
                ?: return@mapNotNull null
            val name = series.name.ifBlank { repository.cachedName(code) ?: code }
            BatchItem(code = code, name = name, series = series)
        }

        if (items.isEmpty()) {
            notify(config, "数据获取失败", "今天没有取到任何行情数据，稍后再试")
            scheduleNext(config)
            return Result.success()
        }

        runCatching { AiAdvisor.judgeBatch(config, items) }
            .onSuccess { verdicts ->
                val names = items.associate { it.code to it.name }
                val body = verdicts
                    .sortedByDescending { it.action != SwingAction.HOLD }
                    .joinToString("\n") { verdict ->
                        "${names[verdict.code] ?: verdict.code}：${verdict.action.label}" +
                            " ${verdict.confidence}%" +
                            if (verdict.reason.isNotBlank()) " · ${verdict.reason}" else ""
                    }
                    .ifBlank { "AI 没有返回可用的判断结果" }
                notify(config, "波段判断 · ${stamp()}", body)
            }
            .onFailure { throwable ->
                notify(
                    config,
                    "AI 判断失败 · ${stamp()}",
                    throwable.message ?: "未知错误"
                )
            }

        scheduleNext(config)
        return Result.success()
    }

    private suspend fun notify(config: com.hukua.fundradar.data.AiConfig, title: String, body: String) {
        if (!config.barkConfigured) return
        runCatching { BarkNotifier.push(config, title, body) }
    }

    private fun scheduleNext(config: com.hukua.fundradar.data.AiConfig) {
        runCatching { DailyScheduler.apply(applicationContext, config) }
    }

    private fun stamp(): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())

    private companion object {
        /** Keeps the single AI call short and the push readable. */
        const val MAX_FUNDS = 12
    }
}
