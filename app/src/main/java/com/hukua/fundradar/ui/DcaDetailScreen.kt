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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp
import com.hukua.fundradar.data.DcaAiAdvice
import com.hukua.fundradar.data.DcaFactor
import com.hukua.fundradar.data.DcaPlan
import com.hukua.fundradar.data.DcaStance
import com.hukua.fundradar.data.amountText
import com.hukua.fundradar.data.growthText
import com.hukua.fundradar.data.money
import com.hukua.fundradar.data.sharesText
import com.hukua.fundradar.data.signedAmountText
import com.hukua.fundradar.ui.theme.LocalMarketColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 定投详情：一个行业主题指数的全貌。
 *
 * 四段：评分逐条依据 → 按计划回放出来的持仓 → 计划本身 → AI 复核。
 * 评分全部本地算，没配 AI 也能用；AI 只在这四段之外再加一个可反驳的意见。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DcaDetailScreen(
    row: DcaRow,
    advice: DcaAiAdvice?,
    aiLoading: Boolean,
    aiError: String?,
    onBack: () -> Unit,
    onAskAi: () -> Unit,
    onEditPlan: () -> Unit,
    onRemove: () -> Unit,
    onClearAiError: () -> Unit = {}
) {
    val report = row.report
    val plan = row.plan
    var showPrompt by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = row.displayName.ifBlank { row.entry.code },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${row.entry.code} · ${marketLabel(row.entry.market)}行业指数",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Filled.Delete, contentDescription = "从定投清单移除")
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
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (report == null) {
                SectionCard(title = "暂时没有结论") {
                    Text(
                        text = row.note.ifBlank { "这个指数的日线还没准备好，下拉刷新试试。" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                HeadCard(row)

                SectionCard(title = "值不值得定投 · ${report.score}/100") {
                    Text(
                        text = report.verdict.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = verdictColor(report.verdict)
                    )
                    Spacer(Modifier.height(10.dp))
                    report.factors.forEach { factor ->
                        FactorRow(factor)
                        Spacer(Modifier.height(12.dp))
                    }
                }

                SectionCard(title = "按计划回放出来的持仓") {
                    val sim = report.simulation
                    when {
                        sim == null -> Text(
                            text = "还没设定投计划，先设一个频率和金额，这里会按指数日线一期一期地回放。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        !sim.started -> Text(
                            text = "还没有扣过款" +
                                (if (sim.nextDue.isNotBlank()) "，下一期在 ${sim.nextDue}" else "") +
                                "。到了那天，这里会按当天的收盘点位记账。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        else -> {
                            MetricRow("已投期数", "${sim.periods} 期")
                            MetricRow("累计投入", money(sim.invested))
                            MetricRow("持有份额", sharesText(sim.units) + " 份")
                            MetricRow("当前市值", money(sim.value))
                            MetricRow(
                                label = "浮动盈亏",
                                value = signedAmountText(sim.profit) + "（" + growthText(sim.profitPct) + "）",
                                color = growthColor(sim.profit)
                            )
                            MetricRow(
                                label = "过程中最大浮亏",
                                value = String.format(Locale.US, "%.1f%%", sim.maxLossPct),
                                color = if (sim.maxLossPct <= -20) LocalMarketColors.current.down else null
                            )
                            MetricRow("首期 / 最近一期", "${sim.firstBuy} / ${sim.lastBuy}")
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "按每期收盘点位买入、不含申赎费与分红再投的差异，用来判断节奏，" +
                                    "不是你账户里的精确数字。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                SectionCard(title = "现在该怎么做") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ActionPill(report.action, actionColor(report.action))
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = if (report.live) "含盘中实时 bar" else "已收盘定型",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = report.actionReason,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (report.note.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = report.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val signal = report.signal
                    if (signal != null) {
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "短线信号 · ${signal.trend.label}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = signal.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                SectionCard(title = "定投计划") {
                    PlanLine("节奏", if (plan.enabled) plan.cadenceText() else "未设置")
                    PlanLine("每期金额", if (plan.enabled) money(plan.amount) else "未设置")
                    PlanLine("起始日", plan.startDate.ifBlank { "未设置" })
                    PlanLine("止盈线", String.format(Locale.US, "%.0f%%", plan.target))
                    if (plan.enabled) {
                        PlanLine(
                            "折算年投入",
                            money(plan.yearlyAmount()) + "（一年 " + when (plan.frequency.name) {
                                "DAILY" -> "约 244 个交易日"
                                "WEEKLY" -> "52 周"
                                else -> "12 个月"
                            } + "）"
                        )
                    }
                    if (plan.note.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "我的补充：" + plan.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onEditPlan, modifier = Modifier.fillMaxWidth()) {
                        Text(if (plan.enabled) "修改计划" else "设定投计划")
                    }
                }

                SectionCard(title = "AI 复核") {
                    Text(
                        text = "把指数现状、定投计划和回放出来的持仓一起发给模型，" +
                            "让它给一个能直接执行的态度：继续 / 加码 / 减少 / 暂停 / 卖出。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))

                    if (aiError != null) {
                        Text(
                            text = aiError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onClearAiError) { Text("知道了") }
                    }

                    if (advice != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StancePill(advice.stance)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "置信度 ${advice.confidence}%",
                                style = MaterialTheme.typography.labelSmall,
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
                            Spacer(Modifier.height(10.dp))
                            Text(text = advice.reason, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (advice.risk.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "风险：" + advice.risk,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (advice.next.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "下一步：" + advice.next,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        if (advice.prompt.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            TextButton(onClick = { showPrompt = !showPrompt }) {
                                Text(if (showPrompt) "收起本轮提示词" else "查看本轮提示词")
                            }
                            if (showPrompt) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        text = advice.prompt,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    OutlinedButton(
                        onClick = onAskAi,
                        enabled = !aiLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (aiLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("正在问…")
                        } else {
                            Text(if (advice == null) "让 AI 复核一次" else "再问一次")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeadCard(row: DcaRow) {
    val report = row.report ?: return
    val change = row.changePct
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = String.format(Locale.US, "%.2f", report.close),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = change?.let { growthText(it) } ?: "—",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = change?.let { growthColor(it) }
                        ?: MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = report.asOf,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = report.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Spacer(Modifier.height(12.dp))
            Row {
                MiniMetric(
                    "近一年分位",
                    report.percentile?.let { String.format(Locale.US, "%.0f%%", it * 100.0) } ?: "—",
                    Modifier.weight(1f)
                )
                MiniMetric(
                    "距高点回撤",
                    report.drawdown?.let { String.format(Locale.US, "-%.1f%%", it) } ?: "—",
                    Modifier.weight(1f)
                )
                MiniMetric(
                    "年化波动",
                    report.volatility?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—",
                    Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MiniMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FactorRow(factor: DcaFactor) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = factor.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${factor.score} / ${factor.max}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(5.dp))
        LinearProgressIndicator(
            progress = { if (factor.max > 0) factor.score.toFloat() / factor.max else 0f },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = factor.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun PlanLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun MetricRow(label: String, value: String, color: Color? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
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
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(10.dp)) {
        Text(
            text = stance.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

private fun timeText(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(timestamp))
