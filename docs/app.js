// 基金雷达 · 前端应用
import { computeIndicators, actionShort, actionText } from './lib/indicators.js';
import { KLineChart } from './lib/chart.js';
import { judgeFund, isConfigured, testConnection, AI_PRESETS, normalizeBaseUrl } from './lib/ai-core.js';
import * as store from './lib/store.js';

const el = {
  app: document.getElementById('app'),
  title: document.getElementById('pageTitle'),
  sub: document.getElementById('pageSub'),
  back: document.getElementById('backBtn'),
  action: document.getElementById('actionBtn'),
  view: document.getElementById('view'),
  tabbar: document.getElementById('tabbar'),
  toast: document.getElementById('toast'),
  topbar: document.getElementById('topbar'),
};

const state = {
  view: 'list',
  code: null,
  data: null,
  aiLatest: null,
  watch: store.loadWatch(),
  settings: store.loadSettings(),
  loading: true,
  cached: false,
  error: '',
  chart: null,
  klines: null,
  ind: null,
  detail: null,
  ai: null,
  aiLoading: false,
  aiError: '',
  range: '6m',
  token: 0,
  online: navigator.onLine,
  installPrompt: null,
  swReg: null,
};

// ---------- 工具 ----------
const $ = (sel, root = document) => root.querySelector(sel);

function esc(v) {
  return String(v == null ? '' : v).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}

function fmtNav(v) {
  if (!Number.isFinite(Number(v))) return '—';
  return Number(v).toFixed(4);
}

function fmtPct(v, digits = 2) {
  if (!Number.isFinite(Number(v))) return '—';
  const n = Number(v);
  return `${n > 0 ? '+' : ''}${n.toFixed(digits)}%`;
}

function trendClass(v) {
  if (!Number.isFinite(Number(v)) || Number(v) === 0) return 'flat';
  return Number(v) > 0 ? 'up' : 'down';
}

function fmtTime(ts) {
  if (!ts) return '—';
  const d = new Date(ts);
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getMonth() + 1}/${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

let toastTimer = null;
function toast(msg, ms = 2400) {
  el.toast.textContent = msg;
  el.toast.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.toast.classList.remove('show'), ms);
}

function applyTheme() {
  const mode = state.settings.theme || 'auto';
  if (mode === 'auto') document.documentElement.removeAttribute('data-theme');
  else document.documentElement.setAttribute('data-theme', mode);
  const dark = mode === 'dark' || (mode === 'auto' && window.matchMedia('(prefers-color-scheme: dark)').matches);
  if (state.chart) state.chart.setTheme(dark ? 'dark' : 'light');
}

function isDark() {
  const mode = state.settings.theme || 'auto';
  if (mode === 'dark') return true;
  if (mode === 'light') return false;
  return window.matchMedia('(prefers-color-scheme: dark)').matches;
}

// ---------- 数据 ----------
function cloudFunds() {
  return (state.data && state.data.funds) || [];
}

function localFundEntries() {
  const cloudCodes = new Set(cloudFunds().map((f) => f.code));
  return state.watch
    .filter((w) => !cloudCodes.has(w.code))
    .map((w) => {
      const live = store.loadLive(w.code);
      const klines = live && live.klines ? live.klines : null;
      const ind = klines && klines.length ? computeIndicators(klines).latest : null;
      const last = klines && klines.length ? klines[klines.length - 1] : null;
      return {
        code: w.code,
        name: (live && live.name) || w.name || w.code,
        local: true,
        nav: last ? last.c : null,
        navDate: last ? last.d : null,
        chg: last && Number.isFinite(last.chg) ? last.chg : null,
        limitText: '本地自选',
        ind,
        spark: klines ? klines.slice(-60).map((k) => k.c) : [],
      };
    });
}

function allFunds() {
  return [...cloudFunds(), ...localFundEntries()];
}

function findFund(code) {
  return allFunds().find((f) => f.code === code) || null;
}

function stats() {
  if (state.aiLatest && state.aiLatest.stat) return state.aiLatest.stat;
  const funds = cloudFunds();
  return {
    buy: funds.filter((f) => f.ind && f.ind.action === 'buy').length,
    sell: funds.filter((f) => f.ind && f.ind.action === 'sell').length,
    hold: funds.filter((f) => f.ind && f.ind.action === 'hold').length,
  };
}

async function loadAll(force = false) {
  state.loading = true;
  render();
  const [snap, ai] = await Promise.all([store.loadFundsSnapshot(), store.loadAILatest()]);
  state.loading = false;
  if (snap.ok) {
    state.data = snap.data;
    state.cached = Boolean(snap.cached);
    state.error = '';
  } else {
    state.error = snap.error || '数据加载失败';
  }
  state.aiLatest = ai;
  if (force) toast('已刷新');
  render();
}

// ---------- 列表 ----------
function sparkline(values, positive) {
  if (!Array.isArray(values) || values.length < 2) return '';
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min || 1;
  const w = 100;
  const h = 34;
  const pts = values.map((v, i) => {
    const x = (i / (values.length - 1)) * w;
    const y = h - 3 - ((v - min) / span) * (h - 6);
    return `${x.toFixed(2)},${y.toFixed(2)}`;
  });
  const line = `M${pts.join(' L')}`;
  const color = positive ? 'var(--up)' : 'var(--down)';
  const fill = positive ? 'var(--up-soft)' : 'var(--down-soft)';
  return `<svg viewBox="0 0 ${w} ${h}" preserveAspectRatio="none" aria-hidden="true">
    <path d="${line} L${w},${h} L0,${h} Z" fill="${fill}" opacity="0.5"></path>
    <path d="${line}" fill="none" stroke="${color}" stroke-width="1.5" vector-effect="non-scaling-stroke" stroke-linejoin="round" stroke-linecap="round"></path>
  </svg>`;
}

function signalChip(ind) {
  if (!ind || !ind.action) return '<span class="chip">待判断</span>';
  const cls = ind.action;
  const src = ind.source === 'ai' ? '<span class="chip ai">AI</span>' : '';
  return `<span class="chip ${cls}">${actionShort(ind.action)} ${ind.confidence || 0}%</span>${src}`;
}

function fundCard(f) {
  const ind = f.ind || null;
  const chgCls = trendClass(f.chg);
  const spark = Array.isArray(f.spark) && f.spark.length ? f.spark : null;
  const positive = spark ? spark[spark.length - 1] >= spark[0] : (f.chg || 0) >= 0;
  return `<button class="fund-card" data-action="open" data-code="${esc(f.code)}">
    <div class="fund-top">
      <div class="fund-name">
        <span class="n">${esc(f.name)}</span>
        <span class="c">${esc(f.code)}${f.local ? ' · 本地自选' : f.type ? ' · ' + esc(f.type) : ''}</span>
      </div>
      <div class="fund-price">
        <div class="p">${fmtNav(f.nav)}</div>
        <div class="d ${chgCls}">${f.chg == null ? '—' : fmtPct(f.chg)}</div>
      </div>
    </div>
    <div class="fund-meta">
      ${signalChip(ind)}
      <span class="chip">${esc(f.limitText || '申购状态未知')}</span>
      ${ind && ind.ma20 ? `<span class="chip">MA20 ${fmtNav(ind.ma20)}</span>` : ''}
    </div>
    ${spark ? `<div class="spark-wrap">${sparkline(spark, positive)}</div>` : ''}
  </button>`;
}

function renderList() {
  el.title.textContent = '基金雷达';
  el.sub.textContent = state.data
    ? `${state.data.dataDate || ''} 净值 · ${state.data.updatedAtText || ''} 更新${state.cached ? ' · 离线缓存' : ''}`
    : '每天 14:35 自动判断';
  el.back.classList.add('hidden');
  el.action.classList.remove('hidden');
  el.action.classList.remove('spinning');
  el.action.setAttribute('data-action', 'refresh');
  el.tabbar.classList.remove('hidden');

  const funds = allFunds();
  const s = stats();
  const hero = `<section class="hero">
    <div class="hero-label"><span class="dot"></span>${state.aiLatest && state.aiLatest.aiEnabled ? 'AI 波段判断' : '规则波段判断'} · ${esc((state.aiLatest && state.aiLatest.atText) || (state.data && state.data.updatedAtText) || '—')}</div>
    <div class="hero-title">${s.buy > 0 ? `${s.buy} 只出现买入信号` : s.sell > 0 ? `${s.sell} 只偏弱，注意减仓` : '暂无明显波段信号'}</div>
    <div class="hero-stats">
      <div class="hero-stat"><b>${s.buy}</b><span>偏买入</span></div>
      <div class="hero-stat"><b>${s.sell}</b><span>偏卖出</span></div>
      <div class="hero-stat"><b>${s.hold}</b><span>观望</span></div>
    </div>
    <div class="hero-foot"><span>共 ${funds.length} 只基金</span><span>${state.aiLatest && state.aiLatest.model ? '模型 ' + esc(state.aiLatest.model) : '未接入云端 AI'}</span></div>
  </section>`;

  let body;
  if (state.loading && !state.data) {
    body = `<div class="fund-list" style="margin-top:16px">
      <div class="skeleton"></div><div class="skeleton"></div><div class="skeleton"></div>
    </div>`;
  } else if (!funds.length) {
    body = `<div class="empty"><div class="big">📡</div>还没有基金数据<br />在设置里添加本地自选，或在仓库变量 FUNDS 里配置</div>`;
  } else {
    body = `<div class="section-title"><h2>关注列表</h2><span class="hint">点开看 K 线与判断</span></div>
      <div class="fund-list">${funds.map(fundCard).join('')}</div>`;
  }

  el.view.innerHTML = `${hero}${body}
    <p class="disclaimer">数据来自东方财富公开接口，净值以基金公司公告为准。判断仅为技术面参考，不构成投资建议。</p>`;
}

// ---------- 详情 ----------
function ring(score, action) {
  const r = 28;
  const c = 2 * Math.PI * r;
  const v = Math.max(0, Math.min(100, Number(score) || 0));
  const color = action === 'buy' ? 'var(--up)' : action === 'sell' ? 'var(--down)' : 'var(--hold)';
  return `<div class="ring">
    <svg width="66" height="66" viewBox="0 0 66 66">
      <circle cx="33" cy="33" r="${r}" fill="none" stroke="var(--bg-soft)" stroke-width="6"></circle>
      <circle cx="33" cy="33" r="${r}" fill="none" stroke="${color}" stroke-width="6" stroke-linecap="round"
        stroke-dasharray="${c.toFixed(1)}" stroke-dashoffset="${(c * (1 - v / 100)).toFixed(1)}"></circle>
    </svg>
    <div class="val" style="color:${color}">${v}</div>
  </div>`;
}

function analysisCard(ind) {
  if (!ind) {
    return `<section class="card analysis"><div class="analysis-head"><div class="txt"><div class="act">等待净值数据</div><div class="sum">打开后会自动拉取历史净值并计算指标。</div></div></div></section>`;
  }
  const v = ind.verdict || ind;
  const reasons = (v.reasons || []).map((r) => `<li>${esc(r)}</li>`).join('');
  return `<section class="card analysis">
    <div class="analysis-label"><span>规则判断</span><span>MA20 · RSI · 回撤</span></div>
    <div class="analysis-head">
      ${ring(v.score, v.action)}
      <div class="txt">
        <div class="act" style="color:${v.action === 'buy' ? 'var(--up)' : v.action === 'sell' ? 'var(--down)' : 'var(--hold)'}">${actionText(v.action)}</div>
        <div class="sum">${esc(v.summary || '')}</div>
      </div>
    </div>
    ${reasons ? `<ul class="reasons">${reasons}</ul>` : ''}
    <div class="kv">
      <div>MA20<b>${fmtNav(ind.ma20)}</b></div>
      <div>偏离 MA20<b>${ind.bias20 == null ? '—' : fmtPct(ind.bias20, 1)}</b></div>
      <div>RSI14<b>${ind.rsi14 == null ? '—' : ind.rsi14}</b></div>
      <div>支撑<b>${fmtNav(v.support)}</b></div>
      <div>阻力<b>${fmtNav(v.resistance)}</b></div>
      <div>建议仓位<b>${esc(v.position || '—')}</b></div>
    </div>
  </section>`;
}

function aiCard() {
  const ai = state.ai;
  const cfgOk = isConfigured(state.settings.ai);
  const head = `<div class="ai-head">
    <div class="logo">AI</div>
    <div class="t">AI 波段判断</div>
    <div class="badge">${ai ? (ai.fromCloud ? '云端 · ' : '本机 · ') + (ai.source === 'ai' ? esc(ai.model || 'AI') : '规则兜底') : '未运行'}</div>
  </div>`;

  if (state.aiLoading) {
    return `<section class="ai-card" id="aiCard">${head}
      <div class="ai-body">正在把 MA20、RSI、近 45 个交易日净值等数据发给 AI，请稍候…</div>
      <button class="btn btn-primary btn-block" disabled><span class="spin"></span>AI 正在判断</button>
    </section>`;
  }

  if (!ai) {
    return `<section class="ai-card" id="aiCard">${head}
      <div class="ai-body">让 AI 结合 MA20 位置、均线斜率、RSI 与回撤，判断未来 5-15 个交易日是否适合做一次波段。${cfgOk ? '' : '（还没配置 AI，会先用规则给出结论）'}</div>
      <button class="btn ${cfgOk ? 'btn-primary' : 'btn-ghost'} btn-block" data-action="ai">${cfgOk ? '让 AI 判断这次波段' : '配置 AI 后判断'}</button>
      ${state.aiError ? `<div class="ai-error">${esc(state.aiError)}</div>` : ''}
    </section>`;
  }

  const color = ai.action === 'buy' ? 'var(--up)' : ai.action === 'sell' ? 'var(--down)' : 'var(--hold)';
  const reasons = (ai.reasons || []).map((r) => `<li>${esc(r)}</li>`).join('');
  return `<section class="ai-card" id="aiCard">${head}
    <div class="ai-verdict">
      <div class="ai-action" style="color:${color}">${actionText(ai.action)}</div>
      <div class="ai-conf">
        <div class="bar"><i style="width:${Math.max(4, Math.min(100, ai.confidence || 0))}%"></i></div>
        <div class="bar-label"><span>信心</span><span>${ai.confidence || 0}%</span></div>
      </div>
    </div>
    <div class="ai-body">${esc(ai.summary || '')}</div>
    ${reasons ? `<ul class="reasons">${reasons}</ul>` : ''}
    <div class="kv">
      <div>建议仓位<b>${esc(ai.position || '—')}</b></div>
      <div>支撑<b>${fmtNav(ai.support)}</b></div>
      <div>阻力<b>${fmtNav(ai.resistance)}</b></div>
    </div>
    ${ai.risk ? `<div class="ai-body" style="color:var(--text-3);font-size:11.5px;margin-top:10px">风险：${esc(ai.risk)}</div>` : ''}
    ${ai.error ? `<div class="ai-error">${esc(ai.error)}</div>` : ''}
    <button class="btn ${cfgOk ? 'btn-ghost' : 'btn-primary'} btn-block" data-action="ai">${!cfgOk ? '配置 AI 后判断' : ai.source === 'ai' ? '重新判断' : '用 AI 再判断一次'}</button>
    <div class="ai-foot"><span>${ai.source === 'ai' ? 'AI 生成 · ' + esc(ai.model || '') : '规则判断 · 未调用 AI'}${ai.fromCloud ? ' · 每日 14:35' : ''}</span><span>${ai.at ? fmtTime(ai.at) : ''}</span></div>
    ${!cfgOk ? '<div class="ai-body" style="font-size:11.5px;color:var(--text-3);margin-top:8px">配好接口地址与 Key 后，这里可以随时让 AI 用最新数据复核一次。</div>' : ''}
  </section>`;
}

function renderDetail() {
  const fund = state.detail || findFund(state.code);
  el.title.textContent = '净值详情';
  el.sub.textContent = fund ? `${fund.name} · ${fund.code}` : '';
  el.back.classList.remove('hidden');
  el.action.classList.remove('hidden');
  el.action.classList.remove('spinning');
  el.action.setAttribute('data-action', 'live');
  el.tabbar.classList.remove('hidden');

  if (!fund) {
    el.view.innerHTML = `<div class="empty"><div class="big">🔍</div>没有找到这只基金</div>`;
    return;
  }

  const ind = state.ind || fund.ind || null;
  const nav = state.klines && state.klines.length ? state.klines[state.klines.length - 1] : null;
  const navValue = nav ? nav.c : fund.nav;
  const chg = nav && Number.isFinite(nav.chg) ? nav.chg : fund.chg;
  const navDate = nav ? nav.d : fund.navDate;

  el.view.innerHTML = `<div class="detail-head">
      <div><h2 class="detail-name">${esc(fund.name)}<span class="detail-code">${esc(fund.code)}</span></h2></div>
      <div class="detail-nav">
        <div class="big">${fmtNav(navValue)}</div>
        <div class="pill ${trendClass(chg)}">${chg == null ? '—' : fmtPct(chg)}</div>
      </div>
      <div class="detail-sub">
        <span>净值日期 ${esc(navDate || '—')}</span>
        <span>${esc(fund.type || fund.limitText || '')}</span>
        <button class="text-btn" data-action="live">拉取最新</button>
      </div>
    </div>

    <section class="chart-card">
      <div class="chart-toolbar">
        <div class="seg" id="rangeSeg">
          ${[
            ['3m', '3月'],
            ['6m', '6月'],
            ['1y', '1年'],
            ['all', '全部'],
          ]
            .map(([k, label]) => `<button data-action="range" data-range="${k}" class="${state.range === k ? 'active' : ''}">${label}</button>`)
            .join('')}
        </div>
      </div>
      <div class="legend" id="legend"></div>
      <div class="chart-wrap"><canvas id="kline" data-height="300"></canvas></div>
      <div class="chart-note">基金只有日频净值：开盘 = 前一日净值，收盘 = 当日净值，影线为当日涨跌区间。可拖动平移、双指或滚轮缩放。</div>
    </section>

    ${analysisCard(ind)}
    <div id="aiHost">${aiCard()}</div>
    <p class="disclaimer">判断基于历史净值的技术面，不含基本面与持仓信息。基金申赎 T+1 确认，波段周期按 5-15 个交易日估算。</p>`;

  mountChart(fund, ind);
  updateLegend(ind);
}

function updateLegend(ind, hover) {
  const box = $('#legend');
  if (!box) return;
  const src = hover || ind;
  if (!src) {
    box.innerHTML = '';
    return;
  }
  const g = (v) => (v == null ? '—' : fmtNav(v));
  const items = [
    ['MA5', src.ma5, 'var(--up)'],
    ['MA10', src.ma10, '#3b82f6'],
    ['MA20', src.ma20, '#8b5cf6'],
    ['MA60', src.ma60, 'var(--text-3)'],
  ];
  box.innerHTML = items
    .map(([label, v, color]) => `<span><i style="background:${color}"></i>${label} ${g(v)}</span>`)
    .join('');
}

function mountChart(fund, ind) {
  const canvas = $('#kline');
  if (!canvas) return;
  if (state.chart) {
    state.chart.destroy();
    state.chart = null;
  }
  const chart = new KLineChart(canvas, {
    theme: isDark() ? 'dark' : 'light',
    range: state.range,
    onHover: (k) => {
      if (k) updateLegend(ind, k);
      else updateLegend(ind);
    },
  });
  state.chart = chart;
  if (state.klines && state.klines.length) chart.setData(state.klines);
}

async function loadDetail(code) {
  const token = ++state.token;
  const fund = findFund(code);
  state.detail = fund;
  state.klines = null;
  state.ind = fund && fund.ind ? fund.ind : null;
  state.ai = null;
  state.aiError = '';
  state.aiLoading = false;

  // 云端今日判断优先展示
  if (state.aiLatest && Array.isArray(state.aiLatest.items)) {
    const item = state.aiLatest.items.find((i) => i.code === code);
    if (item) {
      state.ai = {
        ...item,
        at: state.aiLatest.at,
        model: state.aiLatest.model,
        fromCloud: true,
      };
    }
  }

  render();

  let klines = null;
  if (fund && fund.local) {
    const cached = store.loadLive(code);
    const fresh = cached && Date.now() - (cached.at || 0) < 6 * 3600 * 1000;
    if (cached && Array.isArray(cached.klines) && cached.klines.length && (fresh || !state.settings.liveRefresh)) {
      klines = cached.klines;
    } else {
      try {
        const live = await store.fetchLiveSeries(code);
        klines = live.klines;
        store.saveLive(code, { name: live.name, klines: live.klines });
        if (token === state.token) toast(`已载入 ${live.name || code} 的最新净值`);
      } catch (e) {
        if (token === state.token) toast(String(e.message || e));
      }
    }
  } else {
    const r = await store.loadKline(code);
    if (r.ok) klines = r.data.klines;
    else if (token === state.token) toast('净值历史加载失败：' + (r.error || ''));
  }

  if (token !== state.token) return;
  if (klines && klines.length) {
    state.klines = klines;
    state.ind = computeIndicators(klines).latest;
    render();
  }
}

async function refreshLive() {
  if (state.view !== 'detail' || !state.code) return;
  const code = state.code;
  el.action.classList.add('spinning');
  try {
    const live = await store.fetchLiveSeries(code);
    store.saveLive(code, { name: live.name, klines: live.klines });
    if (state.view !== 'detail' || state.code !== code) return;
    state.klines = live.klines;
    state.ind = computeIndicators(live.klines).latest;
    state.ai = null;
    render();
    toast(`已更新到 ${live.updatedAt}`);
  } catch (e) {
    toast('拉取失败：' + String(e.message || e));
  } finally {
    el.action.classList.remove('spinning');
  }
}

// ---------- AI 判断 ----------
async function runAI() {
  if (state.aiLoading) return;
  const fund = state.detail || findFund(state.code);
  if (!fund) return;
  if (!isConfigured(state.settings.ai)) {
    toast('先在设置里填写 AI 接口地址和 Key');
    location.hash = '#/settings';
    return;
  }
  if (!state.klines || !state.ind) {
    toast('净值数据还在加载');
    return;
  }
  state.aiLoading = true;
  state.aiError = '';
  render();

  const verdict = await judgeFund({
    fund: { code: fund.code, name: fund.name, type: fund.type, limitText: fund.limitText },
    latest: state.ind,
    series: computeIndicators(state.klines).series,
    klines: state.klines.slice(-400),
    config: state.settings.ai,
  });

  state.aiLoading = false;
  state.ai = verdict;
  if (verdict.source !== 'ai') state.aiError = verdict.error || '';
  if (state.view === 'detail') {
    const holder = document.getElementById('aiHost');
    if (holder) holder.innerHTML = aiCard();
    else render();
  }
}

// ---------- 设置 ----------
function settingsStatus() {
  const cfg = state.settings.ai;
  if (isConfigured(cfg)) return { cls: '', text: `已配置 · ${esc(cfg.model)}` };
  if (cfg.baseUrl) return { cls: 'warn', text: '还缺 API Key' };
  return { cls: 'off', text: '未配置' };
}

function renderSettings() {
  el.title.textContent = '设置';
  el.sub.textContent = 'AI 接入、推送与自选';
  el.back.classList.add('hidden');
  el.action.classList.add('hidden');
  el.tabbar.classList.remove('hidden');

  const cfg = state.settings.ai;
  const status = settingsStatus();
  const cloud = state.data;
  const presets = AI_PRESETS.map(
    (p) => `<button class="preset ${cfg.preset === p.id ? 'active' : ''}" data-action="preset" data-id="${p.id}">${esc(p.name)}</button>`
  ).join('');
  const theme = state.settings.theme || 'auto';

  el.view.innerHTML = `
  <section class="card settings-group">
    <div class="group-head"><h3>AI 判断</h3><span class="tag">本机</span></div>
    <div class="group-note">Key 只保存在这台设备的浏览器里，请求直接发往你填的接口，不经过任何中转。支持任意 OpenAI 兼容服务。</div>
    <div class="presets">${presets}</div>
    <div class="row"><span class="label">接口地址</span><input class="input mono" id="aiBase" value="${esc(cfg.baseUrl)}" placeholder="https://api.deepseek.com/v1" autocapitalize="off" spellcheck="false" /></div>
    <div class="row"><span class="label">API Key</span><input class="input mono" id="aiKey" type="password" value="${esc(cfg.apiKey)}" placeholder="sk-..." autocomplete="off" /></div>
    <div class="row"><span class="label">模型</span><input class="input mono" id="aiModel" value="${esc(cfg.model)}" placeholder="deepseek-chat" autocapitalize="off" spellcheck="false" /></div>
    <div class="inline-actions">
      <button class="btn btn-ghost" data-action="ai-test">测试连接</button>
      <button class="btn btn-primary" data-action="ai-save">保存配置</button>
    </div>
    <div class="status-line" id="aiStatus"><span class="status-dot ${status.cls}"></span>${status.text}</div>
  </section>

  <section class="card settings-group">
    <div class="group-head"><h3>每天 14:35 自动判断</h3><span class="tag">云端</span></div>
    <div class="group-note">GitHub Actions 到点自动跑：算指标 → 调 AI → 更新页面数据 → 推 Bark。不需要电脑开机，也不用 App 常驻。</div>
    <div class="status-line"><span class="status-dot ${cloud && cloud.aiEnabled ? '' : 'off'}"></span>${
      cloud && cloud.aiEnabled ? `已启用 · 最近一次 ${esc(cloud.updatedAtText || '')}` : '云端尚未配置 AI，目前用规则判断'
    }</div>
    <div class="row stack">
      <span class="label">云端需要 1 个 Secret + 2 个可选 Variable</span>
      <div class="group-note" style="padding:0">AI_KEY = 你的密钥（Secret）；AI_BASE_URL、AI_MODEL 可选填（Variables），默认 DeepSeek。不配也能跑，只是走规则判断。</div>
    </div>
    <div class="inline-actions">
      <button class="btn btn-ghost" data-action="copy-cloud">复制云端配置</button>
      <a class="btn btn-ghost" href="https://github.com/hukua1111/fund-radar/settings/secrets/actions" target="_blank" rel="noopener">打开 GitHub 设置</a>
    </div>
    <div class="inline-actions">
      <a class="btn btn-ghost" href="https://github.com/hukua1111/fund-radar/actions/workflows/daily-ai.yml" target="_blank" rel="noopener">手动跑一次判断</a>
    </div>
  </section>

  <section class="card settings-group">
    <div class="group-head"><h3>Bark 推送</h3></div>
    <div class="group-note">仓库里的 BARK_KEY 负责云端推送；这里填一份可以在手机上测试。</div>
    <div class="row"><span class="label">Bark Key</span><input class="input mono" id="barkKey" value="${esc(state.settings.barkKey || '')}" placeholder="设备 Key" autocapitalize="off" spellcheck="false" /></div>
    <div class="inline-actions"><button class="btn btn-ghost" data-action="bark-save">保存</button><button class="btn btn-ghost" data-action="bark-test">发一条测试</button></div>
  </section>

  <section class="card settings-group">
    <div class="group-head"><h3>关注列表</h3><span class="tag">${cloud ? cloud.funds.length : 0} 只云端</span></div>
    <div class="group-note">云端列表由仓库变量 FUNDS 控制；下面可以再加只保存在本机的基金，不会公开到仓库。</div>
    <div class="mini-list">
      ${(cloud ? cloud.funds : [])
        .map((f) => `<div class="mini-item"><span class="cd">${esc(f.code)}</span><span class="nm">${esc(f.name)}</span></div>`)
        .join('')}
      ${state.watch
        .map(
          (w) =>
            `<div class="mini-item"><span class="cd">${esc(w.code)}</span><span class="nm">${esc(w.name || '')}</span><button class="del" data-action="del-local" data-code="${esc(w.code)}">移除</button></div>`
        )
        .join('')}
    </div>
    <div class="add-row">
      <input class="input mono" id="addCode" inputmode="numeric" maxlength="6" placeholder="6 位基金代码" />
      <button class="btn btn-ghost" data-action="add-local">添加</button>
    </div>
  </section>

  <section class="card settings-group">
    <div class="group-head"><h3>外观与数据</h3></div>
    <div class="row"><span class="label">主题</span>
      <div class="seg" id="themeSeg">
        ${[
          ['auto', '跟随系统'],
          ['light', '浅色'],
          ['dark', '深色'],
        ]
          .map(([k, label]) => `<button data-action="theme" data-theme="${k}" class="${theme === k ? 'active' : ''}">${label}</button>`)
          .join('')}
      </div>
    </div>
    <div class="row"><span class="label">实时刷新</span>
      <div style="flex:1;font-size:11.5px;color:var(--text-3)">打开基金时顺便拉一次最新净值</div>
      <button class="switch ${state.settings.liveRefresh ? 'on' : ''}" data-action="toggle-live" aria-label="实时刷新"></button>
    </div>
    <div class="row"><span class="label">离线缓存</span>
      <div style="flex:1;font-size:11.5px;color:var(--text-3)">清空本地数据与 K 线缓存</div>
      <button class="btn" style="height:30px;font-size:11.5px;padding:0 10px" data-action="clear">清理</button>
    </div>
    <div class="inline-actions"><button class="btn btn-ghost" data-action="check-update">检查更新</button><button class="btn btn-ghost" data-action="install">添加到主屏幕</button></div>
  </section>

  <div class="about">
    <b>基金雷达 v${store.VERSION}</b><br />
    数据：东方财富公开接口 · 每小时不打扰，只在 14:35 判断一次<br />
    判断：MA5/10/20/60、RSI14、偏离率、回撤与 AI 复核<br />
    代码：github.com/hukua1111/fund-radar
  </div>`;

  bindSettingsInputs();
}

function bindSettingsInputs() {
  const base = $('#aiBase');
  const key = $('#aiKey');
  const model = $('#aiModel');
  const bark = $('#barkKey');
  const collect = () => ({
    ...state.settings.ai,
    baseUrl: base ? base.value.trim() : state.settings.ai.baseUrl,
    apiKey: key ? key.value.trim() : state.settings.ai.apiKey,
    model: model ? model.value.trim() : state.settings.ai.model,
  });
  [base, key, model].forEach((input) => {
    if (!input) return;
    input.addEventListener('change', () => {
      state.settings.ai = collect();
      store.saveSettings(state.settings);
    });
  });
  if (bark) {
    bark.addEventListener('change', () => {
      state.settings.barkKey = bark.value.trim();
      store.saveSettings(state.settings);
    });
  }
}

async function saveAI(showToast = true) {
  const base = $('#aiBase');
  const key = $('#aiKey');
  const model = $('#aiModel');
  state.settings.ai = {
    ...state.settings.ai,
    baseUrl: base ? normalizeBaseUrl(base.value.trim()).replace(/\/chat\/completions$/, '') : state.settings.ai.baseUrl,
    apiKey: key ? key.value.trim() : state.settings.ai.apiKey,
    model: model ? model.value.trim() : state.settings.ai.model,
  };
  store.saveSettings(state.settings);
  const st = settingsStatus();
  const box = $('#aiStatus');
  if (box) box.innerHTML = `<span class="status-dot ${st.cls}"></span>${st.text}`;
  if (showToast) toast('AI 配置已保存');
}

async function testAI() {
  await saveAI(false);
  const statusBox = $('#aiStatus');
  if (statusBox) statusBox.innerHTML = `<span class="status-dot warn"></span>正在连接…`;
  try {
    const r = await testConnection({ config: state.settings.ai });
    if (statusBox) statusBox.innerHTML = `<span class="status-dot"></span>连接正常 · ${esc(r.model)} · ${r.ms}ms`;
    toast('AI 连接正常');
  } catch (e) {
    const msg = String(e.message || e);
    if (statusBox) statusBox.innerHTML = `<span class="status-dot warn"></span>${esc(msg)}`;
    toast('连接失败：' + msg, 4000);
  }
}

// ---------- 本地自选 ----------
async function addLocal() {
  const input = $('#addCode');
  const code = (input && input.value.trim()) || '';
  if (!/^\d{6}$/.test(code)) {
    toast('请输入 6 位基金代码');
    return;
  }
  if (state.watch.some((w) => w.code === code) || cloudFunds().some((f) => f.code === code)) {
    toast('这只基金已经在列表里了');
    return;
  }
  toast('正在获取 ' + code + ' 的净值…', 6000);
  try {
    const live = await store.fetchLiveSeries(code);
    state.watch = [...state.watch, { code, name: live.name || code }];
    store.saveWatch(state.watch);
    store.saveLive(code, { name: live.name, klines: live.klines });
    toast(`已添加 ${live.name || code}`);
    render();
  } catch (e) {
    toast('添加失败：' + String(e.message || e), 3200);
  }
}

// ---------- 路由与渲染 ----------
function render() {
  el.topbar.classList.toggle('scrolled', window.scrollY > 4);
  if (state.view !== 'detail' && state.chart) {
    state.chart.destroy();
    state.chart = null;
  }
  const activeTab = state.view === 'settings' ? 'settings' : 'list';
  el.tabbar.querySelectorAll('[data-tab]').forEach((b) => b.classList.toggle('active', b.getAttribute('data-tab') === activeTab));
  if (state.view === 'list') renderList();
  else if (state.view === 'detail') renderDetail();
  else renderSettings();
}

function route() {
  const h = (location.hash || '#/').replace(/^#/, '');
  const prevView = state.view;
  if (h.startsWith('/f/')) {
    const code = h.slice(3);
    state.view = 'detail';
    state.code = code;
    if (prevView !== 'detail' || state.detail == null || state.detail.code !== code) loadDetail(code);
    else render();
  } else if (h.startsWith('/settings')) {
    state.view = 'settings';
    render();
  } else {
    state.view = 'list';
    state.code = null;
    state.detail = null;
    if (state.chart) {
      state.chart.destroy();
      state.chart = null;
    }
    render();
  }
  window.scrollTo({ top: 0 });
}

// ---------- 事件 ----------
document.addEventListener('click', async (e) => {
  const target = e.target.closest('[data-action]');
  if (!target) return;
  const action = target.getAttribute('data-action');

  if (action === 'open') {
    location.hash = `#/f/${target.getAttribute('data-code')}`;
    return;
  }
  if (action === 'refresh') {
    if (state.view === 'detail') return refreshLive();
    el.action.classList.add('spinning');
    await loadAll(true);
    el.action.classList.remove('spinning');
    return;
  }
  if (action === 'live') {
    refreshLive();
    return;
  }
  if (action === 'range') {
    state.range = target.getAttribute('data-range');
    document.querySelectorAll('#rangeSeg button').forEach((b) => b.classList.toggle('active', b === target));
    if (state.chart) state.chart.applyRange(state.range);
    return;
  }
  if (action === 'ai') {
    runAI();
    return;
  }
  if (action === 'ai-save') {
    saveAI();
    return;
  }
  if (action === 'ai-test') {
    testAI();
    return;
  }
  if (action === 'preset') {
    const id = target.getAttribute('data-id');
    const preset = AI_PRESETS.find((p) => p.id === id);
    if (!preset) return;
    state.settings.ai.preset = id;
    if (preset.baseUrl) {
      const base = $('#aiBase');
      const model = $('#aiModel');
      if (base) base.value = preset.baseUrl;
      if (model) model.value = preset.model;
      state.settings.ai.baseUrl = preset.baseUrl;
      state.settings.ai.model = preset.model;
      store.saveSettings(state.settings);
    }
    document.querySelectorAll('.preset').forEach((b) => b.classList.toggle('active', b === target));
    return;
  }
  if (action === 'bark-save') {
    const input = $('#barkKey');
    state.settings.barkKey = input ? input.value.trim() : '';
    store.saveSettings(state.settings);
    toast('Bark Key 已保存');
    return;
  }
  if (action === 'bark-test') {
    const input = $('#barkKey');
    if (input) state.settings.barkKey = input.value.trim();
    store.saveSettings(state.settings);
    try {
      await store.sendBark(state.settings.barkKey, '基金雷达', '这是一条测试推送，收到就说明配置好了。');
      toast('已发送，检查手机通知');
    } catch (err) {
      toast(String(err.message || err), 3600);
    }
    return;
  }
  if (action === 'add-local') {
    addLocal();
    return;
  }
  if (action === 'del-local') {
    const code = target.getAttribute('data-code');
    state.watch = state.watch.filter((w) => w.code !== code);
    store.saveWatch(state.watch);
    render();
    toast('已移除');
    return;
  }
  if (action === 'theme') {
    state.settings.theme = target.getAttribute('data-theme');
    store.saveSettings(state.settings);
    applyTheme();
    document.querySelectorAll('#themeSeg button').forEach((b) => b.classList.toggle('active', b === target));
    return;
  }
  if (action === 'toggle-live') {
    state.settings.liveRefresh = !state.settings.liveRefresh;
    store.saveSettings(state.settings);
    target.classList.toggle('on', state.settings.liveRefresh);
    toast(state.settings.liveRefresh ? '打开基金时会拉取最新净值' : '只用每日快照');
    return;
  }
  if (action === 'clear') {
    store.clearCaches();
    toast('缓存已清理，正在重新加载');
    setTimeout(() => location.reload(), 600);
    return;
  }
  if (action === 'check-update') {
    await checkUpdate(true);
    return;
  }
  if (action === 'install') {
    installApp();
    return;
  }
  if (action === 'copy-cloud') {
    const cfg = state.settings.ai;
    const text = `AI_BASE_URL=${normalizeBaseUrl(cfg.baseUrl).replace(/\/chat\/completions$/, '')}\nAI_MODEL=${cfg.model}\nAI_KEY=你的密钥`;
    try {
      await navigator.clipboard.writeText(text);
      toast('已复制，粘贴到仓库的 Variables / Secrets');
    } catch (err) {
      toast('复制失败，请手动复制：' + text.slice(0, 40));
    }
  }
});

el.back.addEventListener('click', () => {
  if (history.length > 1) history.back();
  else location.hash = '#/';
});

el.tabbar.addEventListener('click', (e) => {
  const btn = e.target.closest('[data-tab]');
  if (!btn) return;
  location.hash = btn.getAttribute('data-tab') === 'settings' ? '#/settings' : '#/';
});

window.addEventListener('hashchange', route);
window.addEventListener('scroll', () => el.topbar.classList.toggle('scrolled', window.scrollY > 4), { passive: true });
window.addEventListener('online', () => {
  state.online = true;
  toast('网络已恢复');
});
window.addEventListener('offline', () => {
  state.online = false;
  toast('已离线，显示本地缓存');
});

// ---------- PWA ----------
async function checkUpdate(manual = false) {
  try {
    const reg = state.swReg || (await navigator.serviceWorker.getRegistration());
    if (!reg) {
      if (manual) toast('当前环境不支持自动更新');
      return;
    }
    await reg.update();
    if (reg.waiting) {
      reg.waiting.postMessage({ type: 'SKIP_WAITING' });
      toast('发现新版本，正在更新…');
      setTimeout(() => location.reload(), 900);
    } else if (manual) {
      toast('已是最新版本');
    }
  } catch (e) {
    if (manual) toast('检查更新失败：' + String(e.message || e));
  }
}

let deferredPrompt = null;
window.addEventListener('beforeinstallprompt', (e) => {
  e.preventDefault();
  deferredPrompt = e;
});

async function installApp() {
  if (deferredPrompt) {
    deferredPrompt.prompt();
    const { outcome } = await deferredPrompt.userChoice;
    deferredPrompt = null;
    toast(outcome === 'accepted' ? '已添加到主屏幕' : '已取消');
    return;
  }
  const isIOS = /iphone|ipad|ipod/i.test(navigator.userAgent);
  toast(
    isIOS
      ? '在 Safari 里点「分享」→「添加到主屏幕」即可安装'
      : '打开浏览器菜单，选择「安装应用 / 添加到主屏幕」',
    4200
  );
}

async function registerSW() {
  if (!('serviceWorker' in navigator)) return;
  try {
    const reg = await navigator.serviceWorker.register('./sw.js', { scope: './' });
    state.swReg = reg;
    reg.addEventListener('updatefound', () => {
      const sw = reg.installing;
      if (!sw) return;
      sw.addEventListener('statechange', () => {
        if (sw.state === 'installed' && navigator.serviceWorker.controller) {
          sw.postMessage({ type: 'SKIP_WAITING' });
        }
      });
    });
    const hadController = Boolean(navigator.serviceWorker.controller);
    let reloaded = false;
    navigator.serviceWorker.addEventListener('controllerchange', () => {
      if (!hadController || reloaded) return;
      reloaded = true;
      if (sessionStorage.getItem('fr.reloaded') === '1') return;
      sessionStorage.setItem('fr.reloaded', '1');
      location.reload();
    });
  } catch (e) {
    /* 离线或 file:// 环境忽略 */
  }
}

// ---------- 启动 ----------
async function boot() {
  applyTheme();
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    if ((state.settings.theme || 'auto') === 'auto') {
      applyTheme();
      if (state.chart) renderDetail();
    }
  });
  await loadAll(false);
  route();
  registerSW();
}

boot();
