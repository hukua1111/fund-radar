// 基金限额监控 · 核心逻辑
// 与运行平台无关，Cloudflare Worker 和 GitHub Actions 共用。

export const API = 'https://fundmobapi.eastmoney.com/FundMNewApi/FundMNNBasicInformation';

// 历史净值（日频），同样只认 App UA
export const HISTORY_API = 'https://fundmobapi.eastmoney.com/FundMNewApi/FundMNHisNetList';

// 关键：东方财富按 UA 黑名单拦截浏览器。必须用 App 身份请求，否则返回「网络繁忙」。
export const UA = 'EMProjJijin/6.2.8 (iPhone; iOS 16.0; Scale/3.00)';

const UNLIMITED = 1e11;

export function parseCodes(raw) {
  if (!raw) return [];
  const out = [];
  const seen = new Set();
  for (const part of String(raw).split(/[\s,，;；、]+/)) {
    const c = part.trim();
    if (/^\d{6}$/.test(c) && !seen.has(c)) {
      seen.add(c);
      out.push(c);
    }
  }
  // 上限 30：Cloudflare 免费版单次执行最多 50 个子请求，留出推送和重试的余量
  return out.slice(0, 30);
}

function thousands(n) {
  return String(Math.round(n)).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

export function describe(rec) {
  const sgzt = rec.sgzt || '';
  if (sgzt === '暂停申购' || sgzt === '暂停交易') return '暂停申购';

  const raw = rec.maxsg;
  if (raw === null || raw === undefined || raw === '') return sgzt || '未知';

  const n = Number(raw);
  if (!Number.isFinite(n)) return sgzt || '未知';
  if (n >= UNLIMITED) return `${sgzt || '开放申购'} · 不限`;

  return `${sgzt} · ${thousands(n)} 元`;
}

export async function fetchFund(code, fetchImpl = fetch) {
  const url = `${API}?FCODE=${code}&deviceid=Wap&plat=Wap&product=EFund&version=2.0.0`;
  try {
    const res = await fetchImpl(url, {
      headers: { 'User-Agent': UA, Accept: 'application/json' },
    });
    if (!res.ok) return { code, ok: false, error: `HTTP ${res.status}` };
    const data = await res.json();
    const d = data && data.Datas;
    if (!d) return { code, ok: false, error: (data && data.ErrMsg) || '无数据' };
    return {
      code,
      ok: true,
      name: d.SHORTNAME || code,
      type: d.FTYPE || '',
      sgzt: d.SGZT || '',
      maxsg: d.MAXSG,
      minsg: d.MINSG,
      nav: d.DWJZ,
      navDate: d.PDATE || '',
    };
  } catch (e) {
    return { code, ok: false, error: String((e && e.message) || e).slice(0, 120) };
  }
}

/**
 * 拉取一只基金的历史净值（升序）。
 * 接口按日期倒序返回，这里统一反转，方便后面算均线。
 */
export async function fetchHistory(code, opts = {}) {
  const { pageSize = 500, fetchImpl = fetch } = opts;
  const url = `${HISTORY_API}?FCODE=${code}&pageIndex=1&pageSize=${pageSize}&deviceid=Wap&plat=Wap&product=EFund&version=2.0.0`;
  try {
    const res = await fetchImpl(url, {
      headers: { 'User-Agent': UA, Accept: 'application/json' },
    });
    if (!res.ok) return { code, ok: false, error: `HTTP ${res.status}` };
    const data = await res.json();
    const list = data && data.Datas;
    if (!Array.isArray(list) || !list.length) {
      return { code, ok: false, error: (data && data.ErrMsg) || '无历史净值' };
    }
    const klines = list
      .map((d) => ({
        d: String(d.FSRQ || '').slice(0, 10),
        c: Number(d.DWJZ),
        chg: d.JZZZL === '--' || d.JZZZL == null ? null : Number(d.JZZZL),
        acc: Number(d.LJJZ) || null,
      }))
      .filter((k) => k.d && Number.isFinite(k.c) && k.c > 0)
      .sort((a, b) => (a.d < b.d ? -1 : a.d > b.d ? 1 : 0));
    if (!klines.length) return { code, ok: false, error: '历史净值解析为空' };
    return { code, ok: true, klines };
  } catch (e) {
    return { code, ok: false, error: String((e && e.message) || e).slice(0, 120) };
  }
}

export function diffSnapshots(prev, items) {
  const changes = [];
  const next = {};
  const failed = [];

  for (const it of items) {
    if (!it || !it.ok) {
      const code = it && it.code;
      if (code) {
        failed.push(code);
        // 抓取失败的保留旧记录，避免下次被误判为「变化」
        if (prev && prev[code]) next[code] = prev[code];
      }
      continue;
    }

    const sig = `${it.sgzt}|${it.maxsg}`;
    const old = prev && prev[it.code];

    if (old && old.sig && old.sig !== sig) {
      changes.push({
        code: it.code,
        name: it.name,
        fromText: describe(old),
        toText: describe({ sgzt: it.sgzt, maxsg: it.maxsg }),
      });
    }

    next[it.code] = {
      name: it.name,
      sgzt: it.sgzt,
      maxsg: it.maxsg,
      sig,
      nav: it.nav,
      at: Date.now(),
    };
  }

  return { changes, next, failed };
}

export async function pushBark(key, title, body, fetchImpl = fetch) {
  if (!key) return { ok: false, error: '未配置 BARK_KEY' };
  try {
    const res = await fetchImpl('https://api.day.app/push', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        device_key: key,
        title,
        body,
        group: '基金限额',
        level: 'active',
      }),
    });
    const text = await res.text();
    return { ok: res.ok, status: res.status, raw: text.slice(0, 200) };
  } catch (e) {
    return { ok: false, error: String((e && e.message) || e).slice(0, 120) };
  }
}

/**
 * 跑一次检查。
 * @param {{codes:string[], barkKey?:string, prevRaw?:string|null, alwaysNotify?:boolean, fetchImpl?:Function}} opts
 */
export async function runCheck(opts) {
  const { codes = [], barkKey, prevRaw = null, alwaysNotify = false, fetchImpl = fetch } = opts;

  if (!codes.length) {
    return { ok: false, error: '没有配置基金代码' };
  }

  let prev = {};
  if (prevRaw) {
    try {
      prev = JSON.parse(prevRaw) || {};
    } catch (e) {
      prev = {};
    }
  }

  const isFirstRun = Object.keys(prev).length === 0;

  const items = await Promise.all(codes.map((c) => fetchFund(c, fetchImpl)));
  const { changes, next, failed } = diffSnapshots(prev, items);

  let pushed = null;

  if (changes.length) {
    const title = `基金限额变动 ${changes.length} 只`;
    const body = changes.map((c) => `${c.name}：${c.fromText} → ${c.toText}`).join('\n');
    pushed = await pushBark(barkKey, title, body, fetchImpl);
  } else if (isFirstRun && barkKey) {
    const okCount = Object.keys(next).length;
    pushed = await pushBark(
      barkKey,
      '基金限额监控已启动',
      `已建立基线，共 ${okCount} 只基金。之后每天检查，只有限额或申购状态变化时才会提醒。`,
      fetchImpl
    );
  } else if (alwaysNotify && barkKey) {
    const okCount = Object.keys(next).length;
    const body = failed.length
      ? `${okCount} 只基金限额无变化（${failed.length} 只获取失败）`
      : `${okCount} 只基金限额无变化`;
    pushed = await pushBark(barkKey, '基金限额每日检查', body, fetchImpl);
  }

  return {
    ok: true,
    checked: codes.length,
    succeeded: Object.keys(next).length,
    failed,
    changes,
    isFirstRun,
    pushed,
    next,
  };
}
