package com.hukua.fundradar

import com.hukua.fundradar.work.DailyScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class DailySchedulerTest {

    private fun at(hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        set(2026, Calendar.JANUARY, 5, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private val minute = 60_000L
    private val hour = 60 * minute

    @Test
    fun schedulesLaterTheSameDay() {
        val delay = DailyScheduler.delayUntil(14, 35, at(10, 0))
        assertEquals(4 * hour + 35 * minute, delay)
    }

    @Test
    fun schedulesNextDayWhenTimeAlreadyPassed() {
        val delay = DailyScheduler.delayUntil(14, 35, at(15, 0))
        assertEquals(23 * hour + 35 * minute, delay)
    }

    @Test
    fun schedulesNextDayWhenExactlyOnTime() {
        val delay = DailyScheduler.delayUntil(14, 35, at(14, 35))
        assertEquals(24 * hour, delay)
    }

    @Test
    fun delayIsAlwaysWithinOneDay() {
        listOf(0 to 0, 9 to 30, 14 to 35, 23 to 59).forEach { (h, m) ->
            val delay = DailyScheduler.delayUntil(h, m, at(12, 0))
            assertTrue("delay for $h:$m was $delay", delay > 0)
            assertTrue("delay for $h:$m was $delay", delay <= 24 * hour)
        }
    }
}
