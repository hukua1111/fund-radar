package com.hukua.fundradar

import com.hukua.fundradar.data.AiVerdict
import com.hukua.fundradar.data.SwingAction
import com.hukua.fundradar.data.Trend
import com.hukua.fundradar.data.VerdictMemory
import com.hukua.fundradar.data.VerdictStabilizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 结论收敛：用户抱怨的「在观望和买入之间反复横跳」就是这里要挡住的东西。
 *
 * 每条用例对应一种真实会遇到的抖动场景，改阈值前先看这里。
 */
class VerdictStabilizerTest {

    private val today = "2026-11-10"

    private fun verdict(
        action: SwingAction,
        confidence: Int,
        trend: Trend = Trend.UNKNOWN,
        at: Long = 1_762_000_000_000L
    ) = AiVerdict(
        action = action,
        confidence = confidence,
        reason = "理由",
        risk = "风险",
        raw = "{}",
        at = at,
        trend = trend
    )

    private fun memory(
        action: SwingAction,
        confidence: Int,
        trend: Trend = Trend.UNKNOWN,
        day: String = today,
        switches: Int = 0
    ) = VerdictMemory(verdict(action, confidence, trend), day, switches)

    @Test
    fun firstJudgementPassesThrough() {
        val (out, next) = VerdictStabilizer.stabilize(
            verdict(SwingAction.BUY, 70, Trend.UP), memory = null, dayChangePct = 2.0, today = today
        )
        assertEquals(SwingAction.BUY, out.action)
        assertEquals(70, out.confidence)
        assertFalse(out.kept)
        assertEquals(today, next.day)
        assertEquals(0, next.switches)
    }

    @Test
    fun sameActionOnlySmoothsConfidence() {
        // 上次观望 60，这次观望 80 → 平滑成 72，动作不变、不算改口。
        val (out, next) = VerdictStabilizer.stabilize(
            verdict(SwingAction.HOLD, 80, Trend.RANGE),
            memory(SwingAction.HOLD, 60, Trend.RANGE),
            dayChangePct = 0.3,
            today = today
        )
        assertEquals(SwingAction.HOLD, out.action)
        assertEquals(72, out.confidence)
        assertFalse(out.kept)
        assertEquals(0, next.switches)
    }

    @Test
    fun weakSwitchIsBlockedAndExplained() {
        // 昨天收盘后说观望 62，今天涨了 0.4% 就想改买入 68：证据不够，拦住。
        val (out, next) = VerdictStabilizer.stabilize(
            verdict(SwingAction.BUY, 68, Trend.UP),
            memory(SwingAction.HOLD, 62, Trend.RANGE),
            dayChangePct = 0.4,
            today = today
        )
        assertEquals(SwingAction.HOLD, out.action)
        assertTrue(out.kept)
        // 界面要能说清「模型本来想买」。
        assertEquals(SwingAction.BUY, out.intended)
        assertEquals(0, next.switches)
    }

    @Test
    fun strongSwitchIsAllowed() {
        // 置信度一次跳 20 分，即使今天只涨 0.4% 也放行。
        val (out, next) = VerdictStabilizer.stabilize(
            verdict(SwingAction.BUY, 84, Trend.UP),
            memory(SwingAction.HOLD, 60, Trend.RANGE),
            dayChangePct = 0.4,
            today = today
        )
        assertEquals(SwingAction.BUY, out.action)
        assertFalse(out.kept)
        assertEquals(1, next.switches)
    }

    @Test
    fun bigDayMoveLowersTheBar() {
        // 今天涨 2.3%，属于「市场确实动了」，68 分也允许改口。
        val (out, _) = VerdictStabilizer.stabilize(
            verdict(SwingAction.BUY, 68, Trend.UP),
            memory(SwingAction.HOLD, 60, Trend.RANGE),
            dayChangePct = 2.3,
            today = today
        )
        assertEquals(SwingAction.BUY, out.action)
        assertFalse(out.kept)
    }

    @Test
    fun secondSwitchSameDayNeedsHighConfidence() {
        // 今天已经改过一次，再想改回去：80 分但 trend 是 RANGE，不放行。
        val (out, _) = VerdictStabilizer.stabilize(
            verdict(SwingAction.SELL, 80, Trend.RANGE),
            memory(SwingAction.BUY, 70, Trend.UP, switches = 1),
            dayChangePct = 3.0,
            today = today
        )
        assertEquals(SwingAction.BUY, out.action)
        assertTrue(out.kept)
    }

    @Test
    fun secondSwitchAllowedWhenConfidentAndAligned() {
        // 同日改口第二次：78 分 + trend=DOWN 与 SELL 同向，放行。
        val (out, next) = VerdictStabilizer.stabilize(
            verdict(SwingAction.SELL, 78, Trend.DOWN),
            memory(SwingAction.BUY, 70, Trend.UP, switches = 1),
            dayChangePct = 3.0,
            today = today
        )
        assertEquals(SwingAction.SELL, out.action)
        assertFalse(out.kept)
        assertEquals(2, next.switches)
    }

    @Test
    fun switchingCountResetsOnNewDay() {
        val (out, next) = VerdictStabilizer.stabilize(
            verdict(SwingAction.SELL, 80, Trend.DOWN),
            memory(SwingAction.BUY, 70, Trend.UP, day = "2026-11-09", switches = 3),
            dayChangePct = 2.0,
            today = today
        )
        assertEquals(SwingAction.SELL, out.action)
        assertEquals(1, next.switches)
    }

    @Test
    fun rangeTrendCannotBuy() {
        // 模型自己说震荡又要买入，自相矛盾：按震荡处理，置信度压到 55 以下。
        val (out, _) = VerdictStabilizer.stabilize(
            verdict(SwingAction.BUY, 88, Trend.RANGE),
            memory(SwingAction.HOLD, 60, Trend.RANGE, switches = 1),
            dayChangePct = 3.0,
            today = today
        )
        assertEquals(SwingAction.HOLD, out.action)
        // 88 分被压下来：震荡档位下不该给出高置信度的买入。
        assertTrue(out.confidence <= 60)
    }

    @Test
    fun unknownPreviousActsLikeFirstJudgement() {
        // 上一次本身就没答出东西：不能被当成有效历史，否则会把后续判断永远锁死。
        val (out, next) = VerdictStabilizer.stabilize(
            verdict(SwingAction.HOLD, 58, Trend.RANGE),
            memory(SwingAction.UNKNOWN, 0, Trend.UNKNOWN),
            dayChangePct = 0.2,
            today = today
        )
        assertEquals(SwingAction.HOLD, out.action)
        assertFalse(out.kept)
        assertEquals(0, next.switches)
    }

    @Test
    fun unparsableReplyKeepsPrevious() {
        val (out, _) = VerdictStabilizer.stabilize(
            verdict(SwingAction.UNKNOWN, 0, Trend.UNKNOWN),
            memory(SwingAction.SELL, 66, Trend.DOWN),
            dayChangePct = 0.0,
            today = today
        )
        assertEquals(SwingAction.SELL, out.action)
        assertTrue(out.kept)
    }

    @Test
    fun unknownDayChangeDoesNotBlockSwitch() {
        // 拿不到当日涨跌就别当「没动」处理，否则一律锁死。
        val (out, _) = VerdictStabilizer.stabilize(
            verdict(SwingAction.BUY, 76, Trend.UP),
            memory(SwingAction.HOLD, 60, Trend.RANGE),
            dayChangePct = null,
            today = today
        )
        assertEquals(SwingAction.BUY, out.action)
        assertFalse(out.kept)
    }

    @Test
    fun expiredVerdictCanSwitchWithoutConfidenceJump() {
        // 上次 HOLD 62，这次 BUY 64：只高 2 分、当天也没怎么动，正常情况下会被拦。
        val (blocked, _) = VerdictStabilizer.stabilize(
            verdict(SwingAction.BUY, 64, Trend.UP),
            memory(SwingAction.HOLD, 62, Trend.RANGE),
            dayChangePct = 0.3, today = today
        )
        assertEquals(SwingAction.HOLD, blocked.action)
        assertTrue(blocked.kept)

        // 结论已过有效期，同一个新结论应当放行。
        val (passed, _) = VerdictStabilizer.stabilize(
            verdict(SwingAction.BUY, 64, Trend.UP),
            memory(SwingAction.HOLD, 62, Trend.RANGE),
            dayChangePct = 0.3, today = today, previousExpired = true
        )
        assertEquals(SwingAction.BUY, passed.action)
        assertFalse(passed.kept)
    }

    @Test
    fun expiryDoesNotBypassSameDayCap() {
        // 过期只免「涨 15 分」这道门槛，同日第二次改口的限制仍然生效。
        val (out, _) = VerdictStabilizer.stabilize(
            verdict(SwingAction.BUY, 70, Trend.RANGE),
            memory(SwingAction.SELL, 40, Trend.DOWN, switches = 1),
            dayChangePct = 0.2, today = today, previousExpired = true
        )
        assertEquals(SwingAction.SELL, out.action)
        assertTrue(out.kept)
    }
}
