package com.hukua.fundradar

import com.hukua.fundradar.data.Fund
import com.hukua.fundradar.data.Holding
import com.hukua.fundradar.ui.FundUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 列表分组的依据：有份额的算「持有」，只加进来看行情的算「未持有」。
 * 份额清空（全卖掉）之后要回到「未持有」，不能继续占着上半段。
 */
class HoldingGroupTest {

    @Test
    fun splitsHeldAndUnheld() {
        val held = fund("008280")
        val soldOut = fund("161725")
        val never = fund("012414")

        val state = FundUiState(
            all = listOf(held, soldOut, never),
            holdings = mapOf(
                held.code to Holding(shares = 702.97, cost = 1602.42),
                // 卖光之后只剩已实现盈亏，仍算「未持有」
                soldOut.code to Holding(shares = 0.0, cost = 0.0, realized = 88.79)
            )
        )

        assertEquals(1, state.heldCount)
        assertEquals(2, state.unheldCount)
        assertNull(state.holdingOf(soldOut))
        assertNull(state.holdingOf(never))
        assertEquals(702.97, state.holdingOf(held)?.shares ?: 0.0, 1e-6)
    }

    @Test
    fun countsFollowHoldingsMap() {
        val a = fund("000001")
        val b = fund("000002")
        val state = FundUiState(
            all = listOf(a, b),
            holdings = mapOf(a.code to Holding(shares = 100.0, cost = 120.0))
        )
        assertEquals(1, state.heldCount)
        assertEquals(1, state.unheldCount)

        val allHeld = state.copy(holdings = state.holdings + (b.code to Holding(shares = 50.0, cost = 60.0)))
        assertEquals(2, allHeld.heldCount)
        assertEquals(0, allHeld.unheldCount)
    }

    private fun fund(code: String) = Fund(
        code = code,
        name = "测试基金$code",
        type = "指数型-股票",
        nav = 1.0,
        dayGrowth = 0.0,
        buyStatus = "开放申购"
    )
}
