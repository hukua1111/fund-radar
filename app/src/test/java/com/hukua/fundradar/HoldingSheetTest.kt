package com.hukua.fundradar

import com.hukua.fundradar.data.Holding
import com.hukua.fundradar.ui.CostInput
import com.hukua.fundradar.ui.HoldingMode
import com.hukua.fundradar.ui.HoldingSheetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「修改持仓」的成本口径。
 *
 * 基金 App 的持仓页上「持有金额」是市值、「持仓成本价」才是成本。008280 这
 * 组真实数字（份额 702.97 / 市值 1,513.64 / 成本价 2.2795）就是回归用例：
 * 按成本价录入应该得到 1,602.42 元成本、约 -88.8 元浮亏。
 */
class HoldingSheetTest {

    private val current = Holding()

    private fun edit(shares: String, cost: String, input: CostInput, nav: String = "2.1532") =
        HoldingSheetState(
            open = true,
            fundCode = "008280",
            mode = HoldingMode.EDIT,
            shares = shares,
            cost = cost,
            costInput = input,
            nav = nav,
            current = current
        )

    @Test
    fun costPriceIsConvertedToTotalCost() {
        val sheet = edit("702.97", "2.2795", CostInput.PRICE)
        assertEquals(702.97 * 2.2795, sheet.costValue, 1e-6)
        assertEquals(2.2795, sheet.costPrice, 1e-9)
        assertEquals(-88.8, sheet.profitPreview!!, 0.1)
        assertTrue(sheet.valid)
        assertFalse(sheet.looksLikeMarketValue)
    }

    @Test
    fun costAmountIsUsedAsIs() {
        val sheet = edit("702.97", "1602.42", CostInput.AMOUNT)
        assertEquals(1602.42, sheet.costValue, 1e-9)
        assertEquals(2.2795, sheet.costPrice, 1e-4)
        assertFalse(sheet.looksLikeMarketValue)
    }

    @Test
    fun marketValueEnteredAsCostIsFlagged() {
        // 1,513.64 是份额 × 净值，也就是「持有金额」——填进成本会被提示。
        val sheet = edit("702.97", "1513.64", CostInput.AMOUNT)
        assertTrue(sheet.looksLikeMarketValue)
    }

    @Test
    fun switchingInputKeepsZeroForEmptyHolding() {
        val sheet = HoldingSheetState(
            open = true,
            mode = HoldingMode.EDIT,
            shares = "",
            cost = "",
            current = Holding()
        )
        assertEquals(0.0, sheet.costValue, 1e-9)
        assertFalse(sheet.looksLikeMarketValue)
        assertTrue(sheet.valid)
    }

    @Test
    fun buyModeIgnoresCostFields() {
        val sheet = HoldingSheetState(
            open = true,
            mode = HoldingMode.BUY,
            amount = "1000",
            nav = "2.1532",
            cost = "999",
            costInput = CostInput.PRICE
        )
        assertEquals(0.0, sheet.costValue, 1e-9)
        assertEquals(1000.0 / 2.1532, sheet.effectiveShares, 1e-6)
        assertEquals(1000.0, sheet.effectiveAmount, 1e-6)
    }
}
