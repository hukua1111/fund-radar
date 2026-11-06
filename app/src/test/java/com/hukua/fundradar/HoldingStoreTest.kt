package com.hukua.fundradar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hukua.fundradar.data.Holding
import com.hukua.fundradar.data.HoldingStore
import com.hukua.fundradar.data.Trade
import com.hukua.fundradar.data.TradeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 持仓的本地读写：覆盖安装后还在，删基金时清掉。 */
@RunWith(RobolectricTestRunner::class)
class HoldingStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clean() {
        val store = HoldingStore(context)
        store.remove("900101")
        store.remove("900102")
    }

    @Test
    fun saveAndReadBack() {
        val store = HoldingStore(context)
        store.save("900101", Holding(shares = 1200.0, cost = 2400.0))
        val holding = store.get("900101")
        assertEquals(1200.0, holding?.shares ?: 0.0, 1e-9)
        assertEquals(2.0, holding?.avgCost ?: 0.0, 1e-9)
    }

    @Test
    fun tradeUpdatesStoredPosition() {
        val store = HoldingStore(context)
        val trade = Trade(
            id = 100L,
            kind = TradeKind.BUY,
            shares = 500.0,
            nav = 2.0,
            at = 100L
        )
        val holding = store.apply("900102", trade)
        assertEquals(500.0, holding.shares, 1e-9)
        assertEquals(1000.0, holding.cost, 1e-9)
        assertEquals(500.0, HoldingStore(context).get("900102")?.shares ?: 0.0, 1e-9)
    }

    @Test
    fun removingFundDropsItsHolding() {
        val store = HoldingStore(context)
        store.save("900101", Holding(shares = 1.0, cost = 1.0))
        store.remove("900101")
        assertNull(store.get("900101"))
        assertTrue(store.all().keys.none { it == "900101" })
    }
}
