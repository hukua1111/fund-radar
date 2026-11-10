package com.hukua.fundradar

import com.hukua.fundradar.data.IndexEntry
import com.hukua.fundradar.data.IndexQuoteApi
import com.hukua.fundradar.data.IndexSource
import com.hukua.fundradar.data.KlineSource
import com.hukua.fundradar.data.SinaKlineApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主备数据源的解析与映射：备用源（腾讯 / 新浪）只认交易所代码，
 * 中证自编的 930/931 必须留给东财，这一条不能写错。
 */
class SourceFallbackTest {

    @Test
    fun tencentSymbolOnlyForExchangeIndices() {
        assertEquals("sh000300", IndexQuoteApi.tencentSymbol(IndexEntry("000300", 1, "沪深300")))
        assertEquals("sz399006", IndexQuoteApi.tencentSymbol(IndexEntry("399006", 0, "创业板指")))
        // 中证指数公司自己的代码：腾讯、新浪都没有，只能走东财。
        assertNull(IndexQuoteApi.tencentSymbol(IndexEntry("931994", 2, "电网设备主题")))
        assertNull(IndexQuoteApi.tencentSymbol(IndexEntry("930994", 2, "中证某某")))
    }

    @Test
    fun sourceLabels() {
        assertEquals("东方财富", IndexSource.EASTMONEY.label)
        assertEquals("腾讯", IndexSource.TENCENT.label)
    }

    @Test
    fun sinaKlineParsesJsonp() {
        val body = """
            /*<script>location.href='//sina.com';</script>*/
            var _k=([{"day":"2026-11-05","open":"2493.729","high":"2625.985","low":"2493.667","close":"2566.803","volume":"22532155659"},
            {"day":"2026-11-06","open":"2438.715","high":"2506.967","low":"2438.715","close":"2465.232","volume":"18168065554"}]);
        """.trimIndent()

        val series = SinaKlineApi.parse(body, "0.399006")
        requireNotNull(series)
        assertEquals(KlineSource.EXCHANGE, series.source)
        assertEquals("399006", series.code)
        assertEquals(2, series.candles.size)

        val first = series.candles.first()
        assertEquals("2026-11-05", first.date)
        assertEquals(2493.729, first.open, 0.0001)
        assertEquals(2566.803, first.close, 0.0001)
        assertEquals(2625.985, first.high, 0.0001)
        assertEquals(2493.667, first.low, 0.0001)
        // 新浪给股数，存成一体的「手」
        assertEquals(225321556.59, first.volume, 0.01)
    }

    @Test
    fun sinaKlineHandlesEmptyReply() {
        // 中证自编代码在新浪是 (null)，调用方按「备用也拿不到」处理。
        assertNull(SinaKlineApi.parse("var _k=(null);", "2.931994"))
        assertNull(SinaKlineApi.parse("", "2.931994"))
        assertNull(SinaKlineApi.parse("var _k=([]);", "2.931994"))
    }

    @Test
    fun eastMoneyParseKeepsMarketPrefix() {
        val body = """{"rc":0,"data":{"total":2,"diff":[
            {"f2":4058.95,"f3":0.07,"f12":"000300","f13":1,"f14":"沪深300","f124":1794284532},
            {"f2":2402.95,"f3":0.51,"f12":"931994","f13":2,"f14":"电网设备主题","f124":1794284527}
        ]}}"""
        val quotes = IndexQuoteApi.parse(body)
        assertEquals(2, quotes.size)
        val hs300 = quotes.getValue("1.000300")
        assertEquals(4058.95, hs300.price, 0.001)
        assertEquals(0.07, hs300.changePercent, 0.001)
        assertTrue(quotes.containsKey("2.931994"))
    }
}
