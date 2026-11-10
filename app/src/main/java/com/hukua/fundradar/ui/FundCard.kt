package com.hukua.fundradar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hukua.fundradar.data.Estimate
import com.hukua.fundradar.data.Fund
import com.hukua.fundradar.data.Holding
import com.hukua.fundradar.data.LimitType
import com.hukua.fundradar.data.amountText
import com.hukua.fundradar.data.growthText
import com.hukua.fundradar.data.navText
import com.hukua.fundradar.data.signedAmountText
import com.hukua.fundradar.ui.theme.LocalMarketColors

/**
 * One fund, one card. Three shapes:
 *
 * Portfolio, compact (the default on the holdings page — name, estimate and
 * one line of position figures, everything else lives in the detail screen):
 * ```
 * ┌──────────────────────────────────────────────┐
 * │ 国泰中证煤炭ETF联接C                -3.21%   › │
 * │ 008280 · 净值 2.1532              估算涨跌    │
 * │ 市值 2,210.52   今日 +10.16   累计 -193.54   │
 * └──────────────────────────────────────────────┘
 * ```
 *
 * Portfolio, full: adds the value cell row and the estimation footnote.
 *
 * Limit watchlist adds the badge and swaps the estimate for the reported
 * daily change.
 */
@Composable
fun FundCard(
    fund: Fund,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    estimate: Estimate? = null,
    showLimitBadge: Boolean = true,
    /** 我的持仓，持仓页才传。 */
    holding: Holding? = null,
    /** 精简形态：只留基金名、估算涨跌和一行持仓数字。 */
    compact: Boolean = false
) {
    if (compact) {
        CompactFundCard(
            fund = fund,
            onClick = onClick,
            modifier = modifier,
            estimate = estimate,
            holding = holding
        )
        return
    }

    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = fund.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = fund.subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (showLimitBadge) {
                    Spacer(Modifier.width(10.dp))
                    LimitBadge(fund = fund)
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                ValueCell(
                    label = "单位净值",
                    value = navText(fund.nav),
                    modifier = Modifier.weight(1f)
                )
                if (showLimitBadge) {
                    ValueCell(
                        label = "日涨跌",
                        value = growthText(fund.dayGrowth),
                        valueColor = growthColor(fund.dayGrowth),
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    ValueCell(
                        label = estimate?.valueLabel ?: "估算涨跌",
                        value = estimate?.let { growthText(it.percent) } ?: "—",
                        valueColor = estimate?.let { growthColor(it.percent) }
                            ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
                // material-icons-core has no chevron; drawing two strokes keeps
                // the APK from pulling in the whole extended icon set.
                Chevron(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                )
            }

            if (!showLimitBadge && holding != null && !holding.isEmpty) {
                Spacer(Modifier.height(10.dp))
                HoldingStrip(
                    holding = holding,
                    nav = estimate?.nav ?: fund.nav,
                    percent = estimate?.takeIf { it.isRealtime }?.percent
                )
            }

            if (!showLimitBadge) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = estimate?.footnote ?: "正在获取行情…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 精简卡片：基金名 + 估算涨跌 + 一行持仓数字。
 * 净值、类型、估算依据、持有份额这些详情页里都有，列表里不再重复。
 */
@Composable
private fun CompactFundCard(
    fund: Fund,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    estimate: Estimate? = null,
    holding: Holding? = null
) {
    val percent = estimate?.percent
    val livePercent = estimate?.takeIf { it.isRealtime }?.percent

    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = fund.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = fund.code + " · 净值 " + navText(fund.nav),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = percent?.let { growthText(it) } ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = percent?.let { growthColor(it) }
                            ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Text(
                        text = estimate?.valueLabel ?: "估算涨跌",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.width(4.dp))
                Chevron(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                )
            }

            if (holding != null && !holding.isEmpty) {
                val nav = estimate?.nav ?: fund.nav
                val today = livePercent?.let { holding.todayProfit(nav, it) }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InlineMetric(
                        label = "市值",
                        value = amountText(holding.marketValue(nav)),
                        modifier = Modifier.weight(1f)
                    )
                    InlineMetric(
                        label = "今日",
                        value = today?.let { signedAmountText(it) } ?: "—",
                        color = today?.let { growthColor(it) },
                        modifier = Modifier.weight(1f)
                    )
                    InlineMetric(
                        label = "累计",
                        value = signedAmountText(holding.profit(nav)),
                        color = growthColor(holding.profit(nav)),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** 一行里的小指标：浅色标签 + 半粗数字，三个等分排开。 */
@Composable
private fun InlineMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    color: Color? = null
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = color ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 持仓条：市值 / 今日盈亏 / 累计盈亏。只在填过份额的基金上出现，三列数字，
 * 不额外占太多高度。
 */
@Composable
private fun HoldingStrip(holding: Holding, nav: Double, percent: Double?) {
    val value = holding.marketValue(nav)
    val profit = holding.profit(nav)
    val profitPercent = holding.profitPercent(nav)
    val today = percent?.let { holding.todayProfit(nav, it) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f)
    ) {
        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            HoldingCell(
                label = "持有市值",
                value = amountText(value),
                modifier = Modifier.weight(1f)
            )
            HoldingCell(
                label = "今日盈亏",
                value = today?.let { signedAmountText(it) } ?: "—",
                color = today?.let { growthColor(it) },
                modifier = Modifier.weight(1f)
            )
            HoldingCell(
                label = "累计盈亏",
                value = signedAmountText(profit) +
                    (profitPercent?.let { "  " + growthText(it) } ?: ""),
                color = growthColor(profit),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun HoldingCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    color: Color? = null
) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = color ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/** Top-right pill describing the purchase limit of the fund. */
@Composable
fun LimitBadge(fund: Fund, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val container: Color
    val content: Color
    val icon: ImageVector

    when (fund.limitType) {
        LimitType.SUSPENDED -> {
            container = scheme.errorContainer
            content = scheme.onErrorContainer
            icon = Icons.Filled.Warning
        }
        LimitType.LIMITED -> {
            container = scheme.tertiaryContainer
            content = scheme.onTertiaryContainer
            icon = Icons.Filled.Lock
        }
        LimitType.OPEN -> {
            container = scheme.secondaryContainer
            content = scheme.onSecondaryContainer
            icon = Icons.Filled.CheckCircle
        }
        LimitType.UNKNOWN -> {
            container = scheme.surfaceContainerHighest
            content = scheme.onSurfaceVariant
            icon = Icons.Filled.Info
        }
    }

    Surface(
        modifier = modifier,
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(50)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(13.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = fund.limitBadgeText,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ValueCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun Chevron(color: Color) {
    Canvas(modifier = Modifier.size(14.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.32f, size.height * 0.18f)
            lineTo(size.width * 0.68f, size.height * 0.5f)
            lineTo(size.width * 0.32f, size.height * 0.82f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2f.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
fun growthColor(value: Double): Color {
    val market = LocalMarketColors.current
    return when {
        value > 0 -> market.up
        value < 0 -> market.down
        else -> market.flat
    }
}
