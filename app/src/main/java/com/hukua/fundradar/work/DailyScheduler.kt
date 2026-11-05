package com.hukua.fundradar.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.hukua.fundradar.data.AiConfig
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Keeps exactly one pending daily job. WorkManager persists it across process
 * death and reboots, and [DailyCheckWorker] enqueues the next occurrence when it
 * finishes, so the chain survives indefinitely.
 */
object DailyScheduler {

    const val WORK_NAME = "fund-radar-daily-ai"

    fun apply(context: Context, config: AiConfig) {
        val workManager = WorkManager.getInstance(context)
        if (!config.dailyEnabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = OneTimeWorkRequestBuilder<DailyCheckWorker>()
            .setInitialDelay(delayUntil(config.hour, config.minute), TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(WORK_NAME)
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** Milliseconds from [now] to the next occurrence of hour:minute. */
    fun delayUntil(hour: Int, minute: Int, now: Long = System.currentTimeMillis()): Long {
        val target = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= now) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis - now
    }
}
