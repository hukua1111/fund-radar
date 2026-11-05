package com.hukua.fundradar

import com.hukua.fundradar.data.ChangeKind
import com.hukua.fundradar.data.EastMoneyApi
import com.hukua.fundradar.data.Fund
import com.hukua.fundradar.data.FundDetail
import com.hukua.fundradar.data.LimitType
import com.hukua.fundradar.data.limitChangeOf
import com.hukua.fundradar.data.money
import com.hukua.fundradar.data.navText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FundModelTest {

    @Test
    fun moneyFormatting() {
        assertEquals("10元", money(10.0))
        assertEquals("1000元", money(1000.0))
        assertEquals("1.5万", money(15_000.0))
        assertEquals("30万", money(300_000.0))
        assertEquals("50万", money(500_000.0))
        assertEquals("50万", money(499_999.0))
        assertEquals("2亿", money(200_000_000.0))
    }

    @Test
    fun navFormatting() {
        assertEquals("0.5676", navText(0.5676))
        assertEquals("1.2345", navText(1.2345))
    }

    @Test
    fun limitClassification() {
        assertEquals(LimitType.LIMITED, fund(buyStatus = "限大额").limitType)
        assertEquals(LimitType.SUSPENDED, fund(buyStatus = "暂停申购").limitType)
        assertEquals(LimitType.OPEN, fund(buyStatus = "开放申购").limitType)
        assertEquals(LimitType.UNKNOWN, fund(buyStatus = "").limitType)
    }

    @Test
    fun badgeText() {
        assertEquals("限大额 50万", fund(buyStatus = "限大额", maxBuy = 500_000.0).limitBadgeText)
        assertEquals("限大额", fund(buyStatus = "限大额", maxBuy = null).limitBadgeText)
        assertEquals("暂停申购", fund(buyStatus = "暂停申购").limitBadgeText)
        assertEquals("不限购", fund(buyStatus = "开放申购", maxBuy = 100_000_000_000.0).limitBadgeText)
    }

    @Test
    fun subtitleSkipsBlankFields() {
        assertEquals("161725  ·  指数型-股票", fund(type = "指数型-股票").subtitle)
        assertEquals("161725", fund(type = "").subtitle)
    }

    @Test
    fun detailBecomesCard() {
        val detail = FundDetail(
            name = "华夏成长混合",
            nav = 0.9190,
            dayGrowth = -5.06,
            navDate = "10-30",
            type = "混合型-灵活",
            buyStatus = "暂停申购",
            sellStatus = "开放赎回",
            minBuy = 10.0,
            rate = "0.15%",
            sourceRate = "1.50%",
            riskLevel = 4
        )
        val fund = detail.toFund("000001")
        assertEquals("华夏成长混合", fund.name)
        assertEquals(LimitType.SUSPENDED, fund.limitType)
        assertEquals("暂停申购", fund.limitBadgeText)
        assertEquals("000001  ·  混合型-灵活", fund.subtitle)
        assertEquals(10.0, fund.minBuy!!, 0.01)
        assertEquals(-5.06, fund.dayGrowth, 0.001)
    }

    @Test
    fun limitChangeDetection() {
        fun page(status: String, cap: Double?) =
            FundDetail(name = "测试基金", buyStatus = status, maxBuy = cap)

        val suspended = limitChangeOf("000001", page("限大额", 500_000.0), page("暂停申购", null))!!
        assertEquals(ChangeKind.SUSPENDED, suspended.kind)
        assertEquals("限大额 50万", suspended.oldLabel)
        assertEquals("暂停申购", suspended.newLabel)
        assertTrue(suspended.isNegative)

        assertEquals(
            ChangeKind.RESUMED,
            limitChangeOf("000001", page("暂停申购", null), page("限大额", 100_000.0))!!.kind
        )
        assertEquals(
            ChangeKind.TIGHTENED,
            limitChangeOf("000001", page("限大额", 500_000.0), page("限大额", 300_000.0))!!.kind
        )
        assertEquals(
            ChangeKind.RELAXED,
            limitChangeOf("000001", page("限大额", 300_000.0), page("限大额", 500_000.0))!!.kind
        )
        assertEquals(
            ChangeKind.TIGHTENED,
            limitChangeOf("000001", page("开放申购", null), page("限大额", 500_000.0))!!.kind
        )
        assertEquals(
            ChangeKind.RELAXED,
            limitChangeOf("000001", page("限大额", 500_000.0), page("开放申购", null))!!.kind
        )

        assertNull(limitChangeOf("000001", page("限大额", 500_000.0), page("限大额", 500_000.0)))
        assertNull(limitChangeOf("000001", page("", null), page("限大额", 500_000.0)))
    }

    private fun fund(
        buyStatus: String = "开放申购",
        maxBuy: Double? = null,
        type: String = ""
    ) = Fund(
        code = "161725",
        name = "招商中证白酒指数(LOF)A",
        type = type,
        buyStatus = buyStatus,
        maxBuy = maxBuy
    )
}

/** Offline checks for the per-fund page parser, using the real page markup. */
class FundPageParserTest {

    private val limitedPage = """
        <div class="buyWayStatic">
          <div class="staticItem">
            <span class="itemTit">交易状态：</span>
            <span class="staticCell">限大额 (<span>单日累计购买上限50.00万元</span>)</span>
            <span class="staticCell">开放赎回</span>
          </div>
          <div class="staticItem"><span class="itemTit">购买手续费：</span><span class="staticCell"><span class="comparePrice">1.00%</span>&nbsp;<span class="nowPrice">0.10%</span>&nbsp;<span class="ui-color-red"><b>1.0</b></span>折</span></div>
        </div>
        <input id="moneyAmountTxt" type="text" data-minsg="10.00" data-placeholder="10.00元起购" />
        <table><tr><td>类型：<a href="http://fund.eastmoney.com/ZS_jzzzl.html">指数型-股票</a>&nbsp;&nbsp;|&nbsp;&nbsp;中高风险</td></tr>
        <tr><td><a href="http://fundf10.eastmoney.com/gmbd_161725.html">规模</a>：209.47亿元（2026-09-30）</td>
        <td>基金经理：<a href="http://fundf10.eastmoney.com/jjjl_161725.html">侯昊</a></td></tr>
        <tr><td><span class="letterSpace01">成 立 日</span>：2015-05-27</td>
        <td><span class="letterSpace01">管 理 人</span>：<a href="http://fund.eastmoney.com/company/80000222.html">招商基金管理有限公司</a></td></tr>
        </table>
    """.trimIndent()

    @Test
    fun parsesLimitedFund() {
        val detail = EastMoneyApi.parseFundPage(limitedPage)
        assertEquals("限大额", detail.buyStatus)
        assertEquals("开放赎回", detail.sellStatus)
        assertEquals(500_000.0, detail.maxBuy!!, 0.01)
        assertEquals(10.0, detail.minBuy!!, 0.01)
        assertEquals("指数型-股票", detail.type)
        assertEquals(4, detail.riskLevel)
        assertEquals("1.00%", detail.sourceRate)
        assertEquals("0.10%", detail.rate)
        assertEquals("招商基金管理有限公司", detail.company)
        assertEquals("侯昊", detail.manager)
        assertEquals("2015-05-27", detail.established)
        assertEquals("209.47亿元", detail.scale)
    }

    @Test
    fun parsesSuspendedFund() {
        val page = """
            <span class="itemTit">交易状态：</span><span class="staticCell">暂停申购</span><span class="staticCell">开放赎回</span></div>
            <input data-minsg="100.00" />
            <td>类型：<a href="#">混合型-偏股</a>&nbsp;&nbsp;|&nbsp;&nbsp;中风险</td>
        """.trimIndent()
        val detail = EastMoneyApi.parseFundPage(page)
        assertEquals("暂停申购", detail.buyStatus)
        assertEquals(null, detail.maxBuy)
        assertEquals(100.0, detail.minBuy!!, 0.01)
        assertEquals("混合型-偏股", detail.type)
        assertEquals(3, detail.riskLevel)
    }

    @Test
    fun mergesIntoFund() {
        val fund = Fund(code = "161725", name = "招商中证白酒指数(LOF)A", buyStatus = "限大额")
        val merged = fund.merge(EastMoneyApi.parseFundPage(limitedPage))
        assertEquals("限大额 50万", merged.limitBadgeText)
        assertEquals("10元", money(merged.minBuy!!))
        assertTrue(merged.subtitle.contains("指数型-股票"))
    }
}
