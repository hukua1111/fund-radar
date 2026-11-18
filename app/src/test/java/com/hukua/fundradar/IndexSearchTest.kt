package com.hukua.fundradar

import com.hukua.fundradar.data.AiConfig
import com.hukua.fundradar.data.EastMoneyApi
import com.hukua.fundradar.data.IndexEntry
import com.hukua.fundradar.data.IndexSearchApi
import com.hukua.fundradar.data.resolveIndex
import com.hukua.fundradar.data.searchCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexSearchTest {

    @Test
    fun parsesSmartboxIndexRows() {
        val body = "v_hint=\"sz~399997~\\u4e2d\\u8bc1\\u767d\\u9152~zzbj~ZS^" +
            "sh~000300~\\u6caa\\u6df1300~hs300~ZS^" +
            "sz~161725~\\u767d\\u9152\\u57fa\\u91d1LOF~bjjjlof~LOF\""

        val result = IndexSearchApi.parse(body)

        assertEquals(2, result.size) // the LOF row is not an index
        assertEquals("399997", result[0].code)
        assertEquals("中证白酒", result[0].name)
        assertEquals(0, result[0].market)
        assertEquals("1.000300", result[1].secid)
        assertEquals("沪深300", result[1].name)
    }

    @Test
    fun handlesEmptyAndUnknownAnswers() {
        assertTrue(IndexSearchApi.parse("v_hint=\"N\"").isEmpty())
        assertTrue(IndexSearchApi.parse("").isEmpty())
        assertTrue(IndexSearchApi.parse("v_hint=\"\"").isEmpty())
    }

    @Test
    fun dropsDuplicateNames() {
        // 沪深300 is listed on both venues, the tab should show it once
        val body = "v_hint=\"sz~399300~\\u6caa\\u6df1300~hs300~ZS^sh~000300~\\u6caa\\u6df1300~hs300~ZS\""
        val result = IndexSearchApi.parse(body)
        assertEquals(1, result.size)
        assertEquals("399300", result[0].code)
    }

    @Test
    fun offlineCatalogueSearchFindsByCodeAndName() {
        val catalog = listOf(
            IndexEntry("399997", 0, "中证白酒"),
            IndexEntry("000300", 1, "沪深300"),
            IndexEntry("399300", 0, "沪深300"),
            IndexEntry("399990", 0, "煤炭等权")
        )

        assertEquals("399997", searchCatalog("399997", catalog).first().code)
        assertEquals("中证白酒", searchCatalog("白酒", catalog).first().name)
        assertEquals(2, searchCatalog("沪深300", catalog).size)
        assertTrue(searchCatalog("新能源", catalog).isEmpty())
    }

    @Test
    fun parsesEastMoneySuggestKeepingOnlyIndices() {
        // Real reply for 电网设备: a 板块, the CSI index, then an ETF.
        val body = """
            {"QuotationCodeTable":{"Data":[
              {"Code":"BK0457","Name":"电网设备","SecurityTypeName":"板块","SecurityType":"9","QuoteID":"90.BK0457"},
              {"Code":"931994","Name":"电网设备主题","SecurityTypeName":"指数","SecurityType":"11","QuoteID":"2.931994"},
              {"Code":"159326","Name":"电网设备ETF华夏","SecurityTypeName":"基金","SecurityType":"8","QuoteID":"0.159326"}
            ]}}
        """.trimIndent()

        val result = EastMoneyApi.parseIndexSuggest(body)

        assertEquals(1, result.size)
        assertEquals("931994", result[0].code)
        assertEquals(2, result[0].market)
        assertEquals("2.931994", result[0].secid)
        assertEquals("电网设备主题", result[0].name)
    }

    @Test
    fun eastMoneySuggestHandlesGarbage() {
        assertTrue(EastMoneyApi.parseIndexSuggest("").isEmpty())
        assertTrue(EastMoneyApi.parseIndexSuggest("not json").isEmpty())
        assertTrue(EastMoneyApi.parseIndexSuggest("""{"QuotationCodeTable":{"Data":[]}}""").isEmpty())
    }

    @Test
    fun csiIndexNamesResolveAgainstSuggestHits() {
        // 跟踪标的 written by the fund page vs the short name 行情 uses
        val tracked = listOf(
            "中证电网设备主题指数" to "931994",
            "中证卫星产业指数" to "931594",
            "中证半导体材料设备主题指数" to "931743"
        )
        val hits = listOf(
            IndexEntry("931994", 2, "电网设备主题"),
            IndexEntry("931594", 2, "卫星产业"),
            IndexEntry("931743", 2, "半导体材料设备")
        )
        tracked.forEach { (name, code) ->
            assertEquals(name, code, resolveIndex(name, hits)?.code)
        }
    }

    @Test
    fun mergesEastMoneyFirstAndDropsDuplicates() {
        // 东财能给 930/931/H30，腾讯只能给沪深；同一 secid 只留第一条。
        val eastMoney = listOf(
            IndexEntry("930697", 2, "家用电器"),
            IndexEntry("931994", 2, "电网设备主题")
        )
        val tencent = listOf(
            IndexEntry("931994", 2, "电网设备主题"),
            IndexEntry("399997", 0, "中证白酒")
        )

        val merged = IndexSearchApi.merge(eastMoney, tencent)

        assertEquals(3, merged.size)
        assertEquals("2.930697", merged[0].secid)
        assertEquals("2.931994", merged[1].secid)
        assertEquals("0.399997", merged[2].secid)
    }

    @Test
    fun guessByCodeKnowsCsiAndHangSengSegments() {
        // 中证指数公司自编段：930/931/932 与 H + 数字
        assertEquals(2, IndexSearchApi.guessByCode("930697")?.market)
        assertEquals(2, IndexSearchApi.guessByCode("931994")?.market)
        assertEquals(2, IndexSearchApi.guessByCode("932066")?.market)
        assertEquals(2, IndexSearchApi.guessByCode("H30533")?.market)
        assertEquals(2, IndexSearchApi.guessByCode("h30184")?.market)

        // 恒生系列走 124
        assertEquals(124, IndexSearchApi.guessByCode("HSTECH")?.market)
        assertEquals("124.HSI", IndexSearchApi.guessByCode("HSI")?.secid)

        // 交易所挂牌的指数不变
        assertEquals("1.000300", IndexSearchApi.guessByCode("000300")?.secid)
        assertEquals("0.399997", IndexSearchApi.guessByCode("399997")?.secid)

        // 认不出来的不猜
        assertEquals(null, IndexSearchApi.guessByCode("白酒"))
        assertEquals(null, IndexSearchApi.guessByCode("12345"))
    }

    @Test
    fun codeTermsRetriesLowercaseCodesInUpperCase() {
        // 东财 suggest 对大小写敏感：h30533 搜不到，H30533 才行
        assertEquals(listOf("h30533", "H30533"), IndexSearchApi.codeTerms("h30533"))
        assertEquals(listOf("hstech", "HSTECH"), IndexSearchApi.codeTerms("hstech"))
        // 中文与纯数字不需要重试
        assertEquals(listOf("半导体"), IndexSearchApi.codeTerms("半导体"))
        assertEquals(listOf("930697"), IndexSearchApi.codeTerms("930697"))
    }

    @Test
    fun rankPutsExactMatchFirst() {
        val hits = listOf(
            IndexEntry("931743", 2, "半导体材料设备"),
            IndexEntry("932066", 2, "半导体行业精选"),
            IndexEntry("H30184", 2, "半导体")
        )

        val ranked = IndexSearchApi.rank("半导体", hits)

        assertEquals("半导体", ranked.first().name)
        assertEquals(3, ranked.size)
    }

    @Test
    fun endpointAcceptsSloppyInput() {
        assertEquals(
            "http://160.202.238.171:8080/v1/chat/completions",
            AiConfig(baseUrl = "160.202.238.171:8080/v1").endpoint()
        )
        assertEquals(
            "http://192.168.1.9:11434/v1/chat/completions",
            AiConfig(baseUrl = " 192.168.1.9:11434/v1/ ").endpoint()
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            AiConfig(baseUrl = "https://api.deepseek.com/v1/").endpoint()
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            AiConfig(baseUrl = "api.deepseek.com/v1").endpoint()
        )
        // already a full path
        assertEquals(
            "http://10.0.0.2:8080/v1/chat/completions",
            AiConfig(baseUrl = "http://10.0.0.2:8080/v1/chat/completions").endpoint()
        )
        assertEquals("", AiConfig(baseUrl = "   ").endpoint())
    }
}
