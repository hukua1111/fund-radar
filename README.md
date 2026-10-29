# 基金限额雷达

每天北京时间 11:00 自动查一次基金的申购状态和单日限额，**只在有变化时**推送到 Bark。不需要电脑开机，不需要装 App，不需要自己维护服务器。

---

## 它做什么

1. GitHub Actions 按计划唤醒，拉取你配置的基金代码的申购状态（`SGZT`）和单日限购额度（`MAXSG`）
2. 和仓库里的 `state.json`（上一次的基线）逐只比对
3. 有变化就推 Bark，没变化就安静退出
4. 把本次结果写回 `state.json` 并提交，作为下次的基线

第一只基金的状态变化、从「开放申购」变成「限大额 5 万」、从「限大额 5 万」变成「限大额 100 元」，都会推送。

---

## 部署步骤

### 1. 把代码放进仓库

仓库根目录结构必须是：

```
.github/workflows/check.yml
src/core.js
src/gh-run.js
package.json
```

`state.json` 不用手动创建，第一次运行后自动生成。

### 2. 配置 Bark Key

仓库 → **Settings** → **Secrets and variables** → **Actions** → 切到 **Secrets** 标签 → **New repository secret**

| Name | Value |
|---|---|
| `BARK_KEY` | 你的 Bark 设备 Key |

### 3. 配置基金代码

同一页面切到 **Variables** 标签 → **New repository variable**

| Name | Value |
|---|---|
| `FUNDS` | `005827,004243,161725` |

逗号或空格分隔，最多 30 只。不配的话会用 workflow 里写死的默认值。

想每天必推一条（哪怕没变化）：

| Name | Value |
|---|---|
| `ALWAYS_NOTIFY` | `1` |

### 4. 手动跑一次验证

**Actions** 标签 → 左侧「基金限额检查」→ 右侧 **Run workflow** → 再点绿色的 **Run workflow**。

跑完点进这次运行，能看到：

- 日志里 `成功: 3`、`变化: 0`
- 页面顶部有一张「当前状态」表格，列出每只基金的状态和净值
- 仓库里多出一个 `state.json`
- 手机上收到一条「基金限额监控已启动」

收到这条推送，就说明整条链路通了。

---

## 两个必须知道的事实

**定时不准点。** GitHub 的 `cron` 是尽力而为的，`0 3 * * *`（UTC 03:00 = 北京时间 11:00）实际可能在 11:00–11:30 之间随机触发，高峰期甚至跳过一天。这是平台特性，无法调整。如果你需要准点，只能换 Cloudflare Worker 或自己的服务器。

**每天会有一个小提交。** 因为 `state.json` 里记录了最后检查时间，所以每次运行都会产生一个只改几行的提交（提交信息带 `[skip ci]`，不会再触发其他流水线）。这带来一个额外的好处：仓库始终有活动，不会触发 GitHub「60 天无提交自动停用定时任务」的机制。如果哪天发现 Actions 里的定时任务被停用了，去 Actions 页面点一下 **Enable workflow** 即可。

---

## 配置项

| 名称 | 位置 | 必填 | 说明 |
|---|---|---|---|
| `BARK_KEY` | Secrets | 是 | Bark 推送 Key |
| `FUNDS` | Variables | 否 | 基金代码，逗号或空格分隔，最多 30 只 |
| `ALWAYS_NOTIFY` | Variables | 否 | `1` = 每天必推；`0` = 只推变化（默认） |

---

## 为什么必须用 App 身份请求

东方财富的接口有 UA 风控，这是实测结论：

| 请求头 | 结果 |
|---|---|
| `Origin` / `Referer` / `Sec-Fetch-*` | 正常返回 |
| Chrome / Edge / Safari 的 UA | 返回「网络繁忙」 |
| curl、空 UA、天天基金 App UA | 正常返回 |

所以 `src/core.js` 固定发送 `User-Agent: EMProjJijin/6.2.8 (iPhone; ...)`。**不要改成浏览器 UA，会全部失败。**

---

## 本地自测

```bash
node test.mjs
```

不联网，25 项断言，覆盖：首次建基线、无变化不推、限额变化、状态变化（暂停→开放）、抓取失败时保留旧基线、未配 Key、每日必推。

连真实接口试：

```bash
node --input-type=module -e "
import { runCheck } from './src/core.js';
const r = await runCheck({ codes:['005827','004243'], barkKey:'' });
console.log(r.succeeded, r.next);
"
```

---

## 常见问题

**没收到推送**
先浏览器打开 `https://api.day.app/你的KEY/测试/内容`，确认 Key 本身可用。再翻 Actions 的日志看 `推送:` 那一行的返回值。

**某只基金老是失败**
代码写错了。接口对无效代码返回 `Datas: null`，会被记为 failed 并跳过，不会污染基线。

**限额显示 100000000000**
接口对「不限购」的固定返回值，代码已识别为「不限」。

**想改检查时间**
改 `.github/workflows/check.yml` 里的 `cron`。注意它用 **UTC**，北京时间减 8 小时，例如北京时间 22:00 = `0 14 * * *`。

---

## 目录

```
.
├── src/
│   ├── core.js      业务逻辑（抓取、比对、推送）
│   └── gh-run.js    Actions 入口
├── .github/workflows/check.yml
├── package.json
├── test.mjs         本地测试
└── README.md
```

数据来源为东方财富公开接口，仅供个人查询，限额以基金公司公告为准，不构成投资建议。
