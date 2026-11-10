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
     *
     * [maxHosts] 限制这一轮试几台：目录下载可以慢慢轮完，实时行情不行 ——
     * 每台最多 1.5 秒，试满 6 台就把一次 5 秒的刷新拖过去了。
     */
    suspend fun get(
        path: String,
        referer: String = "https://quote.eastmoney.com/",
        readTimeoutMs: Int = 10_000,
        maxHosts: Int = Int.MAX_VALUE,
        /** 实时行情只走 https：明文那一路在 Android 9+ 默认就不通。 */
        allowHttp: Boolean = true
    ): String? {
        val order = buildList {
            preferred?.let { add(it) }
            addAll(HOSTS.filter { it != preferred })
        }.take(maxHosts.coerceAtLeast(1))
        for (host in order) {
            for (scheme in if (allowHttp) listOf("https", "http") else listOf("https")) {
                val body = runCatching {
                    Net.get(
                        "$scheme://$host$path",
                        referer = referer,
                        readTimeoutMs = readTimeoutMs,
                        connectTimeoutMs = readTimeoutMs
                    )
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
