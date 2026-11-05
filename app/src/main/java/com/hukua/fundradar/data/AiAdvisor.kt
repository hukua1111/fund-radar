package com.hukua.fundradar.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

enum class SwingAction {
    BUY, SELL, HOLD, UNKNOWN;

    val label: String
        get() = when (this) {
            BUY -> "波段买入"
            SELL -> "波段卖出"
            HOLD -> "观望"
            UNKNOWN -> "未判断"
        }

    companion object {
        fun of(raw: String): SwingAction = when (raw.trim().uppercase(Locale.US)) {
            "BUY", "买入", "加仓" -> BUY
            "SELL", "卖出", "减仓" -> SELL
            "HOLD", "观望", "持有", "等待" -> HOLD
            else -> UNKNOWN
        }
    }
}

data class AiVerdict(
    val action: SwingAction,
    val confidence: Int,
    val reason: String,
    val risk: String,
    val raw: String,
    val at: Long = System.currentTimeMillis()
)

/** Input / output of the batched daily judgement. */
data class BatchItem(val code: String, val name: String, val series: KlineSeries)

data class BatchVerdict(
    val code: String,
    val action: SwingAction,
    val confidence: Int,
    val reason: String
)

/**
 * Asks an OpenAI-compatible chat endpoint whether now looks like a good moment
 * to swing-trade this fund. The model only sees numbers we computed locally
 * (NAV/OHLC, MA5/10/20/60, recent changes) so the answer stays grounded.
 */
object AiAdvisor {

    suspend fun judge(
        config: AiConfig,
        fundName: String,
        code: String,
        series: KlineSeries,
        subject: String = "基金"
    ): AiVerdict = withContext(Dispatchers.IO) {
        require(config.configured) { "还没有配置 AI 接口" }
        val latest = series.latest ?: throw IllegalStateException("没有可用的行情数据")

        val maValues = series.maValues()
        val ma20 = maValues[20]
        val vsMa20 = ma20?.takeIf { it > 0 }?.let { (latest.close - it) / it * 100.0 }

        val recent = series.candles.takeLast(10).joinToString("\n") { candle ->
            String.format(
                Locale.US,
                "%s 开%.4f 高%.4f 低%.4f 收%.4f",
                candle.date, candle.open, candle.high, candle.low, candle.close
            )
        }

        val dataBlock = buildString {
            appendLine("$subject：${fundName.ifBlank { code }}（$code）")
            appendLine("数据来源：${if (series.source == KlineSource.EXCHANGE) "场内交易K线" else "场外每日净值"}")
            appendLine("最新日期：${latest.date}")
            appendLine("最新价格/净值：${String.format(Locale.US, "%.4f", latest.close)}")
            appendLine(
                "均线：" + maValues.entries.joinToString("  ") { (period, value) ->
                    "MA$period=" + (value?.let { String.format(Locale.US, "%.4f", it) } ?: "无")
                }
            )
            appendLine("现价相对MA20：" + (vsMa20?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "无"))
            appendLine(
                "区间涨跌：近5日=" + pct(series.changeOver(5)) +
                    "  近20日=" + pct(series.changeOver(20)) +
                    "  近60日=" + pct(series.changeOver(60))
            )
            appendLine("最近10个交易日（日期 开高低收）：")
            append(recent)
        }

        val systemPrompt = "你是一名谨慎的基金波段交易助手。只能依据用户给出的数据判断，" +
            "不要编造消息面，不要给出绝对化的保证。用中文回答，并且只输出一个 JSON 对象。"

        val userPrompt = buildString {
            appendLine(dataBlock)
            appendLine()
            appendLine("请判断当前是否适合「波段买入」「波段卖出」或「观望」。要求：")
            appendLine("1. 必须结合 MA5/MA10/MA20/MA60 的多空排列、价格与 MA20 的偏离程度、近期涨跌幅；")
            appendLine("2. 场外基金申赎有确认延迟，判断应偏保守；")
            appendLine("3. 只输出 JSON，不要 markdown 代码块，不要多余文字。")
            append("""格式：{"action":"BUY|SELL|HOLD","confidence":0-100,"reason":"60字以内中文理由，需引用均线关系","risk":"30字以内风险提示"}""")
        }

        val payload = JSONObject().apply {
            put("model", config.model)
            put("temperature", 0.2)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", userPrompt))
            })
        }

        val response = postJson(config.endpoint(), config.apiKey, payload.toString())
        val content = JSONObject(response)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()
            ?: throw IllegalStateException("AI 返回格式不正确")

        parseVerdict(content)
    }

    /** Tolerates ```json fences and surrounding prose. */
    internal fun parseVerdict(content: String): AiVerdict {
        // The model may wrap the object in prose or a markdown fence; the first
        // "{" to the last "}" is the JSON body in every realistic case.
        val json = content.let { text ->
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start >= 0 && end > start) text.substring(start, end + 1) else text
        }

        val parsed = runCatching { JSONObject(json) }.getOrNull()
            ?: return AiVerdict(SwingAction.UNKNOWN, 0, content, "", content)

        return AiVerdict(
            action = SwingAction.of(parsed.optString("action")),
            confidence = parsed.optInt("confidence", 0).coerceIn(0, 100),
            reason = parsed.optString("reason").ifBlank { parsed.optString("message") },
            risk = parsed.optString("risk"),
            raw = content
        )
    }

    /** One call covering the whole watchlist, used by the daily job. */
    suspend fun judgeBatch(
        config: AiConfig,
        items: List<BatchItem>
    ): List<BatchVerdict> = withContext(Dispatchers.IO) {
        require(config.configured) { "还没有配置 AI 接口" }
        require(items.isNotEmpty()) { "自选列表为空" }

        val blocks = items.joinToString("\n\n") { item ->
            val latest = item.series.latest
            val ma = item.series.maValues()
            buildString {
                appendLine("【${item.name.ifBlank { item.code }}】代码 ${item.code}")
                appendLine("最新 ${latest?.date ?: "-"} 收盘/净值 ${latest?.let { String.format(Locale.US, "%.4f", it.close) } ?: "-"}")
                appendLine(
                    ma.entries.joinToString("  ") { (period, value) ->
                        "MA$period=" + (value?.let { String.format(Locale.US, "%.4f", it) } ?: "无")
                    }
                )
                append(
                    "近5日=${pct(item.series.changeOver(5))}  近20日=${pct(item.series.changeOver(20))}  " +
                        "近60日=${pct(item.series.changeOver(60))}"
                )
            }
        }

        val system = "你是一名谨慎的波段交易助手，只依据给定数据分析，不编造消息面，" +
            "用中文回答，且只输出 JSON 数组。"

        val user = buildString {
            appendLine("下面是我自选基金跟踪的指数的最新均线与涨跌数据，请逐个判断当前是否适合波段买入 / 波段卖出 / 观望。")
            appendLine()
            appendLine(blocks)
            appendLine()
            appendLine("要求：结合 MA5/MA10/MA20/MA60 的多空排列与价格偏离度；指数不能直接买卖，结论用于指导对应基金的申赎时机，判断偏保守。")
            append("""只输出 JSON 数组，不要代码块：""")
            append("""[{"code":"161725","action":"BUY|SELL|HOLD","confidence":0-100,"reason":"40字以内中文理由"}]""")
        }

        val payload = JSONObject().apply {
            put("model", config.model)
            put("temperature", 0.2)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", user))
            })
        }

        val content = JSONObject(postJson(config.endpoint(), config.apiKey, payload.toString()))
            .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?.optString("content")?.trim()
            ?: throw IllegalStateException("AI 返回格式不正确")

        val jsonArray = content.let { text ->
            val start = text.indexOf('[')
            val end = text.lastIndexOf(']')
            if (start >= 0 && end > start) text.substring(start, end + 1) else text
        }

        val array = runCatching { JSONArray(jsonArray) }.getOrNull()
            ?: throw IllegalStateException("AI 没有返回可解析的 JSON")

        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val code = item.optString("code")
            if (code.isBlank()) return@mapNotNull null
            BatchVerdict(
                code = code,
                action = SwingAction.of(item.optString("action")),
                confidence = item.optInt("confidence", 0).coerceIn(0, 100),
                reason = item.optString("reason")
            )
        }
    }

    /** Quick connectivity check used by the settings screen. */
    suspend fun testConnection(config: AiConfig): String = withContext(Dispatchers.IO) {
        require(config.configured) { "请先填写地址、模型和 Key" }
        val payload = JSONObject().apply {
            put("model", config.model)
            put("temperature", 0.0)
            put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", "只回复两个字：可用")
            ))
        }
        val body = postJson(config.endpoint(), config.apiKey, payload.toString())
        JSONObject(body).optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content")?.trim()
            ?: "连接成功，但返回内容为空"
    }

    private fun pct(value: Double?): String =
        value?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "无"

    private fun postJson(url: String, apiKey: String, body: String): String {
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 90_000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            }
        } catch (error: Exception) {
            throw friendlyError(error, url)
        }

        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException(httpMessage(code, errorDetail(text)))
            }
            return text
        } catch (error: Exception) {
            if (error is IllegalStateException && error.message?.startsWith("AI 接口") == true) throw error
            throw friendlyError(error, url)
        } finally {
            connection.disconnect()
        }
    }

    /** Pulls the human readable bit out of an OpenAI style error body. */
    private fun errorDetail(body: String): String {
        if (body.isBlank()) return ""
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return body.take(120)
        return json.optJSONObject("error")?.optString("message").orEmpty()
            .ifBlank { json.optString("message") }
            .ifBlank { json.optString("detail") }
            .take(120)
    }

    private fun httpMessage(code: Int, detail: String): String {
        val base = when (code) {
            401, 403 -> "AI 接口返回 HTTP $code：Key 无效或没有权限"
            404 -> "AI 接口返回 HTTP 404：路径不对，常见原因是地址少了 /v1"
            429 -> "AI 接口返回 HTTP 429：请求太频繁，稍后再试"
            in 500..599 -> "AI 服务端错误（HTTP $code）"
            else -> "AI 接口返回 HTTP $code"
        }
        return if (detail.isNotBlank()) "$base（$detail）" else base
    }

    /** Turns transport level failures into something a user can act on. */
    private fun friendlyError(error: Throwable, url: String): IllegalStateException {
        val host = url.substringAfter("://", url).substringBefore('/').substringBefore('?')
        val message = when {
            // Android 9+ blocks plain http unless the network security config allows it.
            error.message?.contains("Cleartext", ignoreCase = true) == true ->
                "系统拦截了这个地址的 http 明文请求。如果是你自己的服务，请确认地址写对了（有没有多写 / 少写 s）"
            error is java.net.UnknownHostException -> "找不到服务器 $host，检查地址拼写"
            error is java.net.SocketTimeoutException -> "连接 $host 超时，检查地址、端口和网络"
            error is java.net.ConnectException -> "连不上 $host，检查端口是否正确、服务是否在运行"
            error is java.net.MalformedURLException -> "接口地址格式不正确：$url"
            error is javax.net.ssl.SSLException -> "HTTPS 证书校验失败（$host），确认证书有效或改用 http"
            else -> error.message ?: error.javaClass.simpleName
        }
        return IllegalStateException(message)
    }
}
