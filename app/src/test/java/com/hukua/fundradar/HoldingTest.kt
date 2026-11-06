package com.hukua.fundradar

import com.hukua.fundradar.data.Holding
import com.hukua.fundradar.data.Trade
import com.hukua.fundradar.data.TradeKind
import com.hukua.fundradar.data.amountText
import com.hukua.fundradar.data.sharesText
import com.hukua.fundradar.data.signedAmountText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 持仓记账：份额、摊薄成本、已实现盈亏、今日盈亏。 */
class HoldingTest {

    private fun buy(shares: Double, nav: Double, id: Long = 1L) =
        Trade(id = id, kind = TradeKind.BUY, shares = shares, nav = nav, at = id)

    private fun sell(shares: Double, nav: Double, id: Long = 2L) =
        Trade(id = id, kind = TradeKind.SELL, shares = shares, nav = nav, at = id)

    @Test
    fun buyAddsSharesAndCost() {
        val holding = Holding().applied(buy(1000.0, 2.0))
        assertEquals(1000.0, holding.shares, 1e-9)
        assertEquals(2000.0, holding.cost, 1e-9)
        assertEquals(2.0, holding.avgCost, 1e-9)
        assertEquals(1, holding.trades.size)

        val second = holding.applied(buy(500.0, 2.5, id = 3L))
        assertEquals(1500.0, second.shares, 1e-9)
        assertEquals(3250.0, second.cost, 1e-9)
        assertEquals(2.1666666, second.avgCost, 1e-6)
        assertEquals(2, second.trades.size)
    }

    @Test
    fun sellKeepsAverageCostAndRealisesProfit() {
        val holding = Holding().applied(buy(1000.0, 2.0)).applied(sell(400.0, 2.5))
        assertEquals(600.0, holding.shares, 1e-9)
        // 卖出部分按均价 2.0 扣成本，剩下的成本是 1200。
        assertEquals(1200.0, holding.cost, 1e-9)
        assertEquals(2.0, holding.avgCost, 1e-9)
        // (2.5 - 2.0) * 400 = 200
        assertEquals(200.0, holding.realized, 1e-9)
    }

    @Test
    fun sellingEverythingClearsThePosition() {
        val holding = Holding().applied(buy(1000.0, 2.0)).applied(sell(1000.0, 1.8))
        assertTrue(holding.isEmpty)
        assertEquals(0.0, holding.shares, 1e-9)
        assertEquals(0.0, holding.cost, 1e-9)
        assertEquals(-200.0, holding.realized, 1e-9)
    }

    @Test
    fun sellingMoreThanHeldIsClamped() {
        val holding = Holding().applied(buy(1000.0, 2.0)).applied(sell(1500.0, 2.2))
        assertEquals(0.0, holding.shares, 1e-9)
        assertEquals(0.0, holding.cost, 1e-9)
        assertEquals(200.0, holding.realized, 1e-9)
    }

    @Test
    fun todayProfitUsesIntradayPercent() {
        val holding = Holding(shares = 1000.0, cost = 2000.0)
        // 市值 2100，涨 5% → 昨天是 2000，今日赚 100。
        assertEquals(100.0, requireNotNull(holding.todayProfit(2.1, 5.0)), 1e-6)
        assertEquals(-100.0, requireNotNull(holding.todayProfit(1.9, -5.0)), 1e-6)
        assertNull(Holding().todayProfit(1.0, 1.0))
    }

    @Test
    fun profitAndPercentFollowNav() {
        val holding = Holding(shares = 1000.0, cost = 2000.0)
        assertEquals(100.0, holding.profit(2.1), 1e-9)
        assertEquals(5.0, requireNotNull(holding.profitPercent(2.1)), 1e-9)
        assertNull(Holding().profitPercent(1.0))
    }

    @Test
    fun baseEditKeepsRealisedAndTrades() {
        val holding = Holding().applied(buy(1000.0, 2.0)).applied(sell(500.0, 2.2))
        val edited = holding.withBase(800.0, 1500.0)
        assertEquals(800.0, edited.shares, 1e-9)
        assertEquals(1500.0, edited.cost, 1e-9)
        assertEquals(holding.realized, edited.realized, 1e-9)
        assertEquals(2, edited.trades.size)
    }

    @Test
    fun jsonRoundTripKeepsEverything() {
        val holding = Holding().applied(buy(1234.5678, 2.1532)).applied(sell(234.5678, 2.2))
        val restored = Holding.fromJson(holding.toJson())
        assertEquals(holding.shares, restored.shares, 1e-9)
        assertEquals(holding.cost, restored.cost, 1e-6)
        assertEquals(holding.realized, restored.realized, 1e-9)
        assertEquals(2, restored.trades.size)
        assertEquals(TradeKind.SELL, restored.trades.first().kind)
        assertEquals(2.2, restored.trades.first().nav, 1e-9)
    }

    @Test
    fun emptyHoldingIsDetected() {
        assertTrue(Holding().isEmpty)
        assertTrue(Holding().applied(buy(1.0, 1.0)).applied(sell(1.0, 1.0)).isEmpty)
        assertFalse(Holding(shares = 1.0, cost = 1.0).isEmpty)
    }

    @Test
    fun numbersFormatWithSeparators() {
        assertEquals("12,345.68", amountText(12345.678))
        assertEquals("+1,234.50", signedAmountText(1234.5))
        assertEquals("-234.50", signedAmountText(-234.5))
        assertEquals("0.00", signedAmountText(0.0))
        assertEquals("1,234.5678", sharesText(1234.5678))
    }
}
