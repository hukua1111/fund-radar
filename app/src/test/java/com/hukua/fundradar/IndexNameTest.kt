package com.hukua.fundradar

import com.hukua.fundradar.data.Fund
import com.hukua.fundradar.data.FundDetail
import com.hukua.fundradar.data.IndexEntry
import com.hukua.fundradar.data.normalizeIndexName
import com.hukua.fundradar.data.resolveIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IndexNameTest {

    private val catalog = listOf(
        IndexEntry("399998", 0, "中证煤炭"),
        IndexEntry("399990", 0, "煤炭等权"),
        IndexEntry("399997", 0, "中证白酒"),
        IndexEntry("399967", 0, "中证军工"),
        IndexEntry("980017", 0, "国证半导体芯片"),
        IndexEntry("000300", 1, "沪深300"),
        IndexEntry("399300", 0, "沪深300")
    )

    @Test
    fun normalisationDropsPrefixAndSuffix() {
        assertEquals("煤炭等权", normalizeIndexName("中证煤炭等权指数"))
        assertEquals("白酒", normalizeIndexName("中证白酒指数"))
        assertEquals("300", normalizeIndexName("沪深300指数"))
        assertEquals("半导体芯片", normalizeIndexName("国证半导体芯片指数"))
    }

    @Test
    fun normalisationHandlesCurrencySuffix() {
        assertEquals("创业板指", normalizeIndexName("创业板指（人民币）"))
    }

    @Test
    fun resolvesRealTrackedNames() {
        assertEquals("399990", resolveIndex("中证煤炭等权指数", catalog)?.code)
        assertEquals("399997", resolveIndex("中证白酒指数", catalog)?.code)
        assertEquals("399967", resolveIndex("中证军工指数", catalog)?.code)
        assertEquals("980017", resolveIndex("国证半导体芯片指数", catalog)?.code)
    }

    @Test
    fun prefersTheShenzhenVenueOnTies() {
        val entry = resolveIndex("沪深300指数", catalog)
        assertEquals(0, entry?.market)
        assertEquals("0.399300", entry?.secid)
    }

    @Test
    fun leavesUnknownNamesUnresolved() {
        assertNull(resolveIndex("中证新能源指数", catalog))
        assertNull(resolveIndex("", catalog))
        assertNull(resolveIndex("中证白酒指数", listOf(IndexEntry("399990", 0, "煤炭等权"))))
    }

    @Test
    fun secidUsesMarketPrefix() {
        assertEquals("0.399998", IndexEntry("399998", 0, "中证煤炭").secid)
        assertEquals("1.000300", IndexEntry("000300", 1, "沪深300").secid)
    }

    @Test
    fun trackedIndexReachesTheCardModel() {
        val detail = FundDetail(name = "招商中证煤炭等权指数", trackIndex = "中证煤炭等权指数")
        val fund = detail.toFund("161724")
        assertEquals("中证煤炭等权指数", fund.trackIndex)

        val merged = Fund(code = "161724", name = "旧名字").merge(detail)
        assertEquals("中证煤炭等权指数", merged.trackIndex)

        // A page without 跟踪标的 (主动型) must not wipe an existing value.
        val blank = Fund(code = "161724", name = "旧名字", trackIndex = "中证煤炭等权指数")
        assertEquals("中证煤炭等权指数", blank.merge(FundDetail(name = "旧名字")).trackIndex)
    }
}
