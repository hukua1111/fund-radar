package com.hukua.fundradar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hukua.fundradar.data.DcaPlan
import java.util.Calendar
import java.util.Locale

/**
 * 定投计划面板：每月投多少、几号扣、从哪个月开始、赚到多少止盈。
 *
 * 只收这四个数 —— 具体买了多少份额由「我的持仓」负责，两边分开才不会出现
 * 「计划说投了 12 期、持仓却只有 8 笔」这种对不上的账。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DcaPlanSheet(
    fundName: String,
    initial: DcaPlan,
    onSave: (DcaPlan) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        DcaPlanSheetContent(
            fundName = fundName,
            initial = initial,
            onSave = onSave
        )
    }
}

@Composable
fun DcaPlanSheetContent(
    fundName: String,
    initial: DcaPlan,
    onSave: (DcaPlan) -> Unit,
    modifier: Modifier = Modifier
) {
    var amount by remember {
        mutableStateOf(if (initial.monthlyAmount > 0) plain(initial.monthlyAmount) else "")
    }
    var day by remember { mutableStateOf(initial.dayOfMonth.toString()) }
    var startMonth by remember {
        mutableStateOf(initial.startMonth.ifBlank { currentMonth() })
    }
    var target by remember {
        mutableStateOf(plain(if (initial.targetProfitPct > 0) initial.targetProfitPct else 30.0))
    }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp)
    ) {
        Text(
            text = "定投计划",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = fundName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = amount,
            onValueChange = { amount = numeric(it); error = null },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("每期金额（元）") },
            placeholder = { Text("如 1000") },
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        )

        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = day,
                onValueChange = { day = numeric(it).take(2); error = null },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("扣款日") },
                placeholder = { Text("1-28") },
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Spacer(Modifier.width(10.dp))
            OutlinedTextField(
                value = startMonth,
                onValueChange = { startMonth = it.take(7); error = null },
                modifier = Modifier.weight(1.4f),
                singleLine = true,
                label = { Text("起始月份") },
                placeholder = { Text("2025-03") },
                shape = RoundedCornerShape(14.dp)
            )
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = target,
            onValueChange = { target = numeric(it); error = null },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("止盈线（%）") },
            placeholder = { Text("如 30") },
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = "扣款日固定 1–28，避开月末；起始月份填「年-月」。达到止盈线时，列表和详情都会提醒分批落袋。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = error.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(18.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TextButton(
                onClick = {
                    // 金额清空即删除计划：不投了就说清楚，而不是留一个 0 元的空壳。
                    onSave(DcaPlan())
                }
            ) {
                Text("删除计划", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    val amountValue = amount.toDoubleOrNull() ?: 0.0
                    val dayValue = day.toIntOrNull() ?: 0
                    val targetValue = target.toDoubleOrNull() ?: 0.0
                    when {
                        amountValue <= 0 -> error = "每期金额要大于 0"
                        dayValue !in 1..28 -> error = "扣款日填 1–28 之间的整数"
                        !MONTH_PATTERN.matches(startMonth) -> error = "起始月份按 2025-03 的格式填"
                        targetValue <= 0 -> error = "止盈线要大于 0"
                        else -> onSave(
                            DcaPlan(
                                monthlyAmount = amountValue,
                                day = dayValue,
                                startMonth = startMonth,
                                targetProfitPct = targetValue
                            )
                        )
                    }
                }
            ) {
                Text("保存")
            }
        }
    }
}

private val MONTH_PATTERN = Regex("^\\d{4}-(0[1-9]|1[0-2])$")

private fun currentMonth(): String {
    val calendar = Calendar.getInstance()
    return String.format(
        Locale.US,
        "%04d-%02d",
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH) + 1
    )
}

/** 小数输入：只留数字和一个小数点。 */
private fun numeric(text: String): String {
    val filtered = text.filter { it.isDigit() || it == '.' }
    val first = filtered.indexOf('.')
    return if (first < 0) {
        filtered
    } else {
        filtered.substring(0, first + 1) + filtered.substring(first + 1).replace(".", "")
    }
}

/** 1000.0 → "1000"，30.5 → "30.5"。 */
private fun plain(value: Double): String {
    if (value <= 0) return ""
    val rounded = String.format(Locale.US, "%.2f", value)
    return rounded.trimEnd('0').trimEnd('.')
}
