# 基金雷达 · FundRadar

一个基于 **Material Design 3（Material You）** 的安卓基金限额监控应用。

**只看你关心的基金**：主界面完全由自选列表驱动，不喜欢的基金不会出现。一只基金一张卡片：

```
┌────────────────────────────────────────────────┐
│ 招商中证白酒指数(LOF)A                 🔒 限大额 50万 │   ← 左上角：基金名称
│ 161725 · 指数型-股票                              │   ← 右上角：限额情况
│ ────────────────────────────────────────────── │
│ 单位净值      日涨跌      起购       费率           │   ← 下面一行：基金基本信息
│ 0.5676       +5.58%     10元       0.10%         │
└────────────────────────────────────────────────┘
```

| 亮色 | 暗色 |
| --- | --- |
| ![亮色](docs/screenshots/main-light.png) | ![暗色](docs/screenshots/main-dark.png) |

| 空列表（首次打开） | 添加基金 |
| --- | --- |
| ![空态](docs/screenshots/empty.png) | ![添加](docs/screenshots/add-sheet.png) |

**刷新后限购有变化的基金会单独置顶列出**：

![限购变化](docs/screenshots/changes.png)

**点开任意基金 → 「查看K线 · AI 波段判断」**：日 K + MA5/10/20/60，一个按钮让 AI 结合均线判断现在该不该做波段：

| K 线 + 均线 + AI 结论 | 还没配置 AI 接口 |
| --- | --- |
| ![K线](docs/screenshots/kline.png) | ![未配置](docs/screenshots/kline-no-ai.png) |

**设置页**：填 AI 地址 / 模型 / Key、Bark 推送地址、每日定时判断时间：

![设置](docs/screenshots/settings.png)

## 安装

`dist/FundRadar-1.1.0-release.apk`（已签名，支持 Android 7.0+ / API 24+）。
传输到手机后直接安装，首次打开需要联网。

`dist/FundRadar-1.1.0-debug.apk` 为调试版，便于 `adb logcat` 排查。

**从 1.0.0 升级**：`applicationId` 和签名证书都没变（`app/release.keystore`），
直接覆盖安装即可，自选列表和设置全部保留，不需要先卸载。

## 怎么用

1. 打开应用 → 点右上角 **+**（或空页面的「添加基金」按钮）；
2. 输入基金代码或名称（如 `161725`、`招商白酒`），从搜索结果里点 **+** 加入；
3. 也可以直接点空页面上的热门基金一键添加；
4. 卡片点开是详情，详情里有 **查看K线 · AI 波段判断**；
5. 详情底部有 **从自选移除**。

自选列表保存在本地，重装应用才会丢失。

### 开启 AI 判断（三步）

1. 右上角 **齿轮** → 设置页；
2. 填 AI 接口地址、模型、API Key（DeepSeek 默认 `https://api.deepseek.com/v1` + `deepseek-chat`，任何 OpenAI 兼容服务都行），点 **测试连接**；
3. 想收推送就再填 Bark 地址（`https://api.day.app/你的Key`），点 **发送测试推送** 确认能收到；
4. 需要每天自动判断，就打开 **开启定时判断**，时间默认 `14:35`。

## 功能

- **自选基金**：只显示你添加的基金，数量不限；搜索添加、一键移除，本地持久化。
- **限购变化提醒**：每次刷新都和上一次抓取结果比对，限购状态或单日额度变化的基金**单独置顶列出**（如「限大额 50万 → 暂停申购」）；收紧标红、放宽标绿，点一行直达详情，也可一键清除。
- **限额卡片**：每只基金一张 MD3 卡片，左上角基金名称、右上角限额徽标（限大额 / 暂停申购 / 不限购），下方一行展示单位净值、日涨跌、起购金额、申购费率。
- **限额金额**：不只显示「限大额」，还显示真实单日额度（如「限大额 50万」）。
- **顶部概览**：自选数量 + 限大额 / 暂停申购 / 不限购 三档统计 + 数据更新时间。
- **筛选**：全部 / 限大额 / 暂停申购 / 不限购，本地即时切换，不重复请求。
- **详情面板**：点击卡片弹出 MD3 BottomSheet —— 申购状态、单日限额、起购金额、赎回状态、费率（含原费率）、净值日期、基金公司、基金经理、成立日期、资产规模，可跳转天天基金，也可移除自选。
- **下拉刷新 + 骨架屏 + 离线缓存**：刷新失败自动回退到本地缓存并 Snackbar 提示。
- **深色模式**：完整明暗两套配色；`FundRadarTheme(dynamicColor = true)` 可开启 Android 12+ 动态取色。
- **涨跌配色**：遵循国内习惯，红涨绿跌。
- **K 线 + 均线**：日 K 蜡烛图叠加 MA5 / MA10 / MA20 / MA60，可切 60 / 120 / 250 日窗口（一次抓 250 天，切换不联网）。
- **AI 波段判断**：K 线界面一个按钮，把最新价、四条均线、相对 MA20 偏离度、近 5/20/60 日涨跌和最近 10 根 K 线发给 AI，返回「波段买入 / 波段卖出 / 观望 + 置信度 + 理由 + 风险」。AI 不常驻，点了才请求。
- **每日自动判断 + Bark 推送**：到点抓一遍自选行情，一次请求问完所有基金，结论推送到手机。

## K 线是怎么来的

| 基金类型 | 数据 | 说明 |
| --- | --- | --- |
| 场内 LOF / ETF（如 161725、510300） | 交易所日 K（真实开高低收 + 成交量） | `push2his.eastmoney.com` 日线接口 |
| 纯场外基金 | 每日单位净值序列 | 用「昨日净值 → 今日净值」画实体，没有上下影线 |

先按代码猜市场（`5`/`6` 开头试沪市，其余试深市），交易所没有这只代码就回退到净值序列，界面上会标明是「场内K线」还是「场外净值」。

均线是本地算的：`movingAverage()` 做前缀和滑窗，切窗口时只在已经抓下来的 250 根上切片，**不重复请求**。

## AI 波段判断怎么问的

提示词里只给数字，不给新闻，避免模型编故事：

```
基金：招商中证白酒指数(LOF)A（161725）
最新日期：2026-11-05
最新价格/净值：0.5926
均线：MA5=0.5812  MA10=0.5779  MA20=0.5803  MA60=0.6011
现价相对MA20：+2.10%
区间涨跌：近5日=+3.20%  近20日=-1.40%  近60日=-8.70%
最近10个交易日（日期 开高低收）：…
```

要求模型只回一个 JSON：

```json
{"action":"BUY|SELL|HOLD","confidence":0-100,"reason":"60字以内中文理由","risk":"30字以内风险提示"}
```

解析很宽容：带 ```json 代码块、前后夹着客套话、`action` 写成「买入 / 卖出 / 观望」都能认；
实在解析不出来就把模型原文当理由显示，`action` 记为「未判断」，不会崩。

请求走 `POST {base}/chat/completions`，`Authorization: Bearer <key>`，`temperature=0.2`，
读超时 90 秒（推理模型慢也够）。

## 每天 14:35 自动判断

用的是 WorkManager 的**一次性任务 + 自我续期**，不是 `PeriodicWorkRequest`（周期任务只能保证 15 分钟粒度，定不准 14:35）：

1. 打开开关时算出「距离下一个 14:35 还有多少毫秒」，`setInitialDelay` 入队，约束是「有网」；
2. 任务跑完（成功或失败）都会重新排下一天，所以只要应用不被卸载就一直在；
3. WorkManager 自己把任务写进数据库，**手机重启后自动恢复**；应用每次启动也会重新对一遍时间，防止被系统清掉；
4. 任务内部：抓自选每只基金 250 天 K 线 → **一次请求**让 AI 判断全部基金（最多 12 只，控制 token 和耗时）→ 一条 Bark 推送汇总：

```
基金雷达 · 波段判断 · 11-05 14:35
招商中证白酒指数(LOF)A：波段买入 72% · MA5上穿MA20，站上MA20
易方达消费行业股票：观望 40% · 均线纠缠，等方向
```

AI 没配置、自选为空、行情抓不到、接口报错，都会推一条对应提示，不会静默失败。

**关于准时**：Android 的省电策略（Doze、厂商后台限制）会让任务延后，一般误差在 15 分钟内；
想更准可以在系统设置里把这个应用加入「不受限制 / 允许后台活动」。基金净值本来就是收盘后才更新，
14:35 这个点判断的是**当天盘中位置**，不影响结论的有效性。

## 升级说明

- `applicationId` 保持 `com.hukua.fundradar`，签名继续用 `app/release.keystore`（证书 SHA-256 未变），
  所以 1.1.0 可以直接覆盖安装 1.0.0，自选和设置都留着；
- `versionCode` 1 → 2，`versionName` 1.0.0 → 1.1.0；
- 新增依赖只有 `androidx.work:work-runtime-ktx`，网络层依旧是 `HttpURLConnection` + `org.json`。

## 数据来源

公开页面（天天基金 / 东方财富），无需鉴权：

| 用途 | 来源 |
| --- | --- |
| 单只基金：名称 / 净值 / 涨跌 / 申购状态 / 限额 / 起购 / 类型 / 费率 / 风险 / 公司 / 经理 / 规模 | `fund.eastmoney.com/{code}.html` |
| 添加基金时的搜索 | `fund.eastmoney.com/Data/Fund_JJJZ_Data.aspx` |
| 场内 LOF / ETF 日 K 线 | `push2his.eastmoney.com/api/qt/stock/kline/get` |
| 场外基金历史净值 | `api.fund.eastmoney.com/f10/lsjz` |

> 数据仅供研究参考，不构成任何投资建议。接口为第三方公开页面，可能随时变更或限流。

### 为什么解析基金详情页

移动端 JSON 接口 `fundmobapi.eastmoney.com` 字段更全、体积更小（约 3 KB），但它对同一 IP 的连续请求
非常敏感，几十次之后就会返回 `ErrCode 61136403 网络繁忙`；对非浏览器 TLS 指纹的客户端（如 JVM）几乎总是直接拒绝。
基金详情页与列表页同源，实测稳定，一次请求就包含卡片和详情页需要的全部字段，因此作为唯一数据源。

### 限购变化是怎么判断的

每只基金的详情（含申购状态和单日额度）都会连同时间戳缓存下来，刷新时用「缓存里的上一次结果」和
「刚抓到的新结果」比对，比对的是卡片右上角那个徽标文本：

| 变化 | 归类 | 颜色 |
| --- | --- | --- |
| 任意 → 暂停申购 | 暂停申购 | 红 |
| 暂停申购 → 限大额 / 不限购 | 恢复申购 | 绿 |
| 不限购 → 限大额，或额度变少 | 收紧 | 红 |
| 限大额 → 不限购，或额度变多 | 放宽 | 绿 |

徽标文本相同则不算变化；首次添加（没有上一次记录）也不会误报。变化列表只反映**最近一次刷新**，
再次刷新会重新计算，没有变化就自动消失，也可以手动点「清除」。

### 请求策略

- 每只基金一次请求，**最多 3 个并发**；
- 结果缓存 **15 分钟**，缓存命中时刷新不发请求（离线也能看）；
- 连续 3 次失败即停止本轮抓取，剩余基金回退到缓存，卡片降级为状态徽标。

## 技术栈

| 组件 | 版本 |
| --- | --- |
| Kotlin | 2.0.20 |
| Jetpack Compose (BOM) | 2024.09.00 |
| Material 3 | 1.3.0 |
| AGP / Gradle | 8.5.2 / 8.9 |
| WorkManager | 2.9.1 |
| compileSdk / targetSdk / minSdk | 34 / 34 / 24 |
| JDK | 17 |

网络层只用 `HttpURLConnection` + `org.json`，没有引入 OkHttp / Retrofit / Room，依赖极少、构建快。
K 线图是自己用 Compose `Canvas` 画的（蜡烛、均线、坐标轴、网格），没有引入图表库。

## 构建

用 Android Studio 直接打开本目录，或：

```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest  # JVM 截图测试，产物在 app/build/screenshots/
```

需要 Android SDK Platform 34 与 Build-Tools 34.0.0。国内网络如果卡在下载 Gradle，可把
`gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 换成腾讯云镜像：
`https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip`。

签名：`app/debug.keystore` 与 `app/release.keystore` 为演示用密钥（口令见 `build.gradle.kts`），
正式发布请替换为你自己的 keystore。

## 截图测试

没有模拟器也能看到真实渲染结果：`ScreenshotTest` / `FeatureScreenshotTest` 用 Robolectric 原生图形在 JVM 上渲染 Compose，
并把视图直接绘制成 PNG（`captureToImage()` 在 Robolectric 下不可用，所以走 `View.draw(Canvas)`）。
覆盖亮色、暗色、骨架屏、空自选、添加面板、限购变化、K 线（含 AI 结论 / 未配置 AI / 暗色）、设置页，
另外会把语义树坐标写进 `tree*.txt`，方便核对布局。

单元测试另外覆盖：均线计算（`KlineTest`）、AI 返回解析（`AiVerdictTest`，含代码块包裹、中文动作词、脏文本兜底）、
定时延迟计算（`DailySchedulerTest`）、限额徽标与变化判定（`FundModelTest`）。

## 目录结构

```
app/src/main/java/com/hukua/fundradar/
├── MainActivity.kt                 入口，edge-to-edge + 列表/设置/K线 路由 + 详情面板
├── data/
│   ├── Fund.kt                     领域模型、限额状态推导、格式化工具
│   ├── EastMoneyApi.kt             详情页（HTML）、列表接口、K线与历史净值
│   ├── FundRepository.kt           并发抓取、双层缓存、失败熔断
│   ├── Kline.kt                    Candle / KlineSeries / 均线计算
│   ├── AiAdvisor.kt                OpenAI 兼容调用、提示词、JSON 解析
│   ├── BarkNotifier.kt             Bark 推送
│   ├── SettingsStore.kt            AI / Bark / 定时配置持久化
│   └── WatchlistStore.kt           自选列表持久化（SharedPreferences）
├── ui/
│   ├── FundViewModel.kt            UiState / 筛选 / 添加 / 移除 / 详情状态机
│   ├── FundListScreen.kt           顶栏、概览、筛选、列表、空态、添加面板
│   ├── FundCard.kt                 卡片 + 限额徽标 + 指标单元格
│   ├── FundDetailSheet.kt          MD3 ModalBottomSheet 详情
│   ├── KlineViewModel.kt           K 线加载 / 窗口切换 / AI 判断状态机
│   ├── KlineScreen.kt              蜡烛图 + 均线 + AI 结论卡片
│   ├── SettingsScreen.kt           AI / Bark / 定时设置
│   └── theme/                      MD3 配色（明/暗）、字阶、涨跌色
└── work/
    ├── DailyScheduler.kt           每天固定时间入队 + 自我续期
    └── DailyCheckWorker.kt         抓行情 → 批量问 AI → 推 Bark
```

## 替换成自己的数据源

1. 改 `EastMoneyApi.fetchFundDetail` 与 `fetchList`，分别返回 `FundDetail` / `List<Fund>`；
2. 字段不同就改 `FundDetail.toFund` 里的映射；
3. 卡片、详情、自选列表全部由 `Fund` 驱动，UI 层无需改动。

`LimitType` 的判定规则集中在 `Fund.limitType`：状态含「暂停」→ 暂停申购；含「限」→ 限大额；
含「开放」→ 不限购；额度 ≥ 100 亿视为不限购。
