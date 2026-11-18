package com.hukua.fundradar.ui

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.unit.dp
import com.hukua.fundradar.data.DcaFrequency
import com.hukua.fundradar.data.DcaPlan
import com.hukua.fundradar.data.WEEK_LABELS
import com.hukua.fundradar.data.dayText
import com.hukua.fundradar.data.parseDayText
import java.util.Calendar
import java.util.Locale

/**
 * 定投计划面板：节奏、金额、起始日、止盈线。
 *
 * 频率支持**每个交易日 / 每周 / 每月**三种。选了哪个，下面就换成对应的「周几」
 * 或「几号」；起始日精确到天，因为按日投的时候「哪个月」根本不够用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DcaPlanSheet(
    indexName: String,
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
            indexName = indexName,
            initial = initial,
            onSave = onSave,
            onDismiss = onDismiss
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DcaPlanSheetContent(
    indexName: String,
    initial: DcaPlan,
    onSave: (DcaPlan) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val seed = if (initial.enabled) initial else DcaPlan.defaultFor()

    var amountText by remember { mutableStateOf(if (initial.amount > 0) plain(initial.amount) else "1000") }
    var frequency by remember { mutableStateOf(seed.frequency) }
    var dayOfWeek by remember { mutableStateOf(seed.weekday) }
    var dayOfMonth by remember { mutableStateOf(seed.monthDay) }
    var startDate by remember { mutableStateOf(seed.startDate.ifBlank { todayText() }) }
    var targetText by remember { mutableStateOf(plain(seed.target)) }
    var note by remember { mutableStateOf(initial.note) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
            .verticalScroll(rememberScrollState())
            .imePadding()
    ) {
        Text(
            text = "定投计划",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = indexName.ifBlank { "这个指数" } + " · 定的是节奏，不是流水；" +
                "已投几期、赚了多少会按指数日线自动回放。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(18.dp))
        Text("频率", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val options = DcaFrequency.entries
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = frequency == option,
                    onClick = { frequency = option },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    icon = {},
                    label = { Text(option.short, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = when (frequency) {
                DcaFrequency.DAILY -> "每个交易日扣一次（周末与节假日顺延到下一个交易日），适合小额高频摊成本。"
                DcaFrequency.WEEKLY -> "每周固定一天扣一次，比月投更细，又不用天天盯。"
                DcaFrequency.MONTHLY -> "每月固定一天扣一次，最常见的工资日定投。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (frequency == DcaFrequency.WEEKLY) {
            Spacer(Modifier.height(14.dp))
            Text("每周几", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WEEK_LABELS.forEachIndexed { index, label ->
                    val value = index + 1
                    FilterChip(
                        selected = dayOfWeek == value,
                        onClick = { dayOfWeek = value },
                        label = { Text("周$label") }
                    )
                }
            }
        }

        if (frequency == DcaFrequency.MONTHLY) {
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = dayOfMonth.toString(),
                onValueChange = { text ->
                    val value = numeric(text).toIntOrNull()
                    if (value != null && value in 1..28) dayOfMonth = value
                    else if (text.isBlank()) dayOfMonth = 1
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("每月几号扣（1–28）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(14.dp)
            )
        }

        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = amountText,
            onValueChange = { amountText = numeric(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("每期金额（元）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            shape = RoundedCornerShape(14.dp),
            supportingText = {
                Text(
                    when (frequency) {
                        DcaFrequency.DAILY -> "按一年 244 个交易日算，约 " +
                            moneyOf(amountText.toDoubleOrNull(), 244) + " / 年"
                        DcaFrequency.WEEKLY -> "按 52 周算，约 " +
                            moneyOf(amountText.toDoubleOrNull(), 52) + " / 年"
                        DcaFrequency.MONTHLY -> "按 12 个月算，约 " +
                            moneyOf(amountText.toDoubleOrNull(), 12) + " / 年"
                    }
                )
            }
        )

        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = startDate,
            onValueChange = { startDate = it.trim().take(10) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("起始日（yyyy-MM-dd）") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            isError = parseDayText(startDate) == null,
            supportingText = {
                Text(
                    if (parseDayText(startDate) == null) "格式不对，写成 2025-03-01 这样"
                    else "从这一天起，按期数回放；改一天，整条记录立刻重算"
                )
            }
        )

        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = targetText,
            onValueChange = { targetText = numeric(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("止盈线（%）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            shape = RoundedCornerShape(14.dp),
            supportingText = { Text("计划收益达到这条线时，会提示分批落袋") }
        )

        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(120) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("补充说明（可选，会一起发给 AI）") },
            minLines = 2,
            shape = RoundedCornerShape(14.dp),
            placeholder = { Text("比如：场外另有一笔 2 万的底仓，成本 1.05") }
        )

        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = error.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                val amount = amountText.toDoubleOrNull() ?: 0.0
                if (amount <= 0) {
                    error = "每期金额要大于 0"
                    return@Button
                }
                if (parseDayText(startDate) == null) {
                    error = "起始日格式不对"
                    return@Button
                }
                onSave(
                    DcaPlan(
                        amount = amount,
                        frequency = frequency,
                        dayOfWeek = dayOfWeek,
                        dayOfMonth = dayOfMonth,
                        startDate = startDate,
                        targetProfitPct = targetText.toDoubleOrNull() ?: 30.0,
                        note = note.trim()
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("保存计划")
        }
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text("取消")
        }
    }
}

private fun todayText(): String = dayText(Calendar.getInstance())

/** 只留数字和一个小数点。 */
private fun numeric(text: String): String {
    val builder = StringBuilder()
    var dot = false
    text.forEach { c ->
        when {
            c.isDigit() -> builder.append(c)
            (c == '.' || c == '。') && !dot -> {
                builder.append('.')
                dot = true
            }
        }
    }
    return builder.toString()
}

private fun plain(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else String.format(Locale.US, "%.2f", value)

private fun moneyOf(amount: Double?, periods: Int): String {
    if (amount == null || amount <= 0) return "—"
    return String.format(Locale.US, "%,.0f 元", amount * periods)
}
