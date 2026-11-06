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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hukua.fundradar.data.AiAdvisor
import com.hukua.fundradar.data.AiConfig
import com.hukua.fundradar.data.BarkNotifier
import com.hukua.fundradar.data.SettingsStore
import com.hukua.fundradar.data.SourceCheck
import com.hukua.fundradar.data.SourceCheckRow
import com.hukua.fundradar.work.DailyScheduler
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var config by remember { mutableStateOf(store.load()) }
    var testing by remember { mutableStateOf(false) }
    var pushing by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var checks by remember { mutableStateOf<List<SourceCheckRow>>(emptyList()) }

    SettingsContent(
        config = config,
        snackbarHostState = snackbar,
        testing = testing,
        pushing = pushing,
        checks = checks,
        checking = checking,
        onBack = onBack,
        onConfigChange = { updated ->
            val scheduleChanged = updated.dailyEnabled != config.dailyEnabled ||
                updated.hour != config.hour ||
                updated.minute != config.minute
            config = updated
            store.save(updated)
            if (scheduleChanged) DailyScheduler.apply(context, updated)
        },
        onCheck = {
            checking = true
            scope.launch {
                checks = runCatching { SourceCheck.run(context) }.getOrDefault(emptyList())
                checking = false
            }
        },
        onTest = {
            testing = true
            store.save(config)
            scope.launch {
                runCatching { AiAdvisor.testConnection(config) }
                    .onSuccess { snackbar.showSnackbar("连接成功：$it") }
                    .onFailure { snackbar.showSnackbar("连接失败：${it.message ?: "未知错误"}") }
                testing = false
            }
        },
        onPush = {
            pushing = true
            store.save(config)
            scope.launch {
                val ok = runCatching {
                    BarkNotifier.push(config, "基金雷达", "这是一条测试推送，收到说明配置正确")
                }.getOrDefault(false)
                snackbar.showSnackbar(if (ok) "推送已发送" else "推送失败，检查地址与网络")
                pushing = false
            }
        },
        onSave = {
            store.save(config)
            DailyScheduler.apply(context, config)
            scope.launch { snackbar.showSnackbar("已保存") }
        }
    )
}

/** Stateless settings screen, also used by the JVM screenshot tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    config: AiConfig,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    testing: Boolean = false,
    pushing: Boolean = false,
    checks: List<SourceCheckRow> = emptyList(),
    checking: Boolean = false,
    onBack: () -> Unit = {},
    onConfigChange: (AiConfig) -> Unit = {},
    onTest: () -> Unit = {},
    onPush: () -> Unit = {},
    onCheck: () -> Unit = {},
    onSave: () -> Unit = {}
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
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
                .padding(bottom = 32.dp)
        ) {
            SectionCard(
                title = "AI 接口",
                subtitle = "兼容 OpenAI 格式：DeepSeek、OpenAI、通义、本地 Ollama 都行"
            ) {
                OutlinedTextField(
                    value = config.baseUrl,
                    onValueChange = { onConfigChange(config.copy(baseUrl = it)) },
                    label = { Text("接口地址") },
                    placeholder = { Text("https://api.deepseek.com/v1") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = config.model,
                    onValueChange = { onConfigChange(config.copy(model = it)) },
                    label = { Text("模型") },
                    placeholder = { Text("deepseek-chat") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = config.apiKey,
                    onValueChange = { onConfigChange(config.copy(apiKey = it)) },
                    label = { Text("API Key") },
                    placeholder = { Text("sk-…") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                TextButton(enabled = !testing && config.configured, onClick = onTest) {
                    if (testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("测试连接")
                }
            }

            Spacer(Modifier.height(14.dp))

            SectionCard(
                title = "估值数据源自检",
                subtitle = "盘中估算要连过「基金持仓 → 交易所行情 → 标的指数」三关，" +
                    "这里把每一步的真实返回摊开，出问题一眼看出卡在哪"
            ) {
                TextButton(enabled = !checking, onClick = onCheck) {
                    if (checking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (checks.isEmpty()) "开始自检" else "重新自检")
                }
                checks.forEach { row ->
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = if (row.ok) "✓" else "✗",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (row.ok) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = row.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = row.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            SectionCard(
                title = "Bark 推送",
                subtitle = "填 https://api.day.app/你的设备Key，判断结果会推到 iPhone"
            ) {
                OutlinedTextField(
                    value = config.barkUrl,
                    onValueChange = { onConfigChange(config.copy(barkUrl = it)) },
                    label = { Text("Bark 地址") },
                    placeholder = { Text("https://api.day.app/xxxxxxxx") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                TextButton(enabled = !pushing && config.barkConfigured, onClick = onPush) {
                    if (pushing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("发送测试推送")
                }
            }

            Spacer(Modifier.height(14.dp))

            SectionCard(
                title = "每日自动判断",
                subtitle = "到点抓取自选基金行情，调用一次 AI，结论推送到 Bark"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("开启定时判断", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = if (config.dailyEnabled) "每天 ${config.timeText()} 运行" else "已关闭",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.dailyEnabled,
                        onCheckedChange = { onConfigChange(config.copy(dailyEnabled = it)) }
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = config.hour.toString(),
                        onValueChange = { text ->
                            text.toIntOrNull()?.let { value ->
                                if (value in 0..23) onConfigChange(config.copy(hour = value))
                            }
                        },
                        label = { Text("时") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.width(96.dp)
                    )
                    Text(":", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = config.minute.toString(),
                        onValueChange = { text ->
                            text.toIntOrNull()?.let { value ->
                                if (value in 0..59) onConfigChange(config.copy(minute = value))
                            }
                        },
                        label = { Text("分") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.width(96.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onConfigChange(config.copy(hour = 14, minute = 35)) }) {
                        Text("14:35")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "省电模式下系统可能延后执行，误差一般不超过 15 分钟；" +
                        "重启或强制停止后，下次打开应用会自动恢复。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = onSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("保存")
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = "Key 只保存在本机，请求直接发往你填写的 AI 地址，不经过第三方服务器。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}
