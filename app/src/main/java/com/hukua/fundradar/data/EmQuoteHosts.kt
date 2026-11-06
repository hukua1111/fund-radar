package com.hukua.fundradar.data

/**
 * 东财行情接口的主机选择。
 *
 * `push2.eastmoney.com` 这类不带编号的主机在部分网络下会被直接掐掉连接（表现
 * 为空回复），而 `45.push2` / `99.push2` 这些分片主机往往还能用。这里按顺序
 * 试，记住第一次成功的那台，后续请求先走它，失败再换。
 */
internal object EmQuoteHosts {

    private val HOSTS = listOf(
        "45.push2.eastmoney.com",
        "99.push2.eastmoney.com",
        "23.push2.eastmoney.com",
        "82.push2.eastmoney.com",
        "7.push2.eastmoney.com",
        "push2.eastmoney.com"
    )

    @Volatile
    private var preferred: String? = null

    /**
     * GET [path]（以 `/api/...` 开头），返回第一个看起来像 JSON 的响应。
     * 全部失败时返回 null。
     */
    suspend fun get(
        path: String,
        referer: String = "https://quote.eastmoney.com/",
        readTimeoutMs: Int = 10_000
    ): String? {
        val order = buildList {
            preferred?.let { add(it) }
            addAll(HOSTS.filter { it != preferred })
        }
        for (host in order) {
            for (scheme in listOf("https", "http")) {
                val body = runCatching {
                    Net.get("$scheme://$host$path", referer = referer, readTimeoutMs = readTimeoutMs)
                }.getOrNull()
                val cleaned = body?.trimStart('\uFEFF', '\r', '\n', ' ')
                if (!cleaned.isNullOrBlank() && cleaned.startsWith("{")) {
                    preferred = host
                    return cleaned
                }
            }
        }
        return null
    }
}
