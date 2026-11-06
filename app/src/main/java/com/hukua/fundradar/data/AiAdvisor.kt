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
 * (大盘、板块相对强弱、OHLC、均线、MACD、BOLL、账户成本) so the answer stays
 * grounded and can be traced back to a specific number.
 */
object AiAdvisor {

    suspend fun judge(
        config: AiConfig,
        fundName: String,
        code: String,
        series: KlineSeries,
        subject: String = "基金",
        context: AiContext = AiContext()
    ): AiVerdict = withContext(Dispatchers.IO) {
        require(config.configured) { "还没有配置 AI 接口" }
        val latest = series.latest ?: throw IllegalStateException("没有可用的行情数据")

        val maValues = series.maValues()
        val ma20 = maValues[20]
        val vsMa20 = ma20?.takeIf { it > 0 }?.let { (latest.close - it) / it * 100.0 }
        val boll = series.boll()
        val bollUpper = boll.upper.lastOrNull()
        val bollMiddle = boll.middle.lastOrNull()
        val bollLower = boll.lower.lastOrNull()
        val bollPosition = if (bollUpper != null && bollLower != null && bollUpper > bollLower) {
            ((latest.close - bollLower) / (bollUpper - bollLower) * 100).coerceIn(0.0, 100.0)
        } else {
            null
        }
        val macd = series.macd()

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

            // 一、大盘
            appendLine()
            appendLine("【一、大盘环境】")
            if (context.pulse.isEmpty()) {
                appendLine("（这次没取到大盘行情，判断时请说明这一点，不要假装知道市场冷暖）")
            } else {
                appendLine(context.pulse.joinToString("  ") {
                    "${it.name} ${String.format(Locale.US, "%.2f", it.price)} ${pct(it.percent)}"
                })
                val up = context.pulse.count { it.percent > 0 }
                appendLine("6 个宽基里 ${up} 涨 ${context.pulse.size - up} 跌（只是指数样本，不是全市场涨跌家数）")
            }

            // 二、风格 / 板块
            appendLine()
            appendLine("【二、风格与板块】")
            appendLine(
                "标的自身：今日 " + pct(series.changeOver(1)) +
                    "  近5日 " + pct(series.changeOver(5)) +
                    "  近20日 " + pct(series.changeOver(20)) +
                    "  近60日 " + pct(series.changeOver(60))
            )
            if (context.benchmark20 != null) {
                appendLine("沪深300 近20日 " + pct(context.benchmark20))
            }
            if (context.relative20 != null) {
                val tone = if (context.relative20 >= 0) "强于大盘" else "弱于大盘"
                appendLine(
                    "20日相对沪深300：" + String.format(Locale.US, "%+.2f", context.relative20) +
                        " 个百分点（$tone）"
                )
            }
            if (context.relatedFunds.isNotEmpty()) {
                appendLine("跟踪这个指数的自选基金：" + context.relatedFunds.joinToString("、"))
            }

            // 三、个券
            appendLine()
            appendLine("【三、前十大重仓（成分）】")
            if (context.constituents.isEmpty()) {
                appendLine("（没有可用的持仓明细）")
            } else {
                appendLine(context.constituents.joinToString("  ") { (name, percent) -> "$name ${pct(percent)}" })
            }

            // 四、技术面
            appendLine()
            appendLine("【四、标的技术面】")
            appendLine("最新价格/净值：" + String.format(Locale.US, "%.4f", latest.close))
            appendLine(
                "均线：" + maValues.entries.joinToString("  ") { (period, value) ->
                    "MA$period=" + (value?.let { String.format(Locale.US, "%.4f", it) } ?: "无")
                }
            )
            appendLine("现价相对MA20：" + (vsMa20?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "无"))
            appendLine(
                "MACD(12,26,9)：DIF=" + (macd.latestDif?.let { String.format(Locale.US, "%+.4f", it) } ?: "无") +
                    "  DEA=" + (macd.latestDea?.let { String.format(Locale.US, "%+.4f", it) } ?: "无") +
                    "  柱=" + (macd.latestHist?.let { String.format(Locale.US, "%+.4f", it) } ?: "无") +
                    "  前一根柱=" + (macd.previousHist?.let { String.format(Locale.US, "%+.4f", it) } ?: "无")
            )
            appendLine(
                "布林带BOLL(20,2)：" +
                    "上轨=" + (bollUpper?.let { String.format(Locale.US, "%.4f", it) } ?: "无") +
                    "  中轨=" + (bollMiddle?.let { String.format(Locale.US, "%.4f", it) } ?: "无") +
                    "  下轨=" + (bollLower?.let { String.format(Locale.US, "%.4f", it) } ?: "无") +
                    (bollPosition?.let { String.format(Locale.US, "  带宽位置=%.0f%%", it) } ?: "")
            )
            appendLine("最近10个交易日（日期 开高低收）：")
            append(recent)

            // 五、账户
            appendLine()
            appendLine("【五、我的账户】")
            if (context.accounts.isEmpty()) {
                appendLine("这个指数相关的基金目前没有填写持仓（空仓，只需考虑买不买）")
            } else {
                context.accounts.forEach { line ->
                    appendLine(
                        "${line.fundName}（${line.fundCode}）：份额 " +
                            String.format(Locale.US, "%.2f", line.shares) + "，成本价 " +
                            String.format(Locale.US, "%.4f", line.avgCost) + "，市值 " +
                            String.format(Locale.US, "%.2f", line.value) + "，浮动盈亏 " +
                            String.format(Locale.US, "%+.2f", line.profit) +
                            (line.profitPercent?.let { String.format(Locale.US, "（%+.2f%%）", it) } ?: "") +
                            (line.today?.let { "，今日 " + String.format(Locale.US, "%+.2f", it) } ?: "") +
                            (line.source.takeIf { it.isNotBlank() }?.let { "，估值来源 " + it } ?: "")
                    )
                }
            }

            // 六、事件与约束
            appendLine()
            appendLine("【六、事件与约束】")
            if (context.events.isEmpty()) {
                appendLine("没有特别的申购限制或事件")
            } else {
                context.events.forEach { appendLine("- " + it) }
            }
            if (context.missing.isNotEmpty()) {
                appendLine("本次缺失的数据：" + context.missing.joinToString("、") + "（这些不要编造）")
            }
        }

        val systemPrompt = "你是一名谨慎的基金波段交易助手。只能依据用户给出的数据判断，" +
            "不要编造消息面，不要给出绝对化的保证。用中文回答，并且只输出一个 JSON 对象。"

        val userPrompt = buildString {
            appendLine(dataBlock)
            appendLine()
            appendLine("请判断当前是否适合「波段买入」「波段卖出」或「观望」。要求：")
            appendLine("1. 按 大盘 → 板块相对强弱 → 标的自身技术面 → 我的账户 的顺序逐层分析，不要只看均线；")
            appendLine("2. 技术面必须同时看 MA5/10/20/60 的多空排列、MACD 的 DIF/DEA 位置与柱线缩短还是放大、布林带位置、近期涨跌幅；")
            appendLine("3. 板块明显弱于沪深300 时，即使标的自身形态不错也要降低买入的置信度；")
            appendLine("4. 结合我的成本：深套时不要简单劝割，浮盈时也要说明回撤风险；没有持仓就只判断该不该建仓；")
            appendLine("5. 场外基金申赎有确认延迟，判断应偏保守；缺失的数据要明确说明，不要脑补；")
            appendLine("6. 只输出 JSON，不要 markdown 代码块，不要多余文字。")
            append("""格式：{"action":"BUY|SELL|HOLD","confidence":0-100,"reason":"80字以内中文理由，需说明大盘与板块的相对强弱","risk":"30字以内风险提示"}""")
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
                val band = item.series.boll()
                appendLine(
                    "BOLL(20,2) 上=" + (band.upper.lastOrNull()?.let { String.format(Locale.US, "%.4f", it) } ?: "无") +
                        " 中=" + (band.middle.lastOrNull()?.let { String.format(Locale.US, "%.4f", it) } ?: "无") +
                        " 下=" + (band.lower.lastOrNull()?.let { String.format(Locale.US, "%.4f", it) } ?: "无")
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
