package com.hukua.fundradar

import com.hukua.fundradar.data.AiConfig
import com.hukua.fundradar.data.IndexEntry
import com.hukua.fundradar.data.IndexSearchApi
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
