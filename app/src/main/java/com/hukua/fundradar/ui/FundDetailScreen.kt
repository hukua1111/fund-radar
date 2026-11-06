package com.hukua.fundradar.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hukua.fundradar.data.Estimate
import com.hukua.fundradar.data.EstimateSource
import com.hukua.fundradar.data.Fund
import com.hukua.fundradar.data.Holding
import com.hukua.fundradar.data.IndexEntry
import com.hukua.fundradar.data.TradeKind
import com.hukua.fundradar.data.amountText
import com.hukua.fundradar.data.growthText
import com.hukua.fundradar.data.money
import com.hukua.fundradar.data.navText
import com.hukua.fundradar.data.riskText
import com.hukua.fundradar.data.sharesText
import com.hukua.fundradar.data.signedAmountText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Second-level page: everything about one fund, reached by tapping its card.
 *
 * 持仓页与限购页共用一个页面，但关注点不同：持仓页显示「我的持仓」（份额、
 * 成本、市值、盈亏、买卖记录）和盘中估算；限购页显示申购限额与基金档案。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FundDetailScreen(
    state: DetailState,
    onBack: () -> Unit,
    onRemove: (String) -> Unit = {},
    /** Intraday estimate, portfolio page only. */
    estimate: Estimate? = null,
    showLimitBadge: Boolean = true,
    removeLabel: String = "从自选移除",
    /** 用户手动指定的跟踪指数，没有则为 null（按基金名自动识别）。 */
    trackOverride: IndexEntry? = null,
    /** 自动识别/手动指定解析出来的指数，用来直接跳 K 线。 */
    trackedIndex: IndexEntry? = null,
    onOpenKline: (() -> Unit)? = null,
    onEditTrack: () -> Unit = {},
    onClearTrack: () -> Unit = {},
    /** 申购限额与基金档案只在限购页展示，持仓页不关心这些。 */
    showFundFacts: Boolean = true,
    /** 我的持仓，只在持仓页传入。 */
    holding: Holding? = null,
    onBuy: () -> Unit = {},
    onSell: () -> Unit = {},
    onEditHolding: () -> Unit = {},
    onRemoveHolding: () -> Unit = {}
) {
    val fund = state.fund
    val extra = (state as? DetailState.Ready)?.extra
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空持仓？") },
            text = { Text("这只基金的份额、成本与买卖记录都会删掉，不能恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onRemoveHolding()
                    }
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "基金详情",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
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
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = fund.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = fund.subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (showLimitBadge) {
                    Spacer(Modifier.width(12.dp))
                    LimitBadge(fund = fund)
                }
            }

            if (state is DetailState.Loading) {
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(20.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Column(modifier = Modifier.padding(vertical = 16.dp)) {
                    Row {
                        DetailMetric(
                            label = "单位净值",
                            value = navText(fund.nav),
                            modifier = Modifier.weight(1f)
                        )
                        DetailMetric(
                            label = "累计净值",
                            value = if (fund.accNav > 0) navText(fund.accNav) else "—",
                            modifier = Modifier.weight(1f)
                        )
                        DetailMetric(
                            label = "日涨跌",
                            value = growthText(fund.dayGrowth),
                            valueColor = growthColor(fund.dayGrowth),
                            modifier = Modifier.weight(1f)
                        )
                        DetailMetric(
                            label = "风险",
                            value = riskText(fund.riskLevel),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (fund.navDate.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "净值日期 ${fund.navDate}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }

            if (!showFundFacts) {
                MyHoldingSection(
                    holding = holding,
                    nav = estimate?.nav ?: fund.nav,
                    percent = estimate?.takeIf { it.isRealtime }?.percent,
                    onBuy = onBuy,
                    onSell = onSell,
                    onEdit = onEditHolding,
                    onRemove = { confirmClear = true }
                )
            }

            if (estimate != null) {
                SectionTitle(if (estimate.isRealtime) "今日估算" else "最新净值")
                DetailCard {
                    DetailRow(
                        estimate.valueLabel,
                        growthText(estimate.percent),
                        valueColor = growthColor(estimate.percent)
                    )
                    DetailRow(
                        "估算净值",
                        estimate.nav?.let { navText(it) } ?: "—"
                    )
                    DetailRow("估算依据", estimate.sourceLabel)
                    if (estimate.basis.isNotBlank() && estimate.source != EstimateSource.NAV) {
                        DetailRow(
                            label = when (estimate.source) {
                                EstimateSource.INDEX -> "跟踪指数"
                                EstimateSource.ETF -> "目标 ETF"
                                EstimateSource.HOLDINGS -> "重仓样本"
                                EstimateSource.NAV -> "净值日期"
                            },
                            value = estimate.basis
                        )
                    }
                    if (estimate.source == EstimateSource.INDEX && estimate.etfCode.isNotBlank()) {
                        // 指数是估算口径，ETF 摆在这里只作对照：两者的差就是
                        // 场内溢价与费用，看久了心里有数。
                        DetailRow(
                            label = "目标 ETF",
                            value = estimate.etfLabel + (
                                estimate.etfPercent?.let { " · " + growthText(it) } ?: " · 无行情"
                                ) + "（参考）",
                            valueColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (estimate.quoteTime.isNotBlank()) {
                        DetailRow("行情时间", estimate.quoteTime)
                    }
                    if (estimate.source == EstimateSource.HOLDINGS && estimate.coverage > 0) {
                        DetailRow(
                            "重仓覆盖",
                            String.format(Locale.US, "%.1f%%", estimate.coverage)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = estimate.sourceLabel + "，实际净值以基金公司公布为准",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (showFundFacts) {
                SectionTitle("申购限额")
                DetailCard {
                    DetailRow("申购状态", fund.buyStatus.ifBlank { "—" })
                    DetailRow("单日限额", fund.limitDetailText)
                    DetailRow("起购金额", fund.minBuy?.let { money(it) } ?: "—")
                    DetailRow("赎回状态", fund.sellStatus.ifBlank { "—" })
                    DetailRow(
                        "申购费率",
                        buildString {
                            append(fund.rate.ifBlank { "—" })
                            if (fund.sourceRate.isNotBlank() && fund.sourceRate != fund.rate) {
                                append("（原 ")
                                append(fund.sourceRate)
                                append("）")
                            }
                        }
                    )
                }

                if (extra != null &&
                    (extra.company.isNotBlank() || extra.manager.isNotBlank() ||
                        extra.established.isNotBlank() || extra.scale.isNotBlank())
                ) {
                    SectionTitle("基金档案")
                    DetailCard {
                        if (extra.company.isNotBlank()) DetailRow("基金公司", extra.company)
                        if (extra.manager.isNotBlank()) DetailRow("基金经理", extra.manager)
                        if (extra.established.isNotBlank()) DetailRow("成立日期", extra.established)
                        if (extra.scale.isNotBlank()) DetailRow("资产规模", extra.scale)
                    }
                }
            }

            SectionTitle("跟踪标的")
            DetailCard {
                DetailRow("基金标注", fund.trackIndex.ifBlank { "—" })
                DetailRow(
                    label = "行情对应",
                    value = when {
                        trackOverride != null -> "${trackOverride.name}（${trackOverride.code}）"
                        trackedIndex != null -> "${trackedIndex.name}（${trackedIndex.code}）"
                        else -> "按名称自动识别"
                    },
                    valueColor = if (trackOverride != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    trackOverride != null ->
                        "指数页里这只基金的标的已经锁定在上面这个指数，同名不同源的指数不会再串。"
                    fund.trackIndex.isNotBlank() ->
                        "基金标注的名字和行情里的指数简称常常差几个字，自动对不上时点下面的按钮手动挑一个。"
                    else ->
                        "这只基金没有标注跟踪标的，可以手动指定一个指数作为参照。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row {
                if (trackedIndex != null && onOpenKline != null) {
                    TextButton(
                        onClick = onOpenKline,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 4.dp,
                            vertical = 0.dp
                        )
                    ) {
                        Text("看这个指数的 K 线", style = MaterialTheme.typography.labelLarge)
                    }
                }
                TextButton(
                    onClick = onEditTrack,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 4.dp,
                        vertical = 0.dp
                    )
                ) {
                    Text(
                        text = if (trackOverride == null) "手动指定指数" else "更换指定指数",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                if (trackOverride != null) {
                    TextButton(
                        onClick = onClearTrack,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp,
                            vertical = 0.dp
                        )
                    ) {
                        Text(
                            text = "恢复自动识别",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    val url = "https://fund.eastmoney.com/${fund.code}.html"
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("在天天基金查看详情")
            }

            Spacer(Modifier.height(8.dp))

            TextButton(
                onClick = { onRemove(fund.code) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(6.dp))
                Text(removeLabel, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
    )
}

@Composable
private fun DetailCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            content()
        }
    }
}

@Composable
private fun DetailMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = valueColor,
            maxLines = 1
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            modifier = Modifier.weight(1f)
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
}

/** 持仓页的「我的持仓」：份额、成本、市值、盈亏，以及买入 / 卖出入口。 */
@Composable
private fun MyHoldingSection(
    holding: Holding?,
    nav: Double,
    percent: Double?,
    onBuy: () -> Unit,
    onSell: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit
) {
    SectionTitle("我的持仓")

    if (holding == null || holding.isEmpty) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
            )
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    text = "填上持有的份额和成本，这里就会按最新净值算市值和盈亏，之后每一笔买入卖出都会自动跟着调整。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onEdit,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(40.dp)
                    ) {
                        Text("填写持仓")
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onBuy) { Text("记一笔买入") }
                }
            }
        }
        return
    }

    val value = holding.marketValue(nav)
    val profit = holding.profit(nav)
    val profitPercent = holding.profitPercent(nav)
    val today = percent?.let { holding.todayProfit(nav, it) }

    DetailCard {
        DetailRow("持有份额", sharesText(holding.shares) + " 份")
        DetailRow("持仓成本", amountText(holding.cost) + " 元")
        DetailRow("成本单价", navText(holding.avgCost))
        DetailRow("最新市值", amountText(value) + " 元")
        DetailRow(
            label = "浮动盈亏",
            value = signedAmountText(profit) +
                (profitPercent?.let { "（" + growthText(it) + "）" } ?: ""),
            valueColor = growthColor(profit)
        )
        if (today != null) {
            DetailRow("今日盈亏", signedAmountText(today), valueColor = growthColor(today))
        }
        if (abs(holding.realized) > 0.005) {
            DetailRow(
                "已实现盈亏",
                signedAmountText(holding.realized),
                valueColor = growthColor(holding.realized)
            )
        }
    }

    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = onBuy,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 4.dp,
                vertical = 0.dp
            )
        ) { Text("买入") }
        TextButton(
            onClick = onSell,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp,
                vertical = 0.dp
            )
        ) { Text("卖出") }
        TextButton(
            onClick = onEdit,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp,
                vertical = 0.dp
            )
        ) { Text("修改持仓") }
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = onRemove,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 4.dp,
                vertical = 0.dp
            )
        ) {
            Text(
                text = "清空持仓",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (holding.trades.isNotEmpty()) {
        SectionTitle("交易记录")
        DetailCard {
            holding.trades.take(6).forEach { trade ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = tradeText(trade.at),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(74.dp)
                    )
                    Text(
                        text = if (trade.kind == TradeKind.BUY) "买入" else "卖出",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (trade.kind == TradeKind.BUY) growthColor(1.0)
                        else growthColor(-1.0),
                        modifier = Modifier.width(34.dp)
                    )
                    Text(
                        text = sharesText(trade.shares) + " 份",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "@" + navText(trade.nav),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            }
        }
        if (holding.trades.size > 6) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "只显示最近 6 笔，共 ${holding.trades.size} 笔",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun tradeText(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
