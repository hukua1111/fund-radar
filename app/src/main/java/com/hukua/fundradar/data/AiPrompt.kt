package com.hukua.fundradar.data

import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/** 一次判断要发给模型的两段文本。 */
data class JudgePrompt(val system: String, val user: String) {
    /** 完整原文，测试和「查看本轮提示词」用。 */
    fun render(): String = "【system】\n$system\n\n【user】\n$user"
}

/**
 * 拼装发给模型的提示词。
 *
 * ## 为什么单独抽出来
 * AI 判断飘不飘，八成取决于这里给了什么数据、立了什么规矩，而不是模型本身。
 * 抽成纯函数后可以直接用真实行情跑出完整原文来比对（见 AiPromptTest），
 * 调参不用靠猜。
 *
 * ## 三条硬规矩（对着「反复横跳」来的）
 * 1. **指标只用定型的日线**：盘中那根每跳一次，MA/MACD/BOLL 就变一次，模型
 *    自然跟着改口。这里先用 [KlineSeries.completed] 剔掉未收盘的那根，实时价
 *    单独放在「盘中」一段，并明确写「不作为改变结论的依据」。
 * 2. **把定性判断在本地做完**：均线是不是多头排列、离 MA20 算不算贴线、量能
 *    是放是缩，全部由本地算成中文档位再给模型，避免它自己「看着像」。
 * 3. **改口要付代价**：把上一次结论、今天已经改过几次都写进去，并要求改口
 *    必须指出具体是哪一项数据发生了实质变化、置信度要高出 15 分。
 */
internal object AiPrompt {

    /** 与 [VerdictStabilizer] 用同一套阈值，提示词和本地拦截不能自相矛盾。 */
    private const val QUIET_BAND_PCT = VerdictStabilizer.QUIET_DAY_PCT

    /**
     * trend 档位的分界：收盘离 MA20 超过这个百分比才算走出震荡。
     *
     * 1.14 用的是 1%，实测太钝 —— 40 例消融里 5 例被卡在 RANGE，后续 20 日合计
     * +43%，全被「RANGE 只能 HOLD」吃掉了。1.15 收到 0.5%，和规则 3 的措辞一致。
     */
    private const val TREND_BAND_PCT = 0.5

    /** 大盘开关：6 个宽基里至少这么多个下跌…… */
    private const val MARKET_DOWN_COUNT = 5

    /** ……并且平均跌幅到这个数，才判定为系统性下跌日。 */
    private const val MARKET_DOWN_AVG = -1.0

    /** 结论有效期（交易日）。BUY 给的长一些，波段买入本来就该拿住。 */
    const val VALID_DAYS_BUY = 10
    const val VALID_DAYS_OTHER = 5

    fun judge(
        fundName: String,
        code: String,
        subject: String,
        raw: KlineSeries,
        context: AiContext,
        previous: AiVerdict?,
        sameDaySwitches: Int,
        now: Calendar = Calendar.getInstance()
    ): JudgePrompt {
        val series = raw.completed(now = now)
        val latest = series.latest ?: raw.latest
        val openBar = raw.latest.takeIf { raw.hasOpenBar(now = now) }
        val today = todayText(now)

        // 上次结论离现在几个交易日 —— 结论是有保质期的，隔了十几个交易日还拿
        // 「维持」压着，等于让一个过期判断绑住后面所有判断。
        val previousDay = previous?.at?.takeIf { it > 0 }?.let { dayText(it) }
        val elapsedBars = previousDay?.let { day -> series.candles.count { it.date > day } } ?: 0
        val validity = if (previous?.action == SwingAction.BUY) VALID_DAYS_BUY else VALID_DAYS_OTHER
        val expired = previous != null && elapsedBars >= validity

        val body = buildString {
            appendLine("$subject：${fundName.ifBlank { code }}（$code）")
            val kind = if (series.source == KlineSource.EXCHANGE) "场内交易K线" else "场外每日净值"
            appendLine("行情来源：" + series.meta.label.ifBlank { kind } + if (series.meta.label.isNotBlank()) "（$kind）" else "")
            appendLine("技术指标基准日：${latest?.date ?: "-"}（已收盘定型）")
            if (openBar != null) appendLine("现在：${clockText(now)}（盘中，今日这根还没收盘）")

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
                val down = context.pulse.count { it.percent < 0 }
                val avg = context.pulse.sumOf { it.percent } / context.pulse.size
                appendLine("${context.pulse.size} 个宽基里 $up 涨 $down 跌，平均 ${pct(avg)}（只是指数样本，不是全市场涨跌家数）")
                // 大盘开关：只做标注，不做命令。
                //
                // 上一版这里是「普跌日禁止买入」，依据是 6 例样本上命中率从 0/6 变成
                // 3/6。但把同样的规则机械化、在 14 只 ETF 的 2018 年以来全历史上跑，
                // 年化从 7.02% 掉到 5.74%，触发日次日 ETF 平均还涨 0.14%（全样本
                // +0.03%）—— 那条规则是过拟合，普跌日一刀切不买会系统性错过反弹。
                appendLine(
                    if (down >= MARKET_DOWN_COUNT && avg <= MARKET_DOWN_AVG) {
                        "标注：系统性下跌日。历史上这类日子的次日并不必然继续跌，不要因为「今天大跌」就一律不买；" +
                            "但也不能拿「跌多了会反弹」当买入理由，要买仍须有标的自身证据。"
                    } else if (up >= MARKET_DOWN_COUNT && avg >= -MARKET_DOWN_AVG) {
                        "标注：普涨日。不要因为大盘好就买，也不必因为大盘涨就不卖。"
                    } else {
                        "标注：正常（没有一边倒的普跌或普涨）。"
                    }
                )
            }

            // 二、风格 / 板块
            appendLine()
            appendLine("【二、风格与板块】")
            // 基准日是上一根定型的 K 线，这里必须写「最近一根」而不是「今日」，
            // 否则模型会把它当成今天的涨跌，跟下面盘中实时那段自相矛盾。
            appendLine(
                "标的自身：最近一根（${latest?.date ?: "-"}） " + pct(series.changeOver(1)) +
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

            // 三、成分
            appendLine()
            appendLine("【三、前十大重仓（成分）】")
            if (context.constituents.isEmpty()) {
                appendLine("（没有可用的持仓明细）")
            } else {
                appendLine(context.constituents.joinToString("  ") { (name, percent) -> "$name ${pct(percent)}" })
            }

            // 四、技术面（全部用定型日线）
            appendLine()
            appendLine("【四、技术面（全部基于 ${latest?.date ?: "-"} 收盘，已定型）】")
            appendLine("收盘：" + String.format(Locale.US, "%.4f", latest?.close ?: 0.0))
            val ma = series.maValues()
            appendLine(
                "均线：" + ma.entries.joinToString("  ") { (period, value) ->
                    "MA$period=" + (value?.let { String.format(Locale.US, "%.4f", it) } ?: "无")
                }
            )
            val vsMa20 = ma[20]?.takeIf { it > 0 }?.let { value ->
                latest?.let { (it.close - value) / value * 100.0 }
            }
            appendLine("收盘相对MA20：" + (vsMa20?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "无") +
                "（" + bandText(vsMa20) + "）")
            appendLine("均线排列：" + arrangementText(ma))

            val macd = series.macd()
            val histNow = macd.latestHist
            val hist1 = macd.previousHist
            val hist2 = if (macd.hist.size >= 3) macd.hist[macd.hist.size - 3] else null
            appendLine(
                "MACD(12,26,9)：DIF=" + (macd.latestDif?.let { String.format(Locale.US, "%+.4f", it) } ?: "无") +
                    "  DEA=" + (macd.latestDea?.let { String.format(Locale.US, "%+.4f", it) } ?: "无") +
                    "  柱=" + (histNow?.let { String.format(Locale.US, "%+.4f", it) } ?: "无") +
                    "  前一根柱=" + (hist1?.let { String.format(Locale.US, "%+.4f", it) } ?: "无") +
                    "  再前一根柱=" + (hist2?.let { String.format(Locale.US, "%+.4f", it) } ?: "无") +
                    "（" + macdText(histNow, hist1) + "；" + histTrendText(histNow, hist1, hist2) + "）"
            )

            val boll = series.boll()
            val bollUpper = boll.upper.lastOrNull()
            val bollMiddle = boll.middle.lastOrNull()
            val bollLower = boll.lower.lastOrNull()
            val bollPosition = if (bollUpper != null && bollLower != null && bollUpper > bollLower && latest != null) {
                ((latest.close - bollLower) / (bollUpper - bollLower) * 100).coerceIn(0.0, 100.0)
            } else {
                null
            }
            appendLine(
                "布林带BOLL(20,2)：上轨=" + (bollUpper?.let { String.format(Locale.US, "%.4f", it) } ?: "无") +
                    "  中轨=" + (bollMiddle?.let { String.format(Locale.US, "%.4f", it) } ?: "无") +
                    "  下轨=" + (bollLower?.let { String.format(Locale.US, "%.4f", it) } ?: "无") +
                    (bollPosition?.let { String.format(Locale.US, "  带宽位置=%.0f%%", it) } ?: "")
            )

            val ratio = series.volumeRatio()
            appendLine(
                "成交量：最近一根 " + volumeText(latest?.volume ?: 0.0) +
                    "，5日均量比 " + (ratio?.let { String.format(Locale.US, "%.2f", it) } ?: "无") +
                    "（" + volumeHint(ratio) + "）"
            )

            // 量能和相对强弱本地算好摆出来，避免它一边说「缩量」一边给 75 分。
            // 1.15 去掉了「三项不同时满足就 ≤60」的硬封顶：消融里这条一次都没改变
            // 动作，却绑死了量能门槛（机械回测 −3.53pp），只留事实不加命令。
            appendLine()
            appendLine("【四·补充】量能与相对强弱（本地算好的事实，不设硬上限）")
            val ratioForCap = series.volumeRatio()
            val ratioText = String.format(Locale.US, "%.2f", ratioForCap ?: 0.0)
            appendLine("5日均量比：$ratioText（" + volumeHint(ratioForCap) + "）")
            val relCapText = context.relative20?.let {
                String.format(Locale.US, "%+.2f", it) + " 个百分点，" +
                    (if (it >= 0) "强于大盘" else "弱于大盘")
            } ?: "无数据"
            appendLine("20日相对沪深300：$relCapText")

            appendLine()
            appendLine("最近10个交易日（日期 开高低收）：")
            append(
                series.candles.takeLast(10).joinToString("\n") { candle ->
                    String.format(
                        Locale.US,
                        "%s 开%.4f 高%.4f 低%.4f 收%.4f",
                        candle.date, candle.open, candle.high, candle.low, candle.close
                    )
                }
            )

            // 五、盘中（明确标注不可作为改口依据）
            appendLine()
            appendLine("【五、盘中实时（未定型，只用于判断「现在这个位置」，不能作为改变结论的依据）】")
            if (openBar == null) {
                appendLine("（当前已收盘或没有当日数据，这一项为空）")
            } else {
                val prevClose = series.candles.lastOrNull()?.close ?: 0.0
                val vsPrev = if (prevClose > 0) (openBar.close - prevClose) / prevClose * 100.0 else null
                val vsMa20Live = ma[20]?.takeIf { it > 0 }?.let { (openBar.close - it) / it * 100.0 }
                appendLine(
                    "现价 " + String.format(Locale.US, "%.4f", openBar.close) +
                        "，较昨收 " + pct(vsPrev) +
                        "，相对MA20 " + pct(vsMa20Live) + "（实时口径）"
                )
                // 振幅是个绝对幅度的概念，不能带正负号。
                val amplitude = if (prevClose > 0) (openBar.high - openBar.low) / prevClose * 100.0 else null
                appendLine(
                    "今日振幅 " + (amplitude?.let { String.format(Locale.US, "%.2f%%", it) } ?: "无") +
                        "，今日成交量 " + volumeText(openBar.volume)
                )
            }

            // 六、账户
            appendLine()
            appendLine("【六、我的账户】")
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

            // 七、事件与约束
            appendLine()
            appendLine("【七、事件与约束】")
            if (context.events.isEmpty()) {
                appendLine("没有特别的申购限制或事件")
            } else {
                context.events.forEach { appendLine("- " + it) }
            }
            if (context.missing.isNotEmpty()) {
                appendLine("本次缺失的数据：" + context.missing.joinToString("、") + "（这些不要编造）")
            }

            // 八、上一次的判断
            appendLine()
            appendLine("【八、上一次的判断】")
            if (previous == null) {
                appendLine("这是第一次判断，没有历史结论。")
            } else {
                appendLine(
                    "上一次（${stampText(previous.at)}）给出：" + previous.action.label +
                        "（置信度 ${previous.confidence}），趋势档位：" + previous.trend.label
                )
                if (previous.reason.isNotBlank()) appendLine("上次理由：" + previous.reason)
                if (expired) {
                    appendLine(
                        "这条结论已过期：有效期 $validity 个交易日，现在已经过去 $elapsedBars 个交易日。" +
                            "按规则 9 可以重新判断，不必非要维持，但要在 reason 里说明和上次相比变了什么。"
                    )
                } else {
                    appendLine("这条结论仍在有效期内（有效期 $validity 个交易日，已过 $elapsedBars 个），按规则 9 处理。")
                }
                appendLine(
                    if (sameDaySwitches > 0) {
                        "注意：今天已经改过 $sameDaySwitches 次方向了。"
                    } else {
                        "今天还没有改过方向。"
                    }
                )
            }
        }

        val rules = buildString {
            appendLine("请判断当前更适合「波段买入」「波段卖出」还是「观望」。")
            appendLine()
            appendLine("1. 按 大盘 → 板块 → 标的自身技术面 → 我的账户 的顺序看，不要只看一条均线。")
            appendLine("2. 技术面同时看：MA5/10/20/60 的排列、MACD 的 DIF/DEA 与柱线走向、布林带位置、近 5/20/60 日涨跌、量能。")
            appendLine("3. 给出趋势档位 trend（MACD 看「最近两根柱有没有连续缩短」，不要求单根放大）：")
            appendLine("   - UP：收盘在 MA20 上方超过 0.5%，且 MA5 > MA10，且 MACD 柱为正、最近两根没有连续缩短；")
            appendLine("   - DOWN：收盘在 MA20 下方超过 0.5%，且 MA5 < MA10，且 MACD 柱为负、最近两根没有连续缩短；")
            appendLine("   - RANGE：不满足上面两条的都算震荡。")
            appendLine("4. trend=UP 时不给 SELL，trend=DOWN 时不给 BUY。trend=RANGE 时可以给 BUY，但必须写清「这次买的是启动不是趋势」，并点明触发条件（哪条均线被站上、哪个指标刚转向）；写不出来就给 HOLD。")
            appendLine("5. 大盘只作背景，不能单独当理由：普跌日不许拿「跌多了会反弹」当买入理由，普涨日不许拿「大盘好」当买入理由，也不必因为大盘涨就不敢卖。")
            appendLine("6. 有上次结论且还在有效期内（BUY 10 个交易日，SELL/HOLD 5 个）时，要改方向必须能在数据里指出一项实质变化（首次上穿/下穿 MA20 超 0.5%、MACD 柱转色且连续两根同向、20 日相对沪深300 由强转弱或反向）；指不出来就维持上次的 action，置信度不得高于上次 +10，理由开头写「维持：」。")
            appendLine("7. 给 HOLD 必须写清在等什么：站上或跌破哪条均线、哪个指标转向，以及条件满足后做什么。")
            appendLine("8. 缺失的数据要说明，不要编造消息面。")
            append("只输出一个 JSON 对象，不要 markdown 代码块，不要多余文字：")
            append(
                """{"action":"BUY|SELL|HOLD","trend":"UP|RANGE|DOWN","confidence":72,""" +
                    """"reason":"80字以内，需说明大盘与板块的相对强弱，HOLD 要写清在等什么","risk":"30字以内，买入要写失效价（跌破X就不再成立）"}"""
            )
        }

        return JudgePrompt(system = SYSTEM, user = buildString {
            appendLine(body)
            append(rules)
        })
    }

    /**
     * 批量判断（每天收盘后跑一次），只给均线和涨跌，够做「今天要不要看一眼」。
     * 这条链路不参与盘中反复判断，所以不带上次结论。
     */
    fun batch(items: List<BatchItem>, now: Calendar = Calendar.getInstance()): JudgePrompt {
        val blocks = items.joinToString("\n\n") { item ->
            val series = item.series.completed(now = now)
            val latest = series.latest ?: item.series.latest
            val ma = series.maValues()
            buildString {
                appendLine("【${item.name.ifBlank { item.code }}】代码 ${item.code}")
                appendLine("最新 ${latest?.date ?: "-"} 收盘/净值 ${latest?.let { String.format(Locale.US, "%.4f", it.close) } ?: "-"}")
                appendLine(
                    ma.entries.joinToString("  ") { (period, value) ->
                        "MA$period=" + (value?.let { String.format(Locale.US, "%.4f", it) } ?: "无")
                    }
                )
                val band = series.boll()
                appendLine(
                    "BOLL(20,2) 上=" + (band.upper.lastOrNull()?.let { String.format(Locale.US, "%.4f", it) } ?: "无") +
                        " 中=" + (band.middle.lastOrNull()?.let { String.format(Locale.US, "%.4f", it) } ?: "无") +
                        " 下=" + (band.lower.lastOrNull()?.let { String.format(Locale.US, "%.4f", it) } ?: "无")
                )
                append(
                    "近5日=${pct(series.changeOver(5))}  近20日=${pct(series.changeOver(20))}  " +
                        "近60日=${pct(series.changeOver(60))}"
                )
            }
        }

        val user = buildString {
            appendLine("下面是我自选基金跟踪的指数的最新均线与涨跌数据，请逐个判断当前是否适合波段买入 / 波段卖出 / 观望。")
            appendLine()
            appendLine(blocks)
            appendLine()
            appendLine("要求：结合 MA5/MA10/MA20/MA60 的多空排列与价格偏离度；指数不能直接买卖，结论用于指导对应基金的申赎时机，判断偏保守。")
            append("只输出 JSON 数组，不要代码块：")
            append("""[{"code":"161725","action":"BUY|SELL|HOLD","confidence":0-100,"reason":"40字以内中文理由"}]""")
        }

        return JudgePrompt(system = BATCH_SYSTEM, user = user)
    }

    private val SYSTEM = "你是基金波段交易助手。你只依据用户给出的数据判断，" +
        "不编造消息面，不给绝对化保证。用中文回答，只输出一个 JSON 对象。"

    private val BATCH_SYSTEM = "你是一名谨慎的波段交易助手，只依据给定数据分析，不编造消息面，" +
        "用中文回答，且只输出 JSON 数组。"

    private fun pct(value: Double?): String =
        value?.let { String.format(Locale.US, "%+.2f%%", it) } ?: "无"

    /** 价格相对 MA20 的档位。±1% 内算贴线，直接告诉模型「这是震荡区」。 */
    private fun bandText(percent: Double?): String = when {
        percent == null -> "无 MA20 可比"
        abs(percent) <= TREND_BAND_PCT -> String.format(Locale.US, "贴线，±%.1f%% 内属于震荡区", TREND_BAND_PCT)
        percent > 0 -> "在中轨上方"
        else -> "在中轨下方"
    }

    /** 均线排列说成人话，省得模型自己「看着像多头」。 */
    private fun arrangementText(ma: Map<Int, Double?>): String {
        val m5 = ma[5] ?: return "数据不足"
        val m10 = ma[10] ?: return "数据不足"
        val m20 = ma[20] ?: return "数据不足"
        val m60 = ma[60]
        val spread = if (m20 > 0) abs(m5 - m20) / m20 * 100.0 else 100.0
        return when {
            spread <= 0.5 -> String.format(Locale.US, "均线纠缠（MA5 与 MA20 相差 %.2f%%，无方向）", spread)
            m5 > m10 && m10 > m20 && (m60 == null || m20 > m60) -> "多头排列（MA5>MA10>MA20>MA60）"
            m5 < m10 && m10 < m20 && (m60 == null || m20 < m60) -> "空头排列（MA5<MA10<MA20<MA60）"
            m5 > m10 && m10 > m20 -> "短期多头，中期未确认"
            m5 < m10 && m10 < m20 -> "短期空头，中期未确认"
            else -> "均线交叉，方向不明"
        }
    }

    /** MACD 柱的方向与力度。 */
    private fun macdText(hist: Double?, previous: Double?): String = when {
        hist == null -> "无"
        previous == null -> if (hist >= 0) "红柱" else "绿柱"
        hist >= 0 && previous < 0 -> "由绿转红"
        hist < 0 && previous >= 0 -> "由红转绿"
        hist >= 0 && hist > previous -> "红柱放大"
        hist >= 0 -> "红柱缩短"
        hist < 0 && hist < previous -> "绿柱放大"
        else -> "绿柱缩短"
    }

    /**
     * 最近两根柱是不是「连续缩短」。
     *
     * 上一版只看单根放大，导致红柱缩一点点就掉出 UP 档、被规则逼成观望 ——
     * 涨得最猛的时候反而不敢买。改成看两根：只要没有连续两根衰竭，趋势还算数。
     */
    private fun histTrendText(hist: Double?, prev: Double?, prev2: Double?): String {
        if (hist == null || prev == null || prev2 == null) return "柱线历史不足"
        val shrinking = kotlin.math.abs(hist) < kotlin.math.abs(prev) && kotlin.math.abs(prev) < kotlin.math.abs(prev2)
        val pureShrinking = if (hist >= 0) hist < prev && prev < prev2 else hist > prev && prev > prev2
        return when {
            pureShrinking && shrinking -> "连续两根衰竭（趋势可能转档）"
            hist >= 0 -> "红柱未连续衰竭"
            else -> "绿柱未连续衰竭"
        }
    }

    /** 时间戳 → `yyyy-MM-dd`，用来数「上次判断到现在过了几个交易日」。 */
    private fun dayText(at: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = at }
        return String.format(
            Locale.US,
            "%04d-%02d-%02d",
            calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    /** 量比说成人话。 */
    private fun volumeHint(ratio: Double?): String = when {
        ratio == null -> "无量能数据"
        ratio >= 1.5 -> "明显放量"
        ratio >= 1.1 -> "温和放量"
        ratio >= 0.9 -> "量能持平"
        ratio >= 0.7 -> "缩量"
        else -> "明显缩量"
    }

    private fun clockText(now: Calendar): String =
        String.format(Locale.US, "%02d:%02d", now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))

    private fun stampText(at: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = at }
        return String.format(
            Locale.US,
            "%04d-%02d-%02d %02d:%02d",
            calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH),
            calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE)
        )
    }
}
