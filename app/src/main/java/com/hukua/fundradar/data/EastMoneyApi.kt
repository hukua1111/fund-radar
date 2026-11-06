package com.hukua.fundradar.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/** Result of checking a secid against the quote endpoint. */
enum class IndexProbeStatus { OK, MISSING, UNREACHABLE }

data class IndexProbe(val status: IndexProbeStatus, val name: String = "")

/**
 * Client for the two public pages used by 天天基金 (eastmoney):
 *
 * 1. `Fund_JJJZ_Data.aspx` – the fund list with the current 申购状态
 *    (开放申购 / 限大额 / 暂停申购), NAV and daily change, all in one request.
 * 2. `fund.eastmoney.com/{code}.html` – the server-rendered fund page, which
 *    carries the exact daily cap ("单日累计购买上限50.00万元"), the minimum
 *    purchase, the fund type, risk level, fees, company, manager and size.
 *
 * The per-fund page is used instead of the JSON mobile API on purpose: that
 * API answers `ErrCode 61136403 网络繁忙` as soon as it is polled a handful of
 * times, while the page is served by the same host as the list and stays
 * available.
 */
object EastMoneyApi {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** Public token baked into the 行情 search box, not a secret of any kind. */
    private const val SEARCH_TOKEN = "D43BF722C8E33BDC906FB84D85E326E8"

    private const val LIST_URL = "https://fund.eastmoney.com/Data/Fund_JJJZ_Data.aspx"
    private const val FUND_PAGE = "https://fund.eastmoney.com/%s.html"

    /**
     * 深市指数 + 沪市指数 + 中证指数公司自有代码（930xxx / 931xxx / H30xxx）。
     *
     * 第三组不能少：`中证卫星产业指数 931594`、`中证电网设备主题指数 931994`、
     * `中证半导体材料设备主题指数 931743` 这类新指数只挂在 market=2 下，
     * 少了它，跟踪这些指数的基金在指数页会直接消失。
     */
    private val CATALOG_FILTERS = listOf("m:0+t:5", "m:1+s:3", "m:2+t:5")

    /** Fund list, sorted by today's change, with purchase status included. */
    suspend fun fetchList(pageSize: Int, keyword: String = ""): List<Fund> {
        val url = buildString {
            append(LIST_URL)
            append("?t=1&lx=1&letter=&gsid=&text=")
            append(URLEncoder.encode(keyword, "UTF-8"))
            append("&sort=zdf,desc&page=1,")
            append(pageSize)
            append("&dt=1&atfc=&onlySale=0&_=")
            append(System.currentTimeMillis())
        }

        val body = httpGet(url)

        // The response is a JS assignment: var db={chars:[...],datas:[[...]],count:[...]...}
        val raw = body.substringAfter("datas:", "").substringBefore(",count:", "")
        if (raw.isBlank()) return emptyList()

        val navDate = Regex("showday:\\[\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1).orEmpty()
        val array = JSONArray(raw)
        val result = ArrayList<Fund>(array.length())

        for (i in 0 until array.length()) {
            val row = array.optJSONArray(i) ?: continue
            val code = row.optString(0)
            if (code.isBlank()) continue
            result += Fund(
                code = code,
                name = row.optString(1),
                nav = row.optString(3).toDoubleOrNull() ?: 0.0,
                accNav = row.optString(4).toDoubleOrNull() ?: 0.0,
                dayGrowth = row.optString(8).toDoubleOrNull() ?: 0.0,
                buyStatus = row.optString(9),
                sellStatus = row.optString(10),
                rate = row.optString(17),
                navDate = navDate
            )
        }
        return result
    }

    /** Everything the fund page knows about one fund. */
    suspend fun fetchFundDetail(code: String): FundDetail? {
        val html = httpGet(FUND_PAGE.format(code))
        if (html.length < 1_000) return null
        return parseFundPage(html, code)
    }

    /** Visible for tests: turns the fund page markup into a [FundDetail]. */
    internal fun parseFundPage(html: String, code: String = ""): FundDetail {
        // <title>招商中证白酒指数(LOF)A(161725)基金净值_估值_行情走势—天天基金网</title>
        val title = Regex("<title>([^<]*)</title>").find(html)?.groupValues?.get(1).orEmpty()
        val name = title.substringBefore("($code)").trim()
            .ifBlank {
                Regex("""class="fix_fname"[^>]*>([^<]*)<""")
                    .find(html)?.groupValues?.get(1)?.trim().orEmpty()
            }

        // <span class="fix_dwjz bold ui-color-red">0.5676</span> ( <span class="fix_zzl …">5.58%</span> )
        val nav = Regex("""fix_dwjz[^>]*>([\d.]+)<""")
            .find(html)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val dayGrowth = Regex("""fix_zzl[^>]*>(-?[\d.]+)%""")
            .find(html)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val navDate = Regex("""fix_date">\(([^)]*)\)""")
            .find(html)?.groupValues?.get(1)?.trim().orEmpty()

        val statusChunk = html.substringAfter("交易状态：", "").substringBefore("</div>", "")
        val statusText = stripTags(statusChunk)

        val buyStatus = when {
            statusText.contains("暂停申购") -> "暂停申购"
            statusText.contains("限大额") -> "限大额"
            statusText.contains("开放申购") -> "开放申购"
            else -> statusText.substringBefore("(").trim()
        }
        val sellStatus = when {
            statusText.contains("暂停赎回") -> "暂停赎回"
            statusText.contains("开放赎回") -> "开放赎回"
            else -> ""
        }
        val maxBuy = Regex("""单日累计购买上限([\d.]+)(亿元|万元|元)""")
            .find(statusChunk)
            ?.let { match ->
                val value = match.groupValues[1].toDoubleOrNull() ?: return@let null
                when (match.groupValues[2]) {
                    "亿元" -> value * 100_000_000
                    "万元" -> value * 10_000
                    else -> value
                }
            }

        // 类型：<a …>指数型-股票</a>&nbsp;&nbsp;|&nbsp;&nbsp;中高风险
        val typeText = stripTags(html.substringAfter("类型：", "").substringBefore("</td>", ""))
        val typeParts = typeText.split("|").map { it.trim() }.filter { it.isNotEmpty() }
        val type = typeParts.firstOrNull().orEmpty()
        val risk = riskLevelOf(typeParts.getOrNull(1).orEmpty())

        val minBuy = Regex("""data-minsg="([\d.]+)"""")
            .find(html)?.groupValues?.get(1)?.toDoubleOrNull()

        // 购买手续费：<span class="comparePrice">1.00%</span> … <span class="nowPrice">0.10%</span>
        val feeChunk = html.substringAfter("购买手续费：", "").substringBefore("</div>", "")
        val fees = Regex("""([\d.]+%)""").findAll(feeChunk).map { it.groupValues[1] }.toList()
        val sourceRate = fees.getOrNull(0).orEmpty()
        val rate = fees.getOrNull(1).orEmpty()

        val company = Regex("""管\s*理\s*人</span>：<a[^>]*>([^<]+)</a>""")
            .find(html)?.groupValues?.get(1)?.trim().orEmpty()
        val manager = Regex("""基金经理：<a[^>]*>([^<]+)</a>""")
            .find(html)?.groupValues?.get(1)?.trim().orEmpty()
        val established = Regex("""成\s*立\s*日</span>：([\d-]+)""")
            .find(html)?.groupValues?.get(1).orEmpty()
        val scale = Regex("""规模</a>：([\d.]+)(亿元|万元)""")
            .find(html)?.let { "${it.groupValues[1]}${it.groupValues[2]}" }.orEmpty()
        // <a ...>跟踪标的：</a>中证煤炭等权指数 | <a ...>年化跟踪误差：</a>
        val trackIndex = Regex("""跟踪标的：</a>([^<|]+)""")
            .find(html)?.groupValues?.get(1)?.trim().orEmpty()

        return FundDetail(
            name = name,
            nav = nav,
            dayGrowth = dayGrowth,
            navDate = navDate,
            type = type,
            buyStatus = buyStatus,
            sellStatus = sellStatus,
            minBuy = minBuy,
            maxBuy = maxBuy,
            rate = rate,
            sourceRate = sourceRate,
            riskLevel = risk,
            company = company,
            manager = manager,
            established = established,
            scale = scale,
            trackIndex = trackIndex
        )
    }

    // ---------------------------------------------------------------
    // K line
    // ---------------------------------------------------------------

    /**
     * Daily candles for a fund.
     *
     * Exchange-traded funds (场内 LOF / ETF) have real OHLC data, so we try the
     * exchange quote endpoint first; pure off-exchange funds only publish a NAV
     * series, which we turn into candles where each body spans
     * previous NAV → today's NAV.
     */
    suspend fun fetchKline(code: String, limit: Int = 120): KlineSeries? {
        for (market in marketPrefixes(code)) {
            val series = runCatching { fetchExchangeKline(code, market, limit) }.getOrNull()
            if (series != null && series.candles.isNotEmpty()) return series
        }
        return runCatching { fetchNavKline(code, limit) }.getOrNull()
    }

    private fun marketPrefixes(code: String): List<String> =
        if (code.startsWith("5") || code.startsWith("6")) listOf("1", "0") else listOf("0", "1")

    private suspend fun fetchExchangeKline(
        code: String,
        market: String,
        limit: Int
    ): KlineSeries? {
        val url = "https://push2his.eastmoney.com/api/qt/stock/kline/get" +
            "?secid=$market.$code" +
            "&fields1=f1,f2,f3,f4,f5,f6" +
            "&fields2=f51,f52,f53,f54,f55,f56,f57,f58" +
            "&klt=101&fqt=1&end=20500101&lmt=$limit&_=${System.currentTimeMillis()}"

        val body = httpGet(url, referer = "https://quote.eastmoney.com/")
        val data = JSONObject(body).optJSONObject("data") ?: return null
        val rows = data.optJSONArray("klines") ?: return null
        if (rows.length() == 0) return null

        val candles = ArrayList<Candle>(rows.length())
        for (i in 0 until rows.length()) {
            // date,open,close,high,low,volume,amount,change%
            val parts = rows.optString(i).split(',')
            if (parts.size < 5) continue
            val open = parts[1].toDoubleOrNull() ?: continue
            val close = parts[2].toDoubleOrNull() ?: continue
            val high = parts[3].toDoubleOrNull() ?: open
            val low = parts[4].toDoubleOrNull() ?: open
            val volume = parts.getOrNull(5)?.toDoubleOrNull() ?: 0.0
            candles += Candle(parts[0], open, close, high, low, volume)
        }
        if (candles.isEmpty()) return null
        return KlineSeries(
            code = code,
            name = data.optString("name"),
            source = KlineSource.EXCHANGE,
            candles = candles
        )
    }

    /** Daily candles for an index, addressed by its eastmoney secid (`0.399998`). */
    suspend fun fetchIndexKline(secid: String, limit: Int = 250): KlineSeries? = runCatching {
        val url = "https://push2his.eastmoney.com/api/qt/stock/kline/get" +
            "?secid=$secid" +
            "&fields1=f1,f2,f3,f4,f5,f6" +
            "&fields2=f51,f52,f53,f54,f55,f56,f57,f58" +
            "&klt=101&fqt=1&end=20500101&lmt=$limit&_=${System.currentTimeMillis()}"
        val body = httpGet(url, referer = "https://quote.eastmoney.com/")
        val data = JSONObject(body).optJSONObject("data") ?: return@runCatching null
        val rows = data.optJSONArray("klines") ?: return@runCatching null
        if (rows.length() == 0) return@runCatching null

        val candles = ArrayList<Candle>(rows.length())
        for (i in 0 until rows.length()) {
            val parts = rows.optString(i).split(',')
            if (parts.size < 5) continue
            val open = parts[1].toDoubleOrNull() ?: continue
            val close = parts[2].toDoubleOrNull() ?: continue
            candles += Candle(
                date = parts[0],
                open = open,
                close = close,
                high = parts[3].toDoubleOrNull() ?: open,
                low = parts[4].toDoubleOrNull() ?: open,
                volume = parts.getOrNull(5)?.toDoubleOrNull() ?: 0.0
            )
        }
        if (candles.isEmpty()) return@runCatching null
        KlineSeries(
            code = data.optString("code"),
            name = data.optString("name"),
            source = KlineSource.EXCHANGE,
            candles = candles
        )
    }.getOrNull()

    /**
     * Checks whether a secid really has index quotes, telling "no such index"
     * apart from "the network is down" — the add-index flow needs both.
     */
    suspend fun probeIndex(secid: String): IndexProbe = withContext(Dispatchers.IO) {
        val url = "https://push2his.eastmoney.com/api/qt/stock/kline/get" +
            "?secid=$secid" +
            "&fields1=f1,f2,f3,f4,f5,f6" +
            "&fields2=f51,f52,f53&klt=101&fqt=1&end=20500101&lmt=2&_=${System.currentTimeMillis()}"
        try {
            val data = JSONObject(httpGet(url, referer = "https://quote.eastmoney.com/"))
                .optJSONObject("data")
            val rows = data?.optJSONArray("klines")
            if (data == null || rows == null || rows.length() == 0) {
                IndexProbe(IndexProbeStatus.MISSING)
            } else {
                IndexProbe(IndexProbeStatus.OK, data.optString("name"))
            }
        } catch (error: Exception) {
            IndexProbe(IndexProbeStatus.UNREACHABLE)
        }
    }

    /**
     * Every quotable index (~1k rows). The search endpoint used by the website
     * rejects non-browser TLS fingerprints, so we download the full list once a
     * week and match names locally instead.
     */
    suspend fun fetchIndexCatalog(): List<IndexEntry> {
        val result = LinkedHashMap<String, IndexEntry>()
        for (fs in CATALOG_FILTERS) {
            var page = 1
            while (page <= 12) {
                val url = "https://push2.eastmoney.com/api/qt/clist/get" +
                    "?pn=$page&pz=100&po=1&np=1&fltt=2&invt=2&fid=f12" +
                    "&fs=$fs&fields=f12,f13,f14&_=${System.currentTimeMillis()}"
                val body = runCatching {
                    httpGet(url, referer = "https://quote.eastmoney.com/")
                }.getOrNull() ?: break
                val diff = runCatching {
                    JSONObject(body).optJSONObject("data")?.optJSONArray("diff")
                }.getOrNull() ?: break
                if (diff.length() == 0) break

                for (i in 0 until diff.length()) {
                    val item = diff.optJSONObject(i) ?: continue
                    val code = item.optString("f12")
                    val name = item.optString("f14")
                    val market = item.optInt("f13", -1)
                    if (code.isBlank() || name.isBlank() || market < 0) continue
                    result["$market.$code"] = IndexEntry(code, market, name)
                }
                if (diff.length() < 100) break
                page++
            }
        }
        return result.values.toList()
    }

    /**
     * Name → index lookup, the same suggest service the 行情 search box uses.
     *
     * This is the part the catalogue cannot do: it answers a *name* with the
     * quotable id, including 中证指数公司's own codes (930xxx / 931xxx) that a
     * fund's 跟踪标的 is written in. Response shape:
     *
     * ```
     * {"QuotationCodeTable":{"Data":[
     *   {"Code":"931994","Name":"电网设备主题","SecurityTypeName":"指数",
     *    "QuoteID":"2.931994",...}]}}
     * ```
     */
    suspend fun searchIndices(keyword: String, limit: Int = 8): List<IndexEntry> {
        val term = keyword.trim()
        if (term.isEmpty()) return emptyList()

        val url = "https://searchapi.eastmoney.com/api/suggest/get" +
            "?input=" + URLEncoder.encode(term, "UTF-8") +
            "&type=14&token=$SEARCH_TOKEN&count=$limit"
        val body = runCatching { httpGet(url, referer = "https://www.eastmoney.com/") }
            .getOrNull() ?: return emptyList()
        return parseIndexSuggest(body)
    }

    /** Visible for tests: turns a suggest reply into quotable indices. */
    internal fun parseIndexSuggest(body: String): List<IndexEntry> {
        val rows = runCatching {
            JSONObject(body).optJSONObject("QuotationCodeTable")?.optJSONArray("Data")
        }.getOrNull() ?: return emptyList()

        val result = LinkedHashMap<String, IndexEntry>()
        for (i in 0 until rows.length()) {
            val item = rows.optJSONObject(i) ?: continue
            // 指数 only: the same reply also carries 板块 / 基金 / 股票 rows.
            val kind = item.optString("SecurityTypeName")
            val securityType = item.optString("SecurityType")
            if (kind != "指数" && securityType != "11" && securityType != "5") continue

            val quoteId = item.optString("QuoteID")
            val code = item.optString("Code").ifBlank { quoteId.substringAfter('.') }
            val name = item.optString("Name")
            val market = quoteId.substringBefore('.').toIntOrNull() ?: continue
            if (code.isBlank() || name.isBlank() || market < 0) continue
            result["$market.$code"] = IndexEntry(code, market, name)
        }
        return result.values.toList()
    }

    private suspend fun fetchNavKline(code: String, limit: Int): KlineSeries? {
        val url = "https://api.fund.eastmoney.com/f10/lsjz" +
            "?fundCode=$code&pageIndex=1&pageSize=$limit&_=${System.currentTimeMillis()}"

        val body = httpGet(url, referer = "https://fundf10.eastmoney.com/")
        val list = JSONObject(body).optJSONObject("Data")?.optJSONArray("LSJZList") ?: return null
        if (list.length() == 0) return null

        // The feed is newest first; the chart wants oldest first.
        val points = ArrayList<Pair<String, Double>>(list.length())
        for (i in list.length() - 1 downTo 0) {
            val item = list.optJSONObject(i) ?: continue
            val nav = item.optString("DWJZ").toDoubleOrNull() ?: continue
            val date = item.optString("FSRQ")
            if (date.isBlank() || nav <= 0) continue
            points += date to nav
        }
        if (points.isEmpty()) return null

        val candles = ArrayList<Candle>(points.size)
        var previous = points.first().second
        points.forEach { (date, nav) ->
            candles += Candle(
                date = date,
                open = previous,
                close = nav,
                high = maxOf(previous, nav),
                low = minOf(previous, nav)
            )
            previous = nav
        }
        return KlineSeries(code = code, name = "", source = KlineSource.NAV, candles = candles)
    }

    // -----------------------------------------------------------

    private fun stripTags(raw: String): String =
        raw.replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun riskLevelOf(text: String): Int = when {
        text.contains("中高") -> 4
        text.contains("中低") -> 2
        text.contains("低") -> 1
        text.contains("高") -> 5
        text.contains("中") -> 3
        else -> 0
    }

    private suspend fun httpGet(
        url: String,
        referer: String = "https://fund.eastmoney.com/",
        readTimeoutMs: Int = 12_000
    ): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = readTimeoutMs
            requestMethod = "GET"
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", referer)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("Connection", "close")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("服务器返回 HTTP $status")
            }
            val stream = connection.inputStream
            val decoded = if (connection.contentEncoding?.contains("gzip", true) == true) {
                GZIPInputStream(stream)
            } else {
                stream
            }
            decoded.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
