// 本地存储、数据获取、离线缓存
// 云端快照来自 docs/data（Actions 每天生成），本地自选基金走东方财富的净值脚本。

export const VERSION = '2.0.0';

const K = {
  settings: 'fr.settings.v1',
  watch: 'fr.watch.v1',
  fundsCache: 'fr.funds.v1',
  klineCache: 'fr.kline.v1',
  aiCache: 'fr.ai.v1',
};

export const DEFAULT_SETTINGS = {
  ai: { preset: 'deepseek', baseUrl: 'https://api.deepseek.com/v1', apiKey: '', model: 'deepseek-chat' },
  barkKey: '',
  theme: 'auto',
  liveRefresh: true,
};

function read(key, fallback) {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return fallback;
    return JSON.parse(raw);
  } catch (e) {
    return fallback;
  }
}

function write(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
    return true;
  } catch (e) {
    return false;
  }
}

export function loadSettings() {
  const s = read(K.settings, {});
  return {
    ...DEFAULT_SETTINGS,
    ...s,
    ai: { ...DEFAULT_SETTINGS.ai, ...(s.ai || {}) },
  };
}

export function saveSettings(next) {
  const merged = {
    ...DEFAULT_SETTINGS,
    ...next,
    ai: { ...DEFAULT_SETTINGS.ai, ...(next.ai || {}) },
  };
  write(K.settings, merged);
  return merged;
}

export function loadWatch() {
  const list = read(K.watch, []);
  return Array.isArray(list) ? list.filter((f) => f && /^\d{6}$/.test(f.code)) : [];
}

export function saveWatch(list) {
  write(K.watch, list);
}

export function clearCaches() {
  try {
    localStorage.removeItem(K.fundsCache);
    localStorage.removeItem(K.klineCache);
    localStorage.removeItem(K.aiCache);
  } catch (e) {
    /* 忽略 */
  }
}

export function baseUrl(path) {
  return new URL(path, document.baseURI).toString();
}

async function fetchJSON(path, { timeout = 12000 } = {}) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeout);
  try {
    const res = await fetch(baseUrl(path), { signal: ctrl.signal, cache: 'no-cache' });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return await res.json();
  } finally {
    clearTimeout(timer);
  }
}

/** 云端快照：优先网络，失败时用上一次的本地缓存 */
export async function loadFundsSnapshot() {
  try {
    const data = await fetchJSON('data/funds.json');
    write(K.fundsCache, { at: Date.now(), data });
    return { ok: true, data, cached: false };
  } catch (e) {
    const cached = read(K.fundsCache, null);
    if (cached && cached.data) return { ok: true, data: cached.data, cached: true, error: String(e.message || e) };
    return { ok: false, error: String(e.message || e) };
  }
}

export async function loadAILatest() {
  try {
    const data = await fetchJSON('data/ai/latest.json');
    write(K.aiCache, { at: Date.now(), data });
    return data;
  } catch (e) {
    const cached = read(K.aiCache, null);
    return cached && cached.data ? cached.data : null;
  }
}

/** 单只基金的历史净值（云端快照，覆盖近 400 个交易日） */
export async function loadKline(code) {
  const cacheKey = `${K.klineCache}.${code}`;
  try {
    const data = await fetchJSON(`data/kline/${code}.json`, { timeout: 15000 });
    try {
      localStorage.setItem(cacheKey, JSON.stringify({ at: Date.now(), data }));
    } catch (e) {
      /* 容量满时忽略 */
    }
    return { ok: true, data, cached: false };
  } catch (e) {
    const cached = read(cacheKey, null);
    if (cached && cached.data) return { ok: true, data: cached.data, cached: true };
    return { ok: false, error: String(e.message || e) };
  }
}

// ---- 浏览器直连东方财富（用于“拉取最新净值”和本地自选基金） ----
// 通过 Worker 加载净值脚本：脚本在独立作用域里执行，拿不到 localStorage 和 DOM。

let worker = null;
let seq = 0;
const pending = new Map();

function ensureWorker() {
  if (worker) return worker;
  worker = new Worker(baseUrl('lib/live-worker.js'));
  worker.onmessage = (e) => {
    const { id, ...rest } = e.data || {};
    const task = pending.get(id);
    if (!task) return;
    pending.delete(id);
    clearTimeout(task.timer);
    if (rest.ok) task.resolve(rest);
    else task.reject(new Error(rest.error || '取数失败'));
  };
  worker.onerror = () => {
    for (const [, task] of pending) {
      clearTimeout(task.timer);
      task.reject(new Error('数据脚本加载失败'));
    }
    pending.clear();
    worker = null;
  };
  return worker;
}

export function fetchLiveSeries(code) {
  return new Promise((resolve, reject) => {
    let w;
    try {
      w = ensureWorker();
    } catch (e) {
      reject(new Error('当前浏览器不支持刷新，请使用云端快照'));
      return;
    }
    const id = ++seq;
    const timer = setTimeout(() => {
      pending.delete(id);
      try {
        w.terminate();
      } catch (e) {
        /* 忽略 */
      }
      worker = null;
      reject(new Error('刷新超时，稍后再试'));
    }, 15000);
    pending.set(id, { resolve, reject, timer });
    w.postMessage({ id, code, ts: Date.now() });
  });
}

/** 本地自选基金的实时净值缓存 */
export function saveLive(code, payload) {
  try {
    localStorage.setItem(`fr.live.${code}`, JSON.stringify({ at: Date.now(), ...payload }));
  } catch (e) {
    /* 忽略 */
  }
}

export function loadLive(code) {
  return read(`fr.live.${code}`, null);
}

/** 浏览器推送测试（Bark 允许跨域） */
export async function sendBark(key, title, body) {
  if (!key) throw new Error('还没有填 Bark Key');
  const res = await fetch('https://api.day.app/push', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ device_key: key, title, body, group: '基金波段', level: 'active' }),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`Bark 返回 ${res.status}：${text.slice(0, 120)}`);
  return text.slice(0, 160);
}
