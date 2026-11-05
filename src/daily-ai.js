// 每日 14:35 的波段判断任务（GitHub Actions 入口，Node 20+，零依赖）
// 流程：抓净值历史 → 算指标 → 规则判断 → （配置了 AI 时）请 AI 复核 → 写快照 → Bark 推送

import { mkdirSync, writeFileSync, readFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fetchFund, fetchHistory, parseCodes, describe } from './core.js';
import { computeIndicators, actionShort } from '../docs/lib/indicators.js';
import { judgeFund, isConfigured, normalizeConfig } from '../docs/lib/ai-core.js';

const ROOT = process.cwd();
const DATA_DIR = join(ROOT, 'docs', 'data');
const KLINE_DIR = join(DATA_DIR, 'kline');
const AI_DIR = join(DATA_DIR, 'ai');

const KLINE_POINTS = Number(process.env.KLINE_POINTS) || 400;
const AI_MAX_FUNDS = Number(process.env.AI_MAX_FUNDS) || 20;
const AI_CONCURRENCY = Math.max(1, Math.min(6, Number(process.env.AI_CONCURRENCY) || 3));

const aiConfig = normalizeConfig({
  baseUrl: process.env.AI_BASE_URL || 'https://api.deepseek.com/v1',
  apiKey: process.env.AI_KEY || '',
  model: process.env.AI_MODEL || 'deepseek-chat',
  timeoutMs: Number(process.env.AI_TIMEOUT_MS) || 60000,
});

const codes = parseCodes(process.env.FUNDS);
if (!codes.length) {
  console.error('FUNDS 没有配置，请在仓库 Settings → Secrets and variables → Actions → Variables 里设置');
  process.exit(1);
}

function bjNow(ts = Date.now()) {
  return new Date(ts + 8 * 3600 * 1000).toISOString().slice(0, 16).replace('T', ' ');
}

function bjDate(ts = Date.now()) {
  return new Date(ts + 8 * 3600 * 1000).toISOString().slice(0, 10);
}

function readJson(path, fallback) {
  try {
    if (!existsSync(path)) return fallback;
    return JSON.parse(readFileSync(path, 'utf8'));
  } catch (e) {
    return fallback;
  }
}

function writeJson(path, obj) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, JSON.stringify(obj) + '\n');
}

function round(v, digits = 4) {
  return Number.isFinite(v) ? Number(v.toFixed(digits)) : null;
}

async function mapLimit(items, limit, fn) {
  const out = new Array(items.length);
  let cursor = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (cursor < items.length) {
      const idx = cursor++;
      out[idx] = await fn(items[idx], idx);
    }
  });
  await Promise.all(workers);
  return out;
}

const prevState = readJson(join(ROOT, 'state.json'), {});

console.log(`开始: ${codes.length} 只基金 · AI ${isConfigured(aiConfig) ? '已启用' : '未配置（仅规则判断）'}`);

const started = Date.now();
const records = await mapLimit(codes, 3, async (code) => {
  const [basic, hist] = await Promise.all([fetchFund(code), fetchHistory(code)]);
  if (!hist.ok) {
    console.error(`  ${code} 历史净值失败: ${hist.error}${hist.fallbackError ? ' / 备用源: ' + hist.fallbackError : ''}`);
    return { code, ok: false, error: hist.error };
  }
  const { series, latest } = computeIndicators(hist.klines);
  const prev = prevState[code] || {};
  const name = (basic.ok && basic.name) || hist.name || prev.name || code;
  const type = (basic.ok && basic.type) || '';
  const sgzt = (basic.ok && basic.sgzt) || prev.sgzt || '';
  const maxsg = basic.ok ? basic.maxsg : prev.maxsg;
  const limitText = describe({ sgzt, maxsg });

  return {
    code,
    ok: true,
    name,
    type,
    limitText,
    sgzt,
    maxsg,
    nav: latest.price,
    navDate: latest.date,
    chg: latest.chg,
    klines: hist.klines,
    series,
    latest,
  };
});

const okFunds = records.filter((r) => r && r.ok);
if (!okFunds.length) {
  console.error('全部基金都没取到历史净值，检查网络或接口是否变更');
  process.exit(1);
}
if (okFunds.length < codes.length) {
  console.warn(`注意: ${codes.length - okFunds.length} 只基金本次抓取失败，将沿用上一次的快照`);
}

// ---- AI 复核 ----
const aiTargets = isConfigured(aiConfig) ? okFunds.slice(0, AI_MAX_FUNDS) : [];
const verdicts = new Map();

await mapLimit(aiTargets, AI_CONCURRENCY, async (f) => {
  const cfg = { ...aiConfig, timeoutMs: Math.min(aiConfig.timeoutMs, 60000) };
  const verdict = await judgeFund({
    fund: { code: f.code, name: f.name, type: f.type, limitText: f.limitText },
    latest: f.latest,
    series: f.series,
    klines: f.klines,
    config: cfg,
  });
  verdicts.set(f.code, verdict);
  const tag = verdict.source === 'ai' ? `AI ${verdict.model || ''}` : '规则';
  console.log(
    `  ${f.code} ${f.name}: ${actionShort(verdict.action)} ${verdict.confidence}% (${tag})` +
      (verdict.error ? ` · ${verdict.error}` : '')
  );
});

for (const f of okFunds) {
  if (!verdicts.has(f.code)) {
    verdicts.set(f.code, { ...f.latest.verdict, source: 'rule', at: Date.now(), note: '超出本次 AI 判断数量上限' });
  }
}

// ---- 写快照 ----
const generatedAt = Date.now();
const prevDoc = readJson(join(DATA_DIR, 'funds.json'), null);
const prevFunds = new Map(((prevDoc && prevDoc.funds) || []).map((f) => [f.code, f]));

const fundsOut = [];
for (const code of codes) {
  const rec = okFunds.find((f) => f.code === code);
  if (!rec) {
    const old = prevFunds.get(code);
    if (old) fundsOut.push({ ...old, stale: true });
    continue;
  }
  const v = verdicts.get(rec.code);
  const f = rec;
  fundsOut.push({
    code: f.code,
    name: f.name,
    type: f.type,
    nav: round(f.nav),
    navDate: f.navDate,
    chg: Number.isFinite(f.chg) ? Math.round(f.chg * 100) / 100 : null,
    sgzt: f.sgzt,
    maxsg: f.maxsg,
    limitText: f.limitText,
    spark: f.klines.slice(-60).map((k) => round(k.c)),
    ind: {
      ma5: f.latest.ma5,
      ma10: f.latest.ma10,
      ma20: f.latest.ma20,
      ma60: f.latest.ma60,
      rsi14: f.latest.rsi14,
      bias20: f.latest.bias20,
      slope20: f.latest.slope20,
      pos20: f.latest.pos20,
      dd60: f.latest.dd60,
      vol20: f.latest.vol20,
      high20: round(f.latest.high20),
      low20: round(f.latest.low20),
      high60: round(f.latest.high60),
      low60: round(f.latest.low60),
      trend: f.latest.trend,
      cross: f.latest.cross,
      score: v.score ?? null,
      action: v.action,
      confidence: v.confidence,
      summary: v.summary,
      reasons: v.reasons,
      position: v.position,
      support: round(v.support),
      resistance: round(v.resistance),
      risk: v.risk,
      source: v.source,
      model: v.model || null,
      error: v.error || null,
    },
  });
}

const failed = records.filter((r) => !r || !r.ok).map((r) => r && r.code).filter(Boolean);

const fundsDoc = {
  version: 2,
  updatedAt: generatedAt,
  updatedAtText: bjNow(generatedAt),
  dataDate: fundsOut.reduce((acc, f) => (f.navDate && f.navDate > (acc || '') ? f.navDate : acc), null),
  aiEnabled: isConfigured(aiConfig) && aiTargets.length > 0,
  funds: fundsOut,
  failed,
};

writeJson(join(DATA_DIR, 'funds.json'), fundsDoc);

for (const f of okFunds) {
  const klines = f.klines.slice(-KLINE_POINTS).map((k) => ({
    d: k.d,
    c: round(k.c),
    chg: Number.isFinite(k.chg) ? Math.round(k.chg * 100) / 100 : null,
    acc: round(k.acc),
  }));
  writeJson(join(KLINE_DIR, `${f.code}.json`), {
    code: f.code,
    name: f.name,
    updatedAt: generatedAt,
    klines,
  });
}

// 抓取失败的基金会沿用旧快照，这里把它的旧结论也还原出来
function verdictOf(f) {
  const v = verdicts.get(f.code);
  if (v) return v;
  return {
    action: f.ind.action,
    confidence: f.ind.confidence,
    score: f.ind.score,
    summary: f.ind.summary,
    reasons: f.ind.reasons,
    position: f.ind.position,
    support: f.ind.support,
    resistance: f.ind.resistance,
    risk: f.ind.risk,
    source: f.ind.source || 'rule',
    model: f.ind.model,
    error: f.ind.error,
  };
}

const items = fundsOut
  .map((f) => {
    const v = verdictOf(f);
    return {
      code: f.code,
      name: f.name,
      action: v.action,
      confidence: v.confidence,
      score: v.score ?? null,
      summary: v.summary,
      reasons: v.reasons,
      position: v.position,
      support: round(v.support),
      resistance: round(v.resistance),
      risk: v.risk,
      source: v.source,
      model: v.model || null,
      error: v.error || null,
    };
  })
  .sort((a, b) => {
    const rank = { buy: 0, sell: 1, hold: 2 };
    if (rank[a.action] !== rank[b.action]) return rank[a.action] - rank[b.action];
    return b.confidence - a.confidence;
  });

const stat = {
  buy: items.filter((i) => i.action === 'buy').length,
  sell: items.filter((i) => i.action === 'sell').length,
  hold: items.filter((i) => i.action === 'hold').length,
};

const latestDoc = {
  date: bjDate(generatedAt),
  at: generatedAt,
  atText: bjNow(generatedAt),
  model: isConfigured(aiConfig) ? aiConfig.model : null,
  aiEnabled: isConfigured(aiConfig) && aiTargets.length > 0,
  stat,
  items,
  failed,
};
writeJson(join(AI_DIR, 'latest.json'), latestDoc);

// 保留最近 90 天的判断摘要，便于回看
const historyPath = join(AI_DIR, 'history.json');
const history = readJson(historyPath, []);
const historyEntry = {
  date: latestDoc.date,
  at: generatedAt,
  stat,
  items: items.map((i) => ({ code: i.code, name: i.name, action: i.action, confidence: i.confidence, score: i.score })),
};
const merged = [historyEntry, ...(Array.isArray(history) ? history : []).filter((h) => h && h.date !== latestDoc.date)].slice(0, 90);
writeJson(historyPath, merged);

console.log(`快照已写入 docs/data（${fundsOut.length} 只基金，${items.length} 条判断）`);

// ---- Bark 推送 ----
function buildBarkBody() {
  const lines = items.map((i) => {
    const short = i.name.length > 12 ? i.name.slice(0, 12) : i.name;
    return `${short} ${actionShort(i.action)}${i.confidence}%·${i.summary}`;
  });
  const header = `买 ${stat.buy} · 卖 ${stat.sell} · 观望 ${stat.hold}`;
  return [header, '', ...lines].join('\n').slice(0, 1800);
}

async function pushBark(key, title, body) {
  if (!key) return { ok: false, error: '未配置 BARK_KEY' };
  try {
    const res = await fetch('https://api.day.app/push', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ device_key: key, title, body, group: '基金波段', level: 'active', icon: 'https://hukua1111.github.io/fund-radar/icons/icon-192.png' }),
    });
    const text = await res.text();
    return { ok: res.ok, status: res.status, raw: text.slice(0, 160) };
  } catch (e) {
    return { ok: false, error: String((e && e.message) || e).slice(0, 120) };
  }
}

const notifyMode = (process.env.AI_NOTIFY || 'all').toLowerCase();
const shouldPush = process.env.SKIP_PUSH !== '1' && (notifyMode !== 'signal' || stat.buy + stat.sell > 0);
let pushed = null;
if (shouldPush) {
  const title = `${latestDoc.date.slice(5)} 波段判断 · 买${stat.buy} 卖${stat.sell}`;
  pushed = await pushBark(process.env.BARK_KEY || '', title, buildBarkBody());
  console.log('推送:', JSON.stringify(pushed));
}

// ---- Actions 运行摘要 ----
if (process.env.GITHUB_STEP_SUMMARY) {
  const { appendFileSync } = await import('node:fs');
  const lines = ['## 基金波段判断', ''];
  lines.push(`- 时间：${latestDoc.atText}（北京时间）`);
  lines.push(`- 模型：${latestDoc.aiEnabled ? latestDoc.model : '未配置 AI，仅规则判断'}`);
  lines.push(`- 结论：买入 ${stat.buy} · 卖出 ${stat.sell} · 观望 ${stat.hold}`);
  if (failed.length) lines.push(`- 失败：${failed.join('、')}`);
  lines.push('', '| 代码 | 名称 | 结论 | 信心 | 分数 | 净值 | MA20 | 说明 |', '| --- | --- | --- | --- | --- | --- | --- | --- |');
  for (const f of fundsOut) {
    const v = verdictOf(f);
    lines.push(
      `| ${f.code} | ${f.name} | ${actionShort(v.action)} | ${v.confidence}% | ${v.score ?? '-'} | ${f.nav} | ${f.ind.ma20 ?? '-'} | ${v.summary} |`
    );
  }
  appendFileSync(process.env.GITHUB_STEP_SUMMARY, lines.join('\n') + '\n');
}

console.log(`完成，用时 ${((Date.now() - started) / 1000).toFixed(1)}s`);
