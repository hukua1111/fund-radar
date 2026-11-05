package com.hukua.fundradar.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Sends a notification through Bark (`https://api.day.app/<key>/<title>/<body>`).
 * The configured URL is the base that already contains the device key.
 */
object BarkNotifier {

    suspend fun push(config: AiConfig, title: String, body: String): Boolean =
        withContext(Dispatchers.IO) {
            val base = normalizeBaseUrl(config.barkUrl)
            if (base.isEmpty()) return@withContext false

            val url = "$base/${encode(title)}/${encode(body)}"
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                requestMethod = "GET"
            }
            try {
                val code = connection.responseCode
                connection.inputStream?.close()
                code in 200..299
            } finally {
                connection.disconnect()
            }
        }

    private fun encode(text: String): String =
        URLEncoder.encode(text, "UTF-8").replace("+", "%20")
}
