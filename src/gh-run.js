// GitHub Actions 入口（Node 20+，无需任何依赖）
// 状态存仓库里的 state.json，每次运行后由 workflow 提交回去。

import { readFileSync, writeFileSync, existsSync, appendFileSync } from 'node:fs';
import { runCheck, parseCodes, describe } from './core.js';

const codes = parseCodes(process.env.FUNDS);
const barkKey = process.env.BARK_KEY || '';
const prevRaw = existsSync('state.json') ? readFileSync('state.json', 'utf8') : null;

if (!codes.length) {
  console.error('FUNDS 没有配置，请在仓库 Settings → Secrets and variables → Actions → Variables 里设置，例如 005827,004243');
  process.exit(1);
}

const result = await runCheck({
  codes,
  barkKey,
  prevRaw,
  alwaysNotify: process.env.ALWAYS_NOTIFY === '1',
});

if (result.ok && result.next) {
  writeFileSync('state.json', JSON.stringify(result.next, null, 2) + '\n');
}

console.log('检查完成');
console.log('  代码数量:', result.checked);
console.log('  成功:', result.succeeded);
console.log('  失败:', result.failed.length ? result.failed.join(', ') : '无');
console.log('  变化:', result.changes.length);
for (const c of result.changes) {
  console.log(`    ${c.name}: ${c.fromText} → ${c.toText}`);
}
console.log('  推送:', JSON.stringify(result.pushed));

// 在 Actions 运行页面写一份可读的汇总，方便事后回看
if (process.env.GITHUB_STEP_SUMMARY) {
  const lines = ['## 基金限额检查', ''];
  lines.push(`- 检查 ${result.checked} 只，成功 ${result.succeeded} 只`);
  if (result.failed.length) lines.push(`- 失败：${result.failed.join('、')}`);
  if (result.isFirstRun) lines.push('- 建立基线（首次运行）');
  lines.push('');
  if (result.changes.length) {
    lines.push('### 检测到变化', '');
    for (const c of result.changes) {
      lines.push(`- **${c.name}**（${c.code}）：${c.fromText} → ${c.toText}`);
    }
  } else {
    lines.push('本次限额与申购状态均无变化。');
  }
  lines.push('', '### 当前状态', '', '| 代码 | 名称 | 状态 | 净值 |', '| --- | --- | --- | --- |');
  for (const [code, rec] of Object.entries(result.next || {})) {
    lines.push(`| ${code} | ${rec.name} | ${describe(rec)} | ${rec.nav ?? '-'} |`);
  }
  appendFileSync(process.env.GITHUB_STEP_SUMMARY, lines.join('\n') + '\n');
}

if (result.changes.length && !barkKey) {
  console.error('检测到变化，但没有配置 BARK_KEY，通知未发出');
}

if (result.succeeded === 0) {
  console.error('全部基金都没取到数据，检查网络或接口是否变更');
  process.exit(1);
}
