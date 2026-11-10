package com.hukua.fundradar

import com.hukua.fundradar.data.CsIndexApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 中证指数官网 `index-perf` 的解析。
 *
 * 这个源是 1.13.0 的历史段主源，解析错一处就会画出错误的 K 线，而且错得很隐蔽
 * ——成交量单位（股 vs 手）尤其容易差 100 倍。
 */
class CsIndexApiTest {

    private val sample = """
        {"code":"0","msg":"Success","data":[
          {"tradeDate":"20261106","indexCode":"931151","indexNameCn":"光伏产业",
           "open":2153.03,"high":2243.45,"low":2152.66,"close":2189.70,
           "change":36.67,"changePct":1.70,"tradingVol":3546847900.0,
           "tradingValue":500.71,"consNumber":50.0,"peg":33.10},
          {"tradeDate":"20261109","indexCode":"931151","indexNameCn":"光伏产业",
           "open":2220.30,"high":2236.67,"low":2153.84,"close":2154.71,
           "change":-34.99,"changePct":-1.60,"tradingVol":2629497516.0,
           "tradingValue":392.45,"consNumber":50.0,"peg":32.96}
        ]}
    """.trimIndent()

    @Test
    fun parsesOhlcAndMeta() {
        val series = CsIndexApi.parse(sample, "931151", 800)
        requireNotNull(series)
        assertEquals(2, series.candles.size)
        assertEquals("931151", series.code)
        assertEquals("光伏产业", series.name)

        val last = series.candles.last()
        assertEquals("2026-11-09", last.date)
        assertEquals(2220.30, last.open, 1e-6)
        assertEquals(2236.67, last.high, 1e-6)
        assertEquals(2153.84, last.low, 1e-6)
        assertEquals(2154.71, last.close, 1e-6)

        // 官网给股数，除以 100 与东财「手」逐位一致（东财 11-09 是 26294975 手）。
        assertEquals(26_294_975.16, last.volume, 0.01)

        val meta = series.meta
        assertEquals(CsIndexApi.LABEL, meta.historySource)
        assertEquals("2026-11-09", meta.asOf)
        assertEquals(32.96, meta.pe!!, 1e-6)
        assertEquals(50, meta.constituents)
    }

    @Test
    fun keepsAscendingOrderAndRespectsLimit() {
        val series = CsIndexApi.parse(sample, "931151", 1)
        requireNotNull(series)
        assertEquals(1, series.candles.size)
        // 截断要留最后一天，不能留第一天。
        assertEquals("2026-11-09", series.candles.single().date)
    }

    @Test
    fun missingFieldsFallBackToClose() {
        val body = """{"data":[{"tradeDate":"20261109","close":100.0,"open":null,"high":null,"low":null,"tradingVol":null,"peg":null,"consNumber":null}]}"""
        val series = CsIndexApi.parse(body, "000300", 10)
        requireNotNull(series)
        val candle = series.candles.single()
        assertEquals(100.0, candle.open, 1e-6)
        assertEquals(100.0, candle.high, 1e-6)
        assertEquals(100.0, candle.low, 1e-6)
        assertEquals(0.0, candle.volume, 1e-6)
        assertNull(series.meta.pe)
        assertNull(series.meta.constituents)
    }

    @Test
    fun emptyOrBrokenReplyReturnsNull() {
        // 深市 399 段官网返回 Success + 空数组，调用方要能识别成「这家没有」。
        assertNull(CsIndexApi.parse("""{"code":"0","msg":"Success","data":[]}""", "399006", 100))
        assertNull(CsIndexApi.parse("""{"code":"1","msg":"失败"}""", "399006", 100))
        assertNull(CsIndexApi.parse("not json at all", "399006", 100))
        assertNull(CsIndexApi.parse("", "399006", 100))
    }

    @Test
    fun isoDateConversion() {
        assertEquals("2026-11-09", CsIndexApi.isoDate("20261109"))
        assertNull(CsIndexApi.isoDate("2026-11-09"))
        assertNull(CsIndexApi.isoDate(""))
    }

    @Test
    fun onlyShanghaiAndCsiMarkets() {
        // 官网只覆盖沪市（1）与中证（2）；深市（0）必须留给东财。
        assertEquals(true, CsIndexApi.supports(1))
        assertEquals(true, CsIndexApi.supports(2))
        assertEquals(false, CsIndexApi.supports(0))
        assertNotNull(CsIndexApi.parse(sample, "931151", 800))
    }
}
