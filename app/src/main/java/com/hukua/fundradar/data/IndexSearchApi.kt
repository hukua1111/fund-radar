package com.hukua.fundradar.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Index lookup used by the "添加指数" sheets.
 *
 * ## 为什么主源是东财
 * 中证指数公司自编的 `930xxx` / `931xxx` / `932xxx`、`H30xxx` / `H50xxx`，
 * 以及港股指数（`HSTECH` / `HSI`），腾讯 smartbox **完全搜不到** —— 直接返回
 * `v_hint="N"`，输入代码也一样。东财 suggest 能逐条命中，而且 `QuoteID`
 * 自带市场号（`2.930697` / `124.HSTECH`），不用按代码前缀猜市场。
 *
 * 腾讯只当备源：中文俗名的召回偶尔比东财好，但它只认沪深交易所挂牌的
 * `000xxx` / `399xxx`，中证自编段一律为空。
 *
 * ## 代码直输兜底
 * 两个接口都抖的时候，`930697` / `H30533` 这类代码仍然要能加进来 ——
 * [guessByCode] 按已知号段给出市场号，名称先放代码，K 线拉回来后
 * 再用真实名称回填（见 `DcaViewModel`）。
 */
object IndexSearchApi {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private const val SUGGEST_URL = "https://smartbox.gtimg.cn/s3/"

    /** 一次给多少条，够填满弹层又不至于翻半天。 */
    private const val SEARCH_LIMIT = 15

    /** Index rows: 东财为主，腾讯补中文俗名，最后按相关度排。 */
    suspend fun search(query: String): List<IndexEntry> = withContext(Dispatchers.IO) {
        val keyword = query.trim()
        if (keyword.isEmpty()) return@withContext emptyList()

        // 东财：唯一能搜到中证自编段（930/931/H30）与港股指数的接口。
        // 代码里带字母时（H30533、HSTECH）补一次全大写 —— 接口对大小写敏感，
        // 用户手敲基本都是小写。
        var eastMoney = emptyList<IndexEntry>()
        for (term in codeTerms(keyword)) {
            eastMoney = runCatching { EastMoneyApi.searchIndices(term, SEARCH_LIMIT) }
                .getOrDefault(emptyList())
            if (eastMoney.isNotEmpty()) break
        }

        // 腾讯：只在东财没喂饱时补一次，省一个请求。
        val tencent = if (eastMoney.size < SEARCH_LIMIT) {
            runCatching { searchTencent(keyword) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val merged = merge(eastMoney, tencent)
        if (merged.isEmpty()) {
            // 两家都没结果：如果输入本身像个指数代码，就直接放一行出来。
            guessByCode(keyword)?.let { return@withContext listOf(it) }
        }
        rank(keyword, merged)
    }

    /**
     * 要拿去哪几个写法去问东财：原样一次，含字母时再补一次全大写。
     * `h30533` 在接口里搜不到，`H30533` 才有。
     */
    internal fun codeTerms(keyword: String): List<String> {
        val term = keyword.trim()
        if (term.isEmpty()) return emptyList()
        val upper = term.uppercase()
        return if (upper != term && term.any { it.isLetter() }) listOf(term, upper) else listOf(term)
    }

    /** 腾讯 smartbox 的回复 → 可报价的指数；中证自编段这里永远是空的。 */
    internal fun parse(raw: String): List<IndexEntry> {
        val hint = raw.substringAfter("v_hint=\"", "").substringBeforeLast("\"", "")
        if (hint.isBlank() || hint == "N") return emptyList()

        val seenNames = HashSet<String>()
        val result = ArrayList<IndexEntry>()
        hint.split('^').forEach { row ->
            if (row.isBlank()) return@forEach
            val parts = row.split('~')
            if (parts.size < 5) return@forEach
            // market ~ code ~ name ~ pinyin ~ type
            val type = parts[4].trim().uppercase()
            if (type != "ZS" && type != "ZS-A") return@forEach

            val code = parts[1].trim()
            val name = decodeEscapes(parts[2]).trim()
            if (code.isBlank() || name.isBlank()) return@forEach

            val market = when (parts[0].trim().lowercase()) {
                "sh" -> 1
                "sz" -> 0
                "bj" -> 0
                // hk / us 行这里不收：腾讯的港股指数代码（HSI、HSTECH）与东财的
                // 市场号对不齐，收进来会出现同一指数两条记录。东财能搜到它们。
                else -> return@forEach
            }
            if (!seenNames.add(name)) return@forEach
            result += IndexEntry(code, market, name)
        }
        return result
    }

    /**
     * 合并两个来源：东财在前，腾讯补缺，按 secid 去重。
     *
     * Visible for tests —— 这个顺序就是弹层里的显示顺序。
     */
    internal fun merge(primary: List<IndexEntry>, fallback: List<IndexEntry>): List<IndexEntry> {
        val seen = HashSet<String>(primary.size + fallback.size)
        val out = ArrayList<IndexEntry>(primary.size + fallback.size)
        (primary + fallback).forEach { entry ->
            if (entry.code.isBlank() || entry.name.isBlank()) return@forEach
            if (seen.add(entry.secid)) out += entry
        }
        return out
    }

    /**
     * 代码 → 市场号，只在两家搜索都没结果时兜底。
     *
     * 规则集中在 [indexMarketOf]：中证自编 `930`–`934` 与 `H` + 数字走 2，
     * 恒生系列走 124，沪市 `000` / `999` 走 1，深市 `399` 与国证 `980` 走 0。
     * 猜不出来返回 null，不瞎给市场号。
     */
    internal fun guessByCode(raw: String): IndexEntry? {
        val code = raw.trim().uppercase()
        if (code.isEmpty() || code.contains(' ')) return null
        val market = indexMarketOf(code) ?: return null
        return IndexEntry(code, market, code)
    }

    /**
     * 相关度：名字与输入完全一致的最前，然后「输入 + 指数」，再是前缀、包含。
     * `sortedWith` 是稳定排序，同分时保留搜索接口自己的顺序。
     */
    internal fun rank(keyword: String, list: List<IndexEntry>): List<IndexEntry> {
        val key = keyword.replace(" ", "").replace("　", "").replace("\t", "")
        if (key.isEmpty()) return list
        return list.sortedWith(
            compareByDescending { entry ->
                val name = entry.name
                when {
                    name == key -> 100
                    name == key + "指数" -> 90
                    name.startsWith(key) -> 70
                    name.contains(key) -> 50
                    else -> 0
                }
            }
        )
    }

    // ------------------------------------------------------------- 腾讯备源

    private fun searchTencent(keyword: String): List<IndexEntry> {
        val body = httpGet(
            SUGGEST_URL + "?q=" + URLEncoder.encode(keyword, "UTF-8") + "&t=all"
        )
        return parse(body)
    }

    private fun decodeEscapes(text: String): String {
        if (!text.contains("\\u")) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == '\\' && i + 5 < text.length && (text[i + 1] == 'u' || text[i + 1] == 'U')) {
                val code = text.substring(i + 2, i + 6).toIntOrNull(16)
                if (code != null) {
                    out.append(code.toChar())
                    i += 6
                    continue
                }
            }
            out.append(text[i])
            i++
        }
        return out.toString()
    }

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", "https://gu.qq.com/")
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Connection", "close")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("搜索服务返回 HTTP $status")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
