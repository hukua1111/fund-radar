package com.hukua.fundradar.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hukua.fundradar.data.Fund
import com.hukua.fundradar.data.growthText
import com.hukua.fundradar.data.money
import com.hukua.fundradar.data.navText
import com.hukua.fundradar.data.riskText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FundDetailSheet(
    state: DetailState,
    onDismiss: () -> Unit,
    onRemove: (String) -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val fund = state.fund
    val extra = (state as? DetailState.Ready)?.extra
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = fund.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = fund.subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                LimitBadge(fund = fund)
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
                Row(modifier = Modifier.padding(vertical = 16.dp)) {
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
            }

            SectionTitle("申购限额")
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                )
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
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
                    DetailRow("净值日期", fund.navDate.ifBlank { "—" })
                }
            }

            if (extra != null && (extra.company.isNotBlank() || extra.manager.isNotBlank())) {
                SectionTitle("基金概况")
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                    )
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        if (extra.company.isNotBlank()) DetailRow("基金公司", extra.company)
                        if (extra.manager.isNotBlank()) DetailRow("基金经理", extra.manager)
                        if (extra.established.isNotBlank()) DetailRow("成立日期", extra.established)
                        if (extra.scale.isNotBlank()) DetailRow("资产规模", extra.scale)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

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
                Text("从自选移除", color = MaterialTheme.colorScheme.error)
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
private fun DetailRow(label: String, value: String) {
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
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
}
