package com.hukua.fundradar

import com.hukua.fundradar.data.IndexEntry
import com.hukua.fundradar.data.IndexQuoteApi
import com.hukua.fundradar.data.SinaKlineApi
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertTrue

/**
 * 联网冒烟：验证「东财主用 + 腾讯备用 + 新浪历史兜底」在真实网络下的表现。
 * 默认跳过（要联网，而且行情随时间变），需要手动验收时去掉 @Ignore 跑一次。
 */
@Ignore("Requires network access to push2.eastmoney.com / qt.gtimg.cn")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LiveSourceSmokeTest {

    @Test
    fun liveIndexQuotes() = runBlocking {
        val entries = listOf(
            IndexEntry("000300", 1, "沪深300"),
            IndexEntry("399006", 0, "创业板指"),
            IndexEntry("931994", 2, "电网设备主题")
        )
        repeat(3) { round ->
            val batch = IndexQuoteApi.fetchBatch(entries)
            println("round=$round source=${batch.source} degraded=${batch.degraded} error=${batch.error}")
            batch.quotes.forEach { (secid, q) ->
                println("   $secid ${q.name} ${q.price} ${q.changePercent}% ${q.time}")
            }
            assertTrue(batch.quotes.isNotEmpty())
        }
    }

    @Test
    fun tencentFallbackOnlyCoversExchangeIndices() = runBlocking {
        val entries = listOf(
            IndexEntry("000300", 1, "沪深300"),
            IndexEntry("399006", 0, "创业板指"),
            IndexEntry("931994", 2, "电网设备主题")
        )
        val quotes = IndexQuoteApi.fromTencent(entries)
        println("tencent fallback keys=" + quotes.keys)
        quotes.forEach { (secid, q) -> println("   $secid ${q.name} ${q.price} ${q.changePercent}%") }
        assertTrue(quotes.containsKey("1.000300"))
        assertTrue(quotes.containsKey("0.399006"))
        // 中证自编指数腾讯没有，只能靠东财
        assertTrue(!quotes.containsKey("2.931994"))
    }

    @Test
    fun liveSinaKline() = runBlocking {
        val series = SinaKlineApi.fetchIndexKline("0.399006", 60)
        println("sina bars=" + (series?.candles?.size ?: -1) + " last=" + series?.latest)
        assertTrue((series?.candles?.size ?: 0) > 30)
        val missing = SinaKlineApi.fetchIndexKline("2.931994", 60)
        println("sina 931994 => $missing")
    }
}
