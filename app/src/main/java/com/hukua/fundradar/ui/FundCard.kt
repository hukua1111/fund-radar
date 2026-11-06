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
import com.hukua.fundradar.data.Fund
import com.hukua.fundradar.data.LimitType
import com.hukua.fundradar.data.growthText
import com.hukua.fundradar.data.navText
import com.hukua.fundradar.ui.theme.LocalMarketColors

/**
 * One fund, one overview card. Only what you scan for lives here — name, code,
 * NAV and today's move; every other number is one tap away on [FundDetailScreen].
 *
 * ```
 * ┌──────────────────────────────────────────────┐
 * │ 招商中证白酒指数(LOF)A              [限大额 50万] │
 * │ 161725 · 指数型-股票                            │
 * │ ──────────────────────────────────────────── │
 * │ 0.5676              +5.58%                ›   │
 * │ 单位净值             日涨跌                    │
 * └──────────────────────────────────────────────┘
 * ```
 */
@Composable
fun FundCard(
    fund: Fund,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                Spacer(Modifier.width(10.dp))
                LimitBadge(fund = fund)
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
                ValueCell(
                    label = "日涨跌",
                    value = growthText(fund.dayGrowth),
                    valueColor = growthColor(fund.dayGrowth),
                    modifier = Modifier.weight(1f)
                )
                // material-icons-core has no chevron; drawing two strokes keeps
                // the APK from pulling in the whole extended icon set.
                Chevron(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                )
            }
        }
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
