package com.hukua.fundradar

import com.hukua.fundradar.data.EastMoneyApi
import com.hukua.fundradar.data.LimitType
import com.hukua.fundradar.data.money
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Optional integration check against the live 天天基金 pages.
 * Ignored by default because it needs network access; remove the @Ignore
 * annotation to run it manually.
 */
@Ignore("Requires network access to fund.eastmoney.com")
class ApiSmokeTest {

    @Test
    fun listParses() = runBlocking {
        val rows = EastMoneyApi.fetchList(pageSize = 10)
        assertTrue("list should not be empty", rows.isNotEmpty())
        println("list size = ${rows.size}")
        rows.take(5).forEach {
            println("  ${it.code} ${it.name} nav=${it.nav} growth=${it.dayGrowth}% status=${it.buyStatus} -> ${it.limitType}")
        }
    }

    @Test
    fun fundPageParses() = runBlocking {
        val detail = EastMoneyApi.fetchFundDetail("161725")
        assertTrue("detail should parse", detail != null)
        println(
            "161725 type=${detail!!.type} status=${detail.buyStatus} " +
                "cap=${detail.maxBuy?.let { money(it) }} min=${detail.minBuy} " +
                "rate=${detail.rate}/${detail.sourceRate} risk=${detail.riskLevel} " +
                "company=${detail.company} manager=${detail.manager} scale=${detail.scale}"
        )
        assertTrue(detail.buyStatus.isNotBlank())
    }

    @Test
    fun limitClassification() = runBlocking {
        val rows = EastMoneyApi.fetchList(pageSize = 20)
        rows.forEach {
            println("${it.code} ${it.limitType} ${it.limitBadgeText}")
            assertTrue(it.limitType != LimitType.UNKNOWN)
        }
    }
}
