package com.hukua.fundradar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hukua.fundradar.data.Holding
import com.hukua.fundradar.data.amountText
import com.hukua.fundradar.data.sharesText
import com.hukua.fundradar.data.signedAmountText
import com.hukua.fundradar.ui.theme.LocalMarketColors
import java.util.Locale

/** 「我的持仓」弹层：买入 / 卖出 / 直接改份额，三种用法共用一个面板。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoldingSheet(
    state: HoldingSheetState,
    onModeChange: (HoldingMode) -> Unit,
    onSharesChange: (String) -> Unit,
    onCostChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onNavChange: (String) -> Unit,
    onFillAll: () -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    onCostInputChange: (CostInput) -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        HoldingSheetContent(
            state = state,
            onModeChange = onModeChange,
            onSharesChange = onSharesChange,
            onCostChange = onCostChange,
            onAmountChange = onAmountChange,
            onNavChange = onNavChange,
            onFillAll = onFillAll,
            onSubmit = onSubmit,
            onCostInputChange = onCostInputChange
        )
    }
}

/** 面板正文，抽出来方便截图测试单独渲染。 */
@Composable
fun HoldingSheetContent(
    state: HoldingSheetState,
    onModeChange: (HoldingMode) -> Unit,
    onSharesChange: (String) -> Unit,
    onCostChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onNavChange: (String) -> Unit,
    onFillAll: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    onCostInputChange: (CostInput) -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp)
    ) {
        Text(
            text = "我的持仓",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = state.fundName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(12.dp))

        if (state.current.isEmpty) {
            Text(
                text = "还没填过持仓。先卖掉一笔就填买入，或者用「修改持仓」照基金 App 里的份额和成本直接填。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
        } else {
            HoldingSummary(current = state.current)
            Spacer(Modifier.height(12.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HoldingMode.entries.forEach { mode ->
                FilterChip(
                    selected = state.mode == mode,
                    onClick = { onModeChange(mode) },
                    label = { Text(mode.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        when (state.mode) {
            HoldingMode.BUY -> {
                NumberField(
                    label = "买入金额（元）",
                    value = state.amount,
                    onValueChange = onAmountChange,
                    placeholder = "1000"
                )
                Spacer(Modifier.height(10.dp))
                NumberField(
                    label = "成交净值",
                    value = state.nav,
                    onValueChange = onNavChange,
                    placeholder = "1.2345"
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "预计买入 " + sharesText(state.effectiveShares) + " 份 · 投入 " +
                        amountText(state.effectiveAmount) + " 元",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HoldingMode.SELL -> {
                NumberField(
                    label = "卖出份额",
                    value = state.shares,
                    onValueChange = onSharesChange,
                    placeholder = "1000.0000",
                    trailing = {
                        TextButton(onClick = onFillAll) {
                            Text("全部", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                )
                Spacer(Modifier.height(10.dp))
                NumberField(
                    label = "成交净值",
                    value = state.nav,
                    onValueChange = onNavChange,
                    placeholder = "1.2345"
                )
                Spacer(Modifier.height(10.dp))
                val proceeds = state.effectiveAmount
                val costOut = state.current.avgCost * state.sharesValue
                val realized = if (state.current.shares > 0) proceeds - costOut else 0.0
                Text(
                    text = "预计到账 " + amountText(proceeds) + " 元 · 实现盈亏 " +
                        signedAmountText(realized),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HoldingMode.EDIT -> {
                NumberField(
                    label = "持有份额",
                    value = state.shares,
                    onValueChange = onSharesChange,
                    placeholder = "1000.0000"
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CostInput.entries.forEach { input ->
                        FilterChip(
                            selected = state.costInput == input,
                            onClick = { onCostInputChange(input) },
                            modifier = Modifier.height(30.dp),
                            label = {
                                Text(input.label, style = MaterialTheme.typography.labelSmall)
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            border = null
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                NumberField(
                    label = state.costInput.fieldLabel,
                    value = state.cost,
                    onValueChange = onCostChange,
                    placeholder = if (state.costInput == CostInput.PRICE) "2.2795" else "1602.42"
                )
                Spacer(Modifier.height(10.dp))
                EditPreview(state)
                if (state.looksLikeMarketValue) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "这个金额和「份额 × 最新净值」几乎一样，像是持有金额（市值）而不是成本。" +
                            "照基金 App 填「持仓成本价」更准。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        if (state.message != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = state.message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onSubmit,
            enabled = state.valid,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = when (state.mode) {
                    HoldingMode.BUY -> "确认买入"
                    HoldingMode.SELL -> "确认卖出"
                    HoldingMode.EDIT -> "保存持仓"
                }
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "按份额与成交净值记账，暂不扣申购赎回费；成本采用摊薄成本口径。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EditPreview(state: HoldingSheetState) {
    val market = LocalMarketColors.current
    val cost = state.costValue
    if (state.sharesValue <= 0 || cost <= 0) {
        Text(
            text = "照基金 App 填「持有份额」和「持仓成本价」即可；成本价就是买入时的单价，不是持有金额。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    val profit = state.profitPreview
    val percent = if (cost > 0 && profit != null) profit / cost * 100 else null
    val color = when {
        profit == null -> MaterialTheme.colorScheme.onSurfaceVariant
        profit > 0 -> market.up
        profit < 0 -> market.down
        else -> market.flat
    }

    Column {
        Text(
            text = "成本合计 " + amountText(cost) + " 元 · 成本单价 " +
                String.format(Locale.US, "%.4f", state.costPrice),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (profit != null && state.navValue > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "按净值 " + String.format(Locale.US, "%.4f", state.navValue) +
                    " 市值 " + amountText(state.sharesValue * state.navValue) + " 元 · 浮动盈亏 " +
                    signedAmountText(profit) +
                    (percent?.let { "（" + String.format(Locale.US, "%+.2f", it) + "%）" } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                color = color
            )
        }
    }
}

@Composable
private fun HoldingSummary(current: Holding) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row {
                SummaryCell("持有份额", sharesText(current.shares), Modifier.weight(1f))
                SummaryCell("摊薄成本", String.format(Locale.US, "%.4f", current.avgCost), Modifier.weight(1f))
                SummaryCell("已实现", signedAmountText(current.realized), Modifier.weight(1f))
            }
            HorizontalDivider(
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "成本合计 " + amountText(current.cost) + " 元",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SummaryCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
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
private fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    trailing: (@Composable () -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        trailingIcon = trailing
    )
}
