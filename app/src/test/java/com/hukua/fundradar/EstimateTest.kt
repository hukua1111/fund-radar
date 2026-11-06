package com.hukua.fundradar

import com.hukua.fundradar.data.EstimateSource
import com.hukua.fundradar.data.Fund
import com.hukua.fundradar.data.FundPosition
import com.hukua.fundradar.data.HoldingStock
import com.hukua.fundradar.data.PositionApi
import com.hukua.fundradar.data.QuoteApi
import com.hukua.fundradar.data.estimateFund
import com.hukua.fundradar.data.exchangeSymbol
import com.hukua.fundradar.data.weightedChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing and weighting behind the intraday estimate. */
class EstimateTest {

    // Captured verbatim from qt.gtimg.cn (GBK decoded, 88 fields).
    private val quoteLine =
            "v_sh515220=\"1~煤炭ETF国泰~515220~1.172~1.210~1.195~6821082~2110271~4710811~1.172~15904~1.171~" +
            "64086~1.170~49855~1.169~12302~1.168~24854~1.173~4542~1.174~9802~1.175~13288~1.176~11605~1." +
            "177~25564~~20261105161438~-0.038~-3.14~1.198~1.165~1.172/6821082/800164229~6821082~80016~1" +
            "0.13~~~1.198~1.165~2.73~78.88~78.88~0.00~1.331~1.089~1.28~102200~1.173~~~~~~80016.4229~61." +
            "4597~5244~   A~ETF~14.01~-4.64~~~~1.459~1.024~-2.50~-8.37~-0.17~6730676900~6730676900~44.0" +
            "9~9.64~6730676900~0.16~1.1701~-1.60~0.17~1.1701~CNY~0~___D__F__N~1.163~20861~\";"

    @Test
    fun tencentQuoteParses() {
        val quotes = QuoteApi.parseQuotes(quoteLine)
        val quote = quotes["sh515220"]
        assertNotNull(quote)
        requireNotNull(quote)
        assertEquals("煤炭ETF国泰", quote.name)
        assertEquals(1.172, quote.price, 1e-9)
        assertEquals(-3.14, quote.changePercent, 1e-9)
        assertEquals("11-05 16:14", quote.time)
    }

    @Test
    fun tencentQuoteSkipsGarbage() {
        assertTrue(QuoteApi.parseQuotes("""v_sh999999="1~空~999999";""").isEmpty())
        assertTrue(QuoteApi.parseQuotes("").isEmpty())
    }

    @Test
    fun symbolUsesExchangeHint() {
        assertEquals("sh515220", exchangeSymbol("515220"))
        assertEquals("sz159819", exchangeSymbol("159819"))
        assertEquals("sz002028", exchangeSymbol("002028"))
        assertEquals("sh600487", exchangeSymbol("600487"))
        // The feed's own market flag wins over the code prefix.
        assertEquals("sz600487", exchangeSymbol("600487", marketHint = 0))
    }

    @Test
    fun weightedChangeIgnoresUnknownWeights() {
        val change = weightedChange(listOf(60.0 to 2.0, 40.0 to -1.0))
        assertEquals(0.8, requireNotNull(change), 1e-9)
        assertNull(weightedChange(emptyList()))
        assertNull(weightedChange(listOf(0.0 to 5.0)))
    }

    @Test
    fun feederFundFollowsItsEtf() {
        val fund = Fund(code = "008280", name = "国泰中证煤炭ETF联接C", nav = 2.1532)
        val position = FundPosition(etfCode = "515220", etfName = "煤炭ETF国泰")
        val quotes = QuoteApi.parseQuotes(quoteLine)

        val estimate = requireNotNull(estimateFund(fund, position, quotes))
        assertEquals(EstimateSource.ETF, estimate.source)
        assertEquals(-3.14, estimate.percent, 1e-9)
        assertEquals(2.0856, requireNotNull(estimate.nav), 1e-3)
        assertTrue(estimate.basis.contains("515220"))
        assertTrue(estimate.footnote.contains("按目标 ETF 估算"))
    }

    @Test
    fun fundWithoutEtfWeightsItsHoldings() {
        val fund = Fund(code = "025833", name = "天弘电网设备特高压指数C", nav = 1.0)
        val position = FundPosition(
            stocks = listOf(
                HoldingStock("600487", "亨通光电", 60.0, 1),
                HoldingStock("002028", "思源电气", 40.0, 0)
            )
        )
        val quotes = mapOf(
            "sh600487" to quote("sh600487", 2.0),
            "sz002028" to quote("sz002028", -1.0)
        )

        val estimate = requireNotNull(estimateFund(fund, position, quotes))
        assertEquals(EstimateSource.HOLDINGS, estimate.source)
        assertEquals(0.8, estimate.percent, 1e-9)
        assertEquals(100.0, estimate.coverage, 1e-9)
        assertEquals("2 只重仓股", estimate.basis)
    }

    @Test
    fun noQuotesMeansNoEstimate() {
        val fund = Fund(code = "025833", name = "天弘电网设备特高压指数C")
        val position = FundPosition(stocks = listOf(HoldingStock("600487", "亨通光电", 9.0, 1)))
        assertNull(estimateFund(fund, position, emptyMap()))
        assertNull(estimateFund(fund, FundPosition(), emptyMap()))
    }

    @Test
    fun positionParsesEtfAndStocks() {
        val body = """
            {"Datas":{"fundStocks":[{"GPDM":"600487","GPJC":"亨通光电","JZBL":"9.77",
            "NEWTEXCH":"1"}],"fundfofs":[],"ETFCODE":"515220","ETFSHORTNAME":"煤炭ETF国泰"},
            "ErrCode":0,"Success":true}
        """.trimIndent()
        val position = requireNotNull(PositionApi.parse(body))
        assertEquals("515220", position.etfCode)
        assertEquals("煤炭ETF国泰", position.etfName)
        assertEquals(1, position.stocks.size)
        assertEquals(9.77, position.stocks[0].weight, 1e-9)
        assertEquals(1, position.stocks[0].market)
    }

    @Test
    fun positionWithoutHoldingsIsEmpty() {
        val body = """{"Datas":{"fundStocks":[],"fundfofs":[],"ETFCODE":null,"ETFSHORTNAME":null}}"""
        assertNull(PositionApi.parse(body))
    }

    private fun quote(symbol: String, percent: Double) = com.hukua.fundradar.data.RealtimeQuote(
        symbol = symbol,
        name = symbol,
        price = 10.0,
        prevClose = 10.0,
        changePercent = percent,
        time = "11-05 15:00"
    )
}
