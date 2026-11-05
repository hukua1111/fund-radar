// 在 Worker 里加载东方财富的净值脚本，只回传整理后的数组。
// 脚本执行环境拿不到页面的 localStorage / DOM / AI Key。

self.onmessage = (e) => {
  const { id, code, ts } = e.data || {};
  if (!/^\d{6}$/.test(String(code || ''))) {
    self.postMessage({ id, ok: false, error: '基金代码不合法' });
    return;
  }
  try {
    importScripts(`https://fund.eastmoney.com/pingzhongdata/${code}.js?v=${ts || Date.now()}`);
    /* global Data_netWorthTrend, fS_name */
    const trend = typeof Data_netWorthTrend !== 'undefined' ? Data_netWorthTrend : null;
    if (!Array.isArray(trend) || !trend.length) {
      self.postMessage({ id, ok: false, error: '没有取到该基金的净值数据' });
      return;
    }
    const klines = [];
    for (const p of trend) {
      const c = Number(p && p.y);
      if (!Number.isFinite(c) || c <= 0 || !p.x) continue;
      klines.push({
        d: new Date(p.x + 8 * 3600 * 1000).toISOString().slice(0, 10),
        c,
        chg: Number.isFinite(Number(p.equityReturn)) ? Number(p.equityReturn) : null,
      });
    }
    if (!klines.length) {
      self.postMessage({ id, ok: false, error: '净值数据为空' });
      return;
    }
    self.postMessage({
      id,
      ok: true,
      code,
      name: typeof fS_name !== 'undefined' ? String(fS_name) : '',
      updatedAt: klines[klines.length - 1].d,
      klines,
    });
  } catch (err) {
    self.postMessage({ id, ok: false, error: String((err && err.message) || err).slice(0, 120) });
  }
};
