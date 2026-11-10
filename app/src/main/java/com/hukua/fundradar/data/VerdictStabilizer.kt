package com.hukua.fundradar.data

import android.content.Context
import org.json.JSONObject

/**
 * 上一次的判断，加上「今天已经改过几次方向」。
 *
 * 只记最后一次结论是不够的：A → B → A 这样来回摆，每次看「上一次」都是刚
 * 换过的，拦不住。所以把当天的切换次数一起记住，同一天第二次改口就要更高的
 * 门槛。
 */
data class VerdictMemory(
    val verdict: AiVerdict,
    /** 结论所属的交易日，`yyyy-MM-dd`。跨天自动清零。 */
    val day: String,
    /** 当天已经发生过的方向切换次数。 */
    val switches: Int = 0
)

/**
 * 把模型的原始结论收敛成「可以端给用户」的结论。
 *
 * ## 为什么要本地再拦一道
 * 提示词能大幅降低乱改口的概率，但模型终究不是确定性的状态机：同样的数据
 * 问两次，给 BUY 和 HOLD 都很正常。用户真正感知到的是「刚说买入，五分钟
 * 后说观望」——这种事必须在客户端兜住，不能指望提示词。
 *
 * 这里做四件事，顺序从不信任到信任：
 * 1. 模型没给出可解析的动作 → 沿用上一次；
 * 2. 模型自相矛盾（说震荡却要买卖）→ 按震荡处理，action 改 HOLD；
 * 3. 想改方向但证据不足（置信度涨得不够 + 今天本来就没怎么动）→ 沿用上一次，
 *    并记下它原本想干什么，界面照实说；
 * 4. 同一天已经改过一次 → 第二次改口需要置信度 ≥ 75 且 trend 与动作同向。
 */
object VerdictStabilizer {

    /** 改口至少要比上次高这么多分。提示词里写的也是这个数。 */
    const val MIN_CONFIDENCE_GAIN = 15

    /** 当日涨跌在这个范围内，视为「没怎么动」，不给改口。 */
    const val QUIET_DAY_PCT = 1.0

    /** 同一天第二次改口要的置信度。 */
    const val SAME_DAY_SWITCH_CONFIDENCE = 75

    /** 置信度平滑系数：新结论占六成，避免显示值跟着一起跳。 */
    private const val SMOOTH_NEW = 0.6
    private const val SMOOTH_KEPT = 0.2

    /**
     * @param dayChangePct 标的当日涨跌幅（%），拿不到就传 null（此时不按「没动」处理）。
     * @param previousExpired 上一次结论是否已过有效期。过期的结论本来就不该继续
     *        绑住判断，所以不再要求「置信度涨 15 分」才准改口；同日改口次数限制
     *        仍然生效，防的是十分钟内来回摆，不是跨周重评。
     * @return 收敛后的结论 + 新的记忆
     */
    fun stabilize(
        raw: AiVerdict,
        memory: VerdictMemory?,
        dayChangePct: Double?,
        today: String = todayText(),
        previousExpired: Boolean = false
    ): Pair<AiVerdict, VerdictMemory> {
        val previous = memory?.verdict
        // 「没有历史」和「上次也没答出东西」都要当第一次处理，否则一次乱码
        // 会把后面所有判断都锁在 UNKNOWN 上。
        if (previous == null || previous.action == SwingAction.UNKNOWN) {
            // 第一次判断不设门槛，否则用户永远拿不到初始结论。
            return raw to VerdictMemory(raw, today, 0)
        }
        val sameDay = memory.day == today
        val switches = if (sameDay) memory.switches else 0

        // 1. 解析不出动作：沿用上次。
        if (raw.action == SwingAction.UNKNOWN) {
            return keep(previous, raw, today, switches)
        }

        // 2. 自相矛盾：trend 说震荡，却要买卖。
        val normalized = if (raw.trend == Trend.RANGE && raw.action != SwingAction.HOLD) {
            raw.copy(
                action = SwingAction.HOLD,
                intended = raw.action,
                confidence = minOf(raw.confidence, 55),
                reason = "趋势判定为震荡，不构成" + raw.action.label + "。" + raw.reason
            )
        } else {
            raw
        }

        // 3. 动作没变：平滑置信度即可。
        if (normalized.action == previous.action) {
            val smoothed = (normalized.confidence * SMOOTH_NEW + previous.confidence * (1 - SMOOTH_NEW)).toInt()
            val merged = normalized.copy(
                confidence = smoothed,
                kept = false,
                intended = normalized.action,
                previous = previous.action
            )
            return merged to VerdictMemory(merged, today, switches)
        }

        // 4. 要换方向：够不够格。过期结论不受「涨 15 分」约束。
        val gain = normalized.confidence - previous.confidence
        val quiet = dayChangePct != null && kotlin.math.abs(dayChangePct) < QUIET_DAY_PCT
        val weakEvidence = gain < MIN_CONFIDENCE_GAIN && quiet && !previousExpired
        val dayCapHit = sameDay && switches >= 1 &&
            !(normalized.confidence >= SAME_DAY_SWITCH_CONFIDENCE && agrees(normalized))

        if (weakEvidence || dayCapHit) {
            return keep(previous, normalized, today, switches)
        }

        val switched = normalized.copy(kept = false, intended = normalized.action, previous = previous.action)
        return switched to VerdictMemory(switched, today, switches + 1)
    }

    /** 沿用上一次的结论，但保留这次的分析文字 —— 用户能看到「它想改但被拦了」。 */
    private fun keep(
        previous: AiVerdict,
        raw: AiVerdict,
        today: String,
        switches: Int
    ): Pair<AiVerdict, VerdictMemory> {
        val confidence = (previous.confidence * (1 - SMOOTH_KEPT) + raw.confidence * SMOOTH_KEPT).toInt()
        val kept = previous.copy(
            confidence = confidence.coerceIn(0, 100),
            reason = raw.reason.ifBlank { previous.reason },
            risk = raw.risk.ifBlank { previous.risk },
            raw = raw.raw,
            at = System.currentTimeMillis(),
            trend = if (raw.trend != Trend.UNKNOWN) raw.trend else previous.trend,
            kept = true,
            intended = raw.action,
            previous = previous.action
        )
        return kept to VerdictMemory(kept, today, switches)
    }

    /** trend 与 action 同向才允许在「同日已改过一次」之后再次改口。 */
    private fun agrees(verdict: AiVerdict): Boolean = when (verdict.action) {
        SwingAction.BUY -> verdict.trend == Trend.UP
        SwingAction.SELL -> verdict.trend == Trend.DOWN
        else -> true
    }
}

/**
 * 把 [VerdictMemory] 落到 SharedPreferences：关掉 App 再进来，上次的结论还在，
 * 「维持」才维持得住。
 */
class VerdictStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("ai_verdict", Context.MODE_PRIVATE)

    fun read(code: String): VerdictMemory? {
        val raw = prefs.getString(key(code), null) ?: return null
        return runCatching {
            val root = JSONObject(raw)
            val verdict = AiVerdict(
                action = SwingAction.of(root.optString("action")),
                confidence = root.optInt("confidence", 0).coerceIn(0, 100),
                reason = root.optString("reason"),
                risk = root.optString("risk"),
                raw = root.optString("raw"),
                at = root.optLong("at", 0L),
                trend = Trend.of(root.optString("trend")),
                kept = false,
                intended = SwingAction.of(root.optString("action")),
                previous = null
            )
            VerdictMemory(
                verdict = verdict,
                day = root.optString("day"),
                switches = root.optInt("switches", 0)
            )
        }.getOrNull()
    }

    fun write(code: String, memory: VerdictMemory) {
        runCatching {
            val verdict = memory.verdict
            val root = JSONObject()
                .put("action", verdict.action.name)
                .put("confidence", verdict.confidence)
                .put("reason", verdict.reason)
                .put("risk", verdict.risk)
                .put("raw", verdict.raw.take(2_000))
                .put("at", verdict.at)
                .put("trend", verdict.trend.name)
                .put("day", memory.day)
                .put("switches", memory.switches)
            prefs.edit().putString(key(code), root.toString()).apply()
        }
    }

    fun clear(code: String) {
        prefs.edit().remove(key(code)).apply()
    }

    private fun key(code: String): String = "v_$code"
}
