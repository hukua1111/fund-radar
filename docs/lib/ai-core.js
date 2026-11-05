// AI 波段判断：OpenAI 兼容协议（DeepSeek / 月之暗面 / 智谱 / 通义 / OpenAI / Ollama 均可）
// 浏览器与 Node 共用。

import { actionText } from './indicators.js';

export const AI_PRESETS = [
  { id: 'deepseek', name: 'DeepSeek', baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-chat' },
  { id: 'moonshot', name: '月之暗面 Kimi', baseUrl: 'https://api.moonshot.cn/v1', model: 'moonshot-v1-8k' },
  { id: 'zhipu', name: '智谱 GLM', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', model: 'glm-4-flash' },
  { id: 'dashscope', name: '阿里通义千问', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-plus' },
  { id: 'openai', name: 'OpenAI', baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini' },
  { id: 'ollama', name: '本地 Ollama', baseUrl: 'http://127.0.0.1:11434/v1', model: 'qwen2.5:7b' },
  { id: 'custom', name: '自定义', baseUrl: '', model: '' },
];

/** 允许用户只填域名，或直接贴完整的 chat/completions 地址 */
export function normalizeBaseUrl(raw) {
  let base = String(raw || '').trim().replace(/\/+$/, '');
  if (!base) return '';
  if (!/^https?:\/\//i.test(base)) base = 'https://' + base;
  if (/\/chat\/completions$/i.test(base)) return base;
  return base + '/chat/completions';
}

export function normalizeConfig(cfg) {
  const c = cfg || {};
  return {
    baseUrl: String(c.baseUrl || '').trim(),
    apiKey: String(c.apiKey || '').trim(),
    model: String(c.model || '').trim() || 'deepseek-chat',
    temperature: Number.isFinite(Number(c.temperature)) ? Number(c.temperature) : 0.3,
    timeoutMs: Number.isFinite(Number(c.timeoutMs)) ? Number(c.timeoutMs) : 45000,
  };
}

export function isConfigured(cfg) {
  const c = normalizeConfig(cfg);
  return Boolean(c.baseUrl && c.apiKey && c.model);
}

const SYSTEM_PROMPT = `你是一名稳健的基金波段交易顾问，服务对象是只能做场外基金申赎的个人投资者。
你必须遵守：
1. 只依据用户给出的数据判断，不得编造任何价格、指标或新闻。
2. 场外基金按 T+1 确认份额，波段周期定义为 5-15 个交易日，不考虑日内交易。
3. 基金只有日频净值，没有盘中高低点与成交量，不要假设这些数据存在。
4. 判断要偏保守：趋势不明、数据不足、或风险收益比不佳时，必须给 hold。
5. 只输出一个 JSON 对象，不要输出 Markdown 代码块、解释文字或前后缀。`;

function fmtNum(v, digits = 4) {
  return Number.isFinite(v) ? Number(v.toFixed(digits)) : null;
}

/** 近 N 个交易日的紧凑数据表，控制 token 用量 */
export function compactSeries(klines, series, days = 45) {
  const n = klines.length;
  const from = Math.max(0, n - days);
  const rows = [];
  for (let i = from; i < n; i++) {
    const k = klines[i];
    rows.push({
      d: k.d,
      c: fmtNum(k.c),
      chg: Number.isFinite(k.chg) ? Math.round(k.chg * 100) / 100 : null,
      ma5: fmtNum(series.ma5[i]),
      ma20: fmtNum(series.ma20[i]),
      ma60: fmtNum(series.ma60[i]),
    });
  }
  return rows;
}

export function buildMessages({ fund, latest, series, klines, days = 45 }) {
  const rows = compactSeries(klines, series, days);
  const info = {
    基金代码: fund.code,
    基金名称: fund.name,
    基金类型: fund.type || null,
    最新净值日期: latest.date,
    最新净值: latest.price,
    当日涨跌: latest.chg == null ? null : latest.chg + '%',
    MA5: latest.ma5,
    MA10: latest.ma10,
    MA20: latest.ma20,
    MA60: latest.ma60,
    MA20偏离率: latest.bias20 == null ? null : latest.bias20 + '%',
    MA20五日斜率: latest.slope20 == null ? null : latest.slope20 + '%',
    RSI14: latest.rsi14,
    '20日位置(0最低1最高)': latest.pos20 == null ? null : latest.pos20,
    '距60日高点': latest.dd60 == null ? null : latest.dd60 + '%',
    '60日高/低': [latest.high60, latest.low60],
    '20日高/低': [latest.high20, latest.low20],
    '年化波动率': latest.vol20 == null ? null : latest.vol20 + '%',
    均线交叉: latest.cross && latest.cross.type !== 'none' ? `${latest.cross.type === 'golden' ? '金叉' : '死叉'}（${latest.cross.days} 个交易日内）` : '无',
    中期趋势: latest.trend === 'up' ? 'MA20 在 MA60 上方' : latest.trend === 'down' ? 'MA20 在 MA60 下方' : '未知',
    申购状态: fund.limitText || null,
    数据样本数: klines.length,
  };

  const user = `请对下面这只基金做一次波段判断。

【基金与指标】
${JSON.stringify(info, null, 2)}

【近 ${rows.length} 个交易日数据】（d=日期, c=单位净值, chg=当日涨跌%, ma5/ma20/ma60=均线）
${rows.map((r) => `${r.d},${r.c},${r.chg == null ? '-' : r.chg},${r.ma5 == null ? '-' : r.ma5},${r.ma20 == null ? '-' : r.ma20},${r.ma60 == null ? '-' : r.ma60}`).join('\n')}

【判断要求】
- 站在今天（${latest.date}）收盘后的视角，判断未来 5-15 个交易日是否适合做一次波段。
- 重点看：价格与 MA20 的位置关系、MA20 斜率、MA5/MA20 排列、RSI 是否过热或超卖、距离 60 日高点的回撤。
- 只在风险收益比明显占优时给 buy；明显破位或过热时给 sell；其余给 hold。不要为了给建议而给建议。
- support / resistance 用给定数据中的价位，不要凭空造。

【输出 JSON 结构】
{"action":"buy|hold|sell","confidence":0-100 的整数,"summary":"不超过 40 字的中文结论","reasons":["3-4 条依据，每条不超过 30 字"],"support":数字,"resistance":数字,"position":"建议仓位，例如 3 成 / 减至 2 成 / 观望","risk":"主要风险，不超过 30 字"}`;

  return [
    { role: 'system', content: SYSTEM_PROMPT },
    { role: 'user', content: user },
  ];
}

function clampNum(v, lo, hi) {
  const n = Number(v);
  if (!Number.isFinite(n)) return null;
  return Math.max(lo, Math.min(hi, n));
}

export function normalizeAction(raw) {
  const s = String(raw || '').trim().toLowerCase();
  if (['buy', 'b', '买入', '加仓', '建仓', '申购', 'strong_buy'].includes(s)) return 'buy';
  if (['sell', 's', '卖出', '减仓', '清仓', '赎回', 'strong_sell'].includes(s)) return 'sell';
  if (['hold', 'h', '观望', '持有', '等待', 'neutral'].includes(s)) return 'hold';
  if (/买|buy|加仓|建仓/.test(s)) return 'buy';
  if (/卖|sell|减仓|清仓/.test(s)) return 'sell';
  return 'hold';
}

/** 从模型输出里抠出 JSON，容忍 ```json 包裹、前后缀说明、全角标点 */
export function parseVerdict(text, fallback = {}) {
  let raw = String(text == null ? '' : text).trim();
  if (!raw) throw new Error('AI 返回空内容');

  raw = raw.replace(/^```(?:json)?\s*/i, '').replace(/```\s*$/i, '').trim();

  let obj = null;
  try {
    obj = JSON.parse(raw);
  } catch (e) {
    const start = raw.indexOf('{');
    const end = raw.lastIndexOf('}');
    if (start >= 0 && end > start) {
      const slice = raw.slice(start, end + 1).replace(/[“”]/g, '"').replace(/，/g, ',');
      obj = JSON.parse(slice);
    }
  }
  if (!obj || typeof obj !== 'object') throw new Error('AI 返回的不是合法 JSON');

  const action = normalizeAction(obj.action || obj.signal || obj.结论);
  let confidence = Number(obj.confidence ?? obj.信心 ?? 60);
  if (Number.isFinite(confidence) && confidence > 0 && confidence <= 1) confidence *= 100;
  confidence = clampNum(confidence, 0, 100) ?? 60;

  const reasonsRaw = obj.reasons ?? obj.理由 ?? [];
  const reasons = (Array.isArray(reasonsRaw) ? reasonsRaw : [reasonsRaw])
    .map((r) => (typeof r === 'string' ? r : r && r.text ? String(r.text) : ''))
    .map((r) => r.trim())
    .filter(Boolean)
    .slice(0, 5);

  const summary = String(obj.summary ?? obj.结论摘要 ?? fallback.summary ?? '').trim().slice(0, 120);

  return {
    action,
    confidence: Math.round(confidence),
    summary: summary || actionText(action),
    reasons: reasons.length ? reasons : fallback.reasons || [],
    support: clampNum(obj.support, 0, 1e6) ?? fallback.support ?? null,
    resistance: clampNum(obj.resistance, 0, 1e6) ?? fallback.resistance ?? null,
    position: String(obj.position ?? obj.仓位 ?? fallback.position ?? '').trim().slice(0, 30),
    risk: String(obj.risk ?? obj.风险 ?? '').trim().slice(0, 120),
  };
}

async function postChat({ url, apiKey, body, timeoutMs, fetchImpl, signal }) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  const onAbort = () => ctrl.abort();
  if (signal) signal.addEventListener('abort', onAbort, { once: true });
  try {
    return await fetchImpl(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${apiKey}`,
      },
      body: JSON.stringify(body),
      signal: ctrl.signal,
    });
  } finally {
    clearTimeout(timer);
    if (signal) signal.removeEventListener('abort', onAbort);
  }
}

export async function callChat({ config, messages, fetchImpl = fetch, signal, jsonMode = true }) {
  const cfg = normalizeConfig(config);
  if (!cfg.baseUrl) throw new Error('还没有填写 AI 接口地址');
  if (!cfg.apiKey) throw new Error('还没有填写 AI Key');

  const url = normalizeBaseUrl(cfg.baseUrl);
  const baseBody = {
    model: cfg.model,
    messages,
    temperature: cfg.temperature,
    max_tokens: 900,
    stream: false,
  };

  let res;
  try {
    res = await postChat({
      url,
      apiKey: cfg.apiKey,
      body: jsonMode ? { ...baseBody, response_format: { type: 'json_object' } } : baseBody,
      timeoutMs: cfg.timeoutMs,
      fetchImpl,
      signal,
    });
    // 部分服务商不支持 response_format，去掉后重试一次
    if (res.status === 400 && jsonMode) {
      await res.text().catch(() => '');
      res = await postChat({ url, apiKey: cfg.apiKey, body: baseBody, timeoutMs: cfg.timeoutMs, fetchImpl, signal });
    }
  } catch (e) {
    const msg = String((e && e.message) || e);
    if (/abort/i.test(msg)) throw new Error(`AI 请求超时（${Math.round(cfg.timeoutMs / 1000)} 秒）`);
    if (/Failed to fetch|NetworkError|fetch failed/i.test(msg)) {
      throw new Error('无法连接 AI 接口，检查地址、网络，或该服务是否允许浏览器直连');
    }
    throw new Error(msg.slice(0, 160));
  }

  const text = await res.text();
  if (!res.ok) {
    let detail = text.slice(0, 200);
    try {
      const j = JSON.parse(text);
      detail = (j.error && (j.error.message || j.error.code)) || j.message || detail;
    } catch (e) {
      /* 保留原始文本 */
    }
    if (res.status === 401 || res.status === 403) throw new Error(`AI Key 无效或无权限：${detail}`);
    if (res.status === 404) throw new Error(`接口地址或模型不存在：${detail}`);
    if (res.status === 429) throw new Error('AI 接口限流或余额不足，稍后再试');
    throw new Error(`AI 接口返回 ${res.status}：${detail}`);
  }

  let data;
  try {
    data = JSON.parse(text);
  } catch (e) {
    throw new Error('AI 接口返回的不是 JSON，可能地址填成了网页地址');
  }
  const content =
    data && data.choices && data.choices[0] && data.choices[0].message && data.choices[0].message.content;
  if (!content) throw new Error('AI 没有返回内容');
  return { content, usage: data.usage || null, model: data.model || cfg.model };
}

/** 对单只基金做判断，失败时退回规则结论，保证界面永远有结果 */
export async function judgeFund({ fund, latest, series, klines, config, fetchImpl = fetch, signal }) {
  const fallback = latest.verdict || {};
  const base = {
    ...fallback,
    support: fallback.support ?? latest.support ?? null,
    resistance: fallback.resistance ?? latest.resistance ?? null,
  };
  if (!isConfigured(config)) {
    return { ...base, source: 'rule', error: '未配置 AI，显示规则判断' };
  }
  const messages = buildMessages({ fund, latest, series, klines });
  try {
    const { content, usage, model } = await callChat({ config, messages, fetchImpl, signal });
    const verdict = parseVerdict(content, base);
    return {
      ...verdict,
      score: fallback.score ?? null,
      source: 'ai',
      model,
      usage,
      at: Date.now(),
    };
  } catch (e) {
    return {
      ...base,
      source: 'rule',
      error: String((e && e.message) || e).slice(0, 200),
      at: Date.now(),
    };
  }
}

/** 设置页的“测试连接”：优先 /models，失败则发一条极短对话 */
export async function testConnection({ config, fetchImpl = fetch }) {
  const cfg = normalizeConfig(config);
  if (!cfg.baseUrl || !cfg.apiKey) throw new Error('请先填写接口地址和 Key');
  const started = Date.now();
  const { content, model } = await callChat({
    config: { ...cfg, timeoutMs: Math.min(cfg.timeoutMs, 20000) },
    messages: [{ role: 'user', content: '只回复两个字：正常' }],
    fetchImpl,
    jsonMode: false,
  });
  return { ok: true, model, ms: Date.now() - started, reply: String(content).slice(0, 60) };
}
