package com.hukua.fundradar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hukua.fundradar.data.DcaAiAdvice
import com.hukua.fundradar.data.DcaPlan
import com.hukua.fundradar.data.DcaStance
import com.hukua.fundradar.data.Estimate
import com.hukua.fundradar.data.Fund
import com.hukua.fundradar.data.Holding
import com.hukua.fundradar.data.TradeKind
import com.hukua.fundradar.data.amountText
import com.hukua.fundradar.data.growthText
import com.hukua.fundradar.data.money
import com.hukua.fundradar.data.navText
import com.hukua.fundradar.data.sharesText
import com.hukua.fundradar.data.signedAmountText
import com.hukua.fundradar.ui.theme.LocalMarketColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 定投详情页：一只基金的全部判断依据 + 我的持仓 + 定投计划 + AI 复核。
 *
 * 不复用持仓页的详情页 —— 那里讲的是「这只基金怎么样」，这里要讲「我该怎么投」，
 * 评分因子、计划期数、止盈线都不该挤在申购限额和基金档案中间。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DcaDetailScreen(
    fund: Fund,
    row: DcaRow?,
    estimate: Estimate?,
    advice: DcaAiAdvice?,
    /** 最新持仓（来自 FundViewModel 的 holdings），比报告里那份新。 */
    holding: Holding? = null,
    aiLoading: Boolean,
    aiError: String?,
    onBack: () -> Unit,
    onAskAi: () -> Unit,
    onEditPlan: () -> Unit,
    onRemove: () -> Unit,
    onBuy: () -> Unit,
    onSell: () -> Unit,
    onEditHolding: () -> Unit,
    onRemoveHolding: () -> Unit
) {
    val market = LocalMarketColors.current
    var showPrompt by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val currentHolding = holding ?: row?.holding
    val plan = row?.plan ?: DcaPlan()
    val nav = estimate?.nav ?: fund.nav

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = fund.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = listOf(fund.code, row?.indexName.orEmpty())
                                .filter { it.isNotBlank() }
                                .joinToString("  ·  "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { confirmRemove = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "从定投移除")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (confirmRemove) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmRemove = false },
                    title = { Text("从定投里移除？") },
                    text = { Text("只从定投清单移除，不影响持仓页和限购清单里的同一只基金，持仓记录也会保留。") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                confirmRemove = false
                                onRemove()
                            }
                        ) {
                            Text("移除", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmRemove = false }) { Text("取消") }
                    }
                )
            }

            // ---- 概况 ----
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = navText(nav),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(10.dp))
                        if (estimate != null) {
                            Text(
                                text = growthText(estimate.percent),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (estimate.percent >= 0) market.up else market.down
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = fund.type.ifBlank { "—" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "更新 " + (fund.navDate.ifBlank { "—" }) +
                            (estimate?.let { " · " + it.sourceLabel } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---- 定投价值 ----
            val report = row?.report
            if (report == null) {
                SectionCard(title = "定投价值") {
                    Text(
                        text = row?.note?.ifBlank { null }
                            ?: "还没有这只基金的指数行情，暂时算不出定投评分。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                SectionCard(title = "定投价值") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = report.score.toString(),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = verdictColor(report.verdict)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "/ 100",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = report.verdict.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = verdictColor(report.verdict)
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = report.trend.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    report.factors.forEach { factor ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = factor.name,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.width(76.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "${factor.score} / ${factor.max}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = factor.detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "基准日 " + report.asOf.ifBlank { "—" } +
                            (if (report.live) "（盘中实时 bar 不计入评分）" else "（已收盘定型）") +
                            " · 跟踪 " + report.indexName.ifBlank { "—" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                    )
                }

                // ---- 现在该怎么做 ----
                SectionCard(title = "现在该怎么做") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ActionPill(report.action, actionColor(report.action))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = report.summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = report.actionReason,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    val signals = report.signal?.signals.orEmpty()
                    if (signals.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "短线信号（跟踪指数）",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        signals.take(4).forEach { signal ->
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val color = if (signal.action ==
                                    com.hukua.fundradar.data.SignalAction.BUY
                                ) {
                                    market.up
                                } else {
                                    market.down
                                }
                                Text(
                                    text = signal.action.label + " · " + signal.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = color
                                )
                            }
                            Text(
                                text = signal.reason,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ---- 我的持仓 ----
            SectionCard(title = "我的持仓") {
                if (currentHolding == null) {
                    Text(
                        text = "还没填持仓。定投买过之后在这里记一笔，收益率和止盈判断才有依据。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onBuy) { Text("记录买入") }
                        OutlinedButton(onClick = onEditHolding) { Text("直接填持仓") }
                    }
                } else {
                    HoldingBody(currentHolding, nav)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onBuy) { Text("买入") }
                        OutlinedButton(onClick = onSell) { Text("卖出") }
                        OutlinedButton(onClick = onEditHolding) { Text("修改") }
                    }
                    TextButton(onClick = onRemoveHolding) {
                        Text("清空持仓", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // ---- 定投计划 ----
            SectionCard(title = "定投计划") {
                if (!plan.enabled) {
                    Text(
                        text = "还没设定投计划。设了每月金额和扣款日，页面上才会算「已投几期、累计投入多少」，" +
                            "达到止盈线时也会直接提醒。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onEditPlan) { Text("设定计划") }
                } else {
                    MetricRow("每期金额", money(plan.monthlyAmount))
                    MetricRow("扣款日", "每月 ${plan.dayOfMonth} 日")
                    MetricRow("起始月份", plan.startMonth.ifBlank { "—" })
                    MetricRow("止盈线", "${plan.targetProfitPct.toInt()}%")
                    MetricRow("已投期数", "${plan.periods()} 期")
                    MetricRow("累计投入", money(plan.invested()))
                    if (currentHolding != null) {
                        val value = currentHolding.marketValue(nav)
                        val invested = plan.invested()
                        val diff = value - invested
                        MetricRow(
                            "计划收益",
                            signedAmountText(diff) + " 元" +
                                if (invested > 0) "（${growthText(diff / invested * 100)}）" else "",
                            if (diff >= 0) market.up else market.down
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onEditPlan) { Text("编辑计划") }
                    }
                }
            }

            // ---- AI ----
            SectionCard(title = "AI 复核") {
                Text(
                    text = "把这只基金的技术面、我的定投计划和真实持仓成本一起发给 AI，让它复核上面的结论。" +
                        "AI 不常驻，点一次请求一次。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))

                if (advice != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StancePill(advice.stance)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "置信度 ${advice.confidence}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = timeText(advice.at),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (advice.reason.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(text = advice.reason, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (advice.risk.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "风险：" + advice.risk,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (advice.next.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "下一步：" + advice.next,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = onAskAi, enabled = !aiLoading) {
                            if (aiLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 1.5.dp
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text("重新问一次")
                        }
                        if (advice.prompt.isNotBlank()) {
                            TextButton(onClick = { showPrompt = !showPrompt }) {
                                Text(if (showPrompt) "收起提示词" else "查看本轮提示词")
                            }
                        }
                    }
                    if (showPrompt) {
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = advice.prompt,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                } else {
                    OutlinedButton(onClick = onAskAi, enabled = !aiLoading) {
                        if (aiLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.5.dp
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (aiLoading) "正在判断…" else "让 AI 结合持仓判断")
                    }
                }

                if (aiError != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = aiError,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Text(
                text = "定投判断与 AI 结论都只基于公开行情与本机记录，不含费率、持有期与申赎时点，" +
                    "仅供参考，不构成投资建议。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun HoldingBody(holding: Holding, nav: Double) {
    val market = LocalMarketColors.current
    val value = holding.marketValue(nav)
    val profit = holding.profit(nav)
    val pct = holding.profitPercent(nav)

    MetricRow("持有份额", sharesText(holding.shares))
    MetricRow("持仓成本", amountText(holding.cost) + " 元")
    MetricRow("成本单价", navText(holding.avgCost))
    MetricRow("最新市值", amountText(value) + " 元")
    MetricRow(
        "浮动盈亏",
        signedAmountText(profit) + " 元" + (pct?.let { "（" + growthText(it) + "）" } ?: ""),
        if (profit >= 0) market.up else market.down
    )
    if (holding.realized != 0.0) {
        MetricRow(
            "已实现盈亏",
            signedAmountText(holding.realized) + " 元",
            if (holding.realized >= 0) market.up else market.down
        )
    }
    val recent = holding.trades.firstOrNull()
    if (recent != null) {
        MetricRow(
            "最近一笔",
            (if (recent.kind == TradeKind.BUY) "买入 " else "卖出 ") +
                sharesText(recent.shares) + " 份 · 净值 " + navText(recent.nav),
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MetricRow(label: String, value: String, color: Color? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = color ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun StancePill(stance: DcaStance) {
    val color = when (stance) {
        DcaStance.CONTINUE, DcaStance.ADD -> MaterialTheme.colorScheme.primary
        DcaStance.REDUCE, DcaStance.PAUSE -> MaterialTheme.colorScheme.tertiary
        DcaStance.SELL -> LocalMarketColors.current.down
        DcaStance.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = stance.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

private fun timeText(timestamp: Long): String {
    if (timestamp <= 0L) return "—"
    val today = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    return "生成于 $today"
}
