package com.hukua.fundradar.data

import org.json.JSONObject

/** One stock inside a fund's portfolio. */
data class HoldingStock(
    val code: String,
    val name: String,
    /** Share of the fund's assets, in percent (0–100). */
    val weight: Double,
    /** 1 = 上海, 0 = 深圳, as reported by the feed. */
    val market: Int
)

/**
 * What a fund holds, as far as the estimate needs to know: the ETF it feeds on
 * (联接基金) and/or its top stocks.
 */
data class FundPosition(
    val etfCode: String = "",
    val etfName: String = "",
    val stocks: List<HoldingStock> = emptyList()
) {
    val isEmpty: Boolean get() = etfCode.isBlank() && stocks.isEmpty()
}

/**
 * 天天基金's mobile "持仓" endpoint. It answers both halves of the question in
 * one request: `ETFCODE` names the ETF a feeder fund buys, `fundStocks` lists
 * the top ten holdings of everything else.
 */
object PositionApi {

    private const val URL =
        "https://fundmobapi.eastmoney.com/FundMNewApi/FundMNInverstPosition" +
            "?FCODE=%s&deviceid=1&plat=Android&product=EFund&version=6.2.8"

    suspend fun fetch(code: String): FundPosition? {
        val body = runCatching {
            Net.get(URL.format(code), referer = "https://fund.eastmoney.com/")
        }.getOrNull() ?: return null
        return parse(body)
    }

    /** Visible for tests. */
    internal fun parse(body: String): FundPosition? {
        val data = runCatching {
            JSONObject(body).optJSONObject("Datas")
        }.getOrNull() ?: return null
        if (data == JSONObject.NULL) return null

        val stocks = ArrayList<HoldingStock>()
        val rows = data.optJSONArray("fundStocks")
        if (rows != null) {
            for (i in 0 until rows.length()) {
                val item = rows.optJSONObject(i) ?: continue
                val code = item.optString("GPDM").trim()
                if (code.isBlank()) continue
                stocks += HoldingStock(
                    code = code,
                    name = item.optString("GPJC").trim(),
                    weight = item.optString("JZBL").toDoubleOrNull() ?: 0.0,
                    market = item.optString("NEWTEXCH").toIntOrNull() ?: -1
                )
            }
        }

        val position = FundPosition(
            etfCode = data.optString("ETFCODE").trim().takeUnless { it == "null" }.orEmpty(),
            etfName = data.optString("ETFSHORTNAME").trim().takeUnless { it == "null" }.orEmpty(),
            stocks = stocks
        )
        return position.takeUnless { it.isEmpty }
    }
}
