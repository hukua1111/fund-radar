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

/**
 * 趋势档位。模型必须自己先定这一档，再由它决定动作。
 *
 * 让模型直接输出「买/卖/观望」时，它在临界位置会自由裁量；先锁死档位，等于把
 * 判断收窄成一个状态机：震荡就只能观望，多头才谈买入。
 */
enum class Trend(val label: String) {
    UP("上行"),
    RANGE("震荡"),
    DOWN("下行"),
    UNKNOWN("未知");

    companion object {
        fun of(raw: String): Trend = when (raw.trim().uppercase(Locale.US)) {
            "UP", "上行", "多头", "上涨", "多头排列" -> UP
            "DOWN", "下行", "空头", "下跌", "空头排列" -> DOWN
            "RANGE", "震荡", "横盘", "盘整", "中性" -> RANGE
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
    val at: Long = System.currentTimeMillis(),
    /** 模型给出的趋势档位。 */
    val trend: Trend = Trend.UNKNOWN,
    /** 本次结论是沿用上一次的（新结论没达到切换门槛）。 */
    val kept: Boolean = false,
    /** 模型这次本来想给的动作；[kept] 为 true 且与 [action] 不同时，界面会说清楚。 */
    val intended: SwingAction = action,
    /** 被沿用/被替换掉的上一次动作。 */
    val previous: SwingAction? = null
)

/** Input / output of the batched daily judgement. */
data class BatchItem(val code: String, val name: String, val series: KlineSeries)

data class BatchVerdict(
    val code: String,
    val action: SwingAction,
    val confidence: Int,
    val reason: String
)

/** 一次判断的结果：结论本身，加上这轮实际发出去的提示词原文。 */
data class JudgeOutcome(val verdict: AiVerdict, val prompt: String)

/**
 * 问一个 OpenAI 兼容接口「现在适不适合做波段」。
 *
 * 这个类只负责三件事：拿提示词、发请求、把回复解析成 [AiVerdict]。提示词怎么
 * 拼在 [AiPrompt] 里，结论怎么收敛在 [VerdictStabilizer] 里 —— 三块分开才
 * 能各自单测。
 *
 * `temperature` 固定 0：判断类任务不需要创造性，任何随机性都会变成用户眼里
 * 的「反复横跳」。
 */
object AiAdvisor {

    private const val TEMPERATURE = 0.0

    suspend fun judge(
        config: AiConfig,
        fundName: String,
        code: String,
        series: KlineSeries,
        subject: String = "基金",
        context: AiContext = AiContext(),
        memory: VerdictMemory? = null
    ): JudgeOutcome = withContext(Dispatchers.IO) {
        require(config.configured) { "还没有配置 AI 接口" }
        require(series.latest != null) { "没有可用的行情数据" }

        val today = todayText()
        val sameDay = memory?.day == today
        val prompt = AiPrompt.judge(
            fundName = fundName,
            code = code,
            subject = subject,
            raw = series,
            context = context,
            previous = memory?.verdict,
            sameDaySwitches = if (sameDay) memory?.switches ?: 0 else 0
        )

        val content = chat(config, prompt)
        JudgeOutcome(parseVerdict(content), prompt.render())
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

        val action = SwingAction.of(parsed.optString("action"))
        return AiVerdict(
            action = action,
            confidence = parsed.optInt("confidence", 0).coerceIn(0, 100),
            reason = parsed.optString("reason").ifBlank { parsed.optString("message") },
            risk = parsed.optString("risk"),
            raw = content,
            trend = Trend.of(parsed.optString("trend")),
            intended = action
        )
    }

    /** One call covering the whole watchlist, used by the daily job. */
    suspend fun judgeBatch(
        config: AiConfig,
        items: List<BatchItem>
    ): List<BatchVerdict> = withContext(Dispatchers.IO) {
        require(config.configured) { "还没有配置 AI 接口" }
        require(items.isNotEmpty()) { "自选列表为空" }

        val content = chat(config, AiPrompt.batch(items))
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

    /** 发一轮 chat completion，返回 `choices[0].message.content`。 */
    private fun chat(config: AiConfig, prompt: JudgePrompt): String {
        val payload = JSONObject().apply {
            put("model", config.model)
            put("temperature", TEMPERATURE)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", prompt.system))
                put(JSONObject().put("role", "user").put("content", prompt.user))
            })
        }
        return JSONObject(postJson(config.endpoint(), config.apiKey, payload.toString()))
            .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?.optString("content")?.trim()
            ?: throw IllegalStateException("AI 返回格式不正确")
    }

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
