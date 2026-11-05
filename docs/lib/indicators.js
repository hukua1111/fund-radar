// 技术指标与规则判断
// 浏览器（docs/）和 Node（src/daily-ai.js）共用同一份实现，避免两端算法不一致。

/** 简单移动平均，返回与输入等长的数组，前 n-1 项为 null */
export function sma(values, n) {
  const out = new Array(values.length).fill(null);
  let sum = 0;
  for (let i = 0; i < values.length; i++) {
    sum += values[i];
    if (i >= n) sum -= values[i - n];
    if (i >= n - 1) out[i] = sum / n;
  }
  return out;
}

/** Wilder RSI */
export function rsiSeries(values, n = 14) {
  const out = new Array(values.length).fill(null);
  if (values.length <= n) return out;
  let gain = 0;
  let loss = 0;
  for (let i = 1; i <= n; i++) {
    const d = values[i] - values[i - 1];
    if (d >= 0) gain += d;
    else loss -= d;
  }
  let avgGain = gain / n;
  let avgLoss = loss / n;
  out[n] = avgLoss === 0 ? 100 : 100 - 100 / (1 + avgGain / avgLoss);
  for (let i = n + 1; i < values.length; i++) {
    const d = values[i] - values[i - 1];
    avgGain = (avgGain * (n - 1) + (d > 0 ? d : 0)) / n;
    avgLoss = (avgLoss * (n - 1) + (d < 0 ? -d : 0)) / n;
    out[i] = avgLoss === 0 ? 100 : 100 - 100 / (1 + avgGain / avgLoss);
  }
  return out;
}

export function stdev(arr) {
  if (arr.length < 2) return 0;
  const mean = arr.reduce((a, b) => a + b, 0) / arr.length;
  const v = arr.reduce((a, b) => a + (b - mean) * (b - mean), 0) / (arr.length - 1);
  return Math.sqrt(v);
}

export function smaSeries(klines) {
  const closes = klines.map((k) => k.c);
  return {
    closes,
    ma5: sma(closes, 5),
    ma10: sma(closes, 10),
    ma20: sma(closes, 20),
    ma60: sma(closes, 60),
    rsi14: rsiSeries(closes, 14),
  };
}

const pct = (v) => (Number.isFinite(v) ? Math.round(v * 1000) / 10 : null);

function crossInfo(ma5, ma20) {
  const n = ma5.length;
  for (let i = Math.max(1, n - 6); i < n; i++) {
    if (ma5[i] == null || ma20[i] == null || ma5[i - 1] == null || ma20[i - 1] == null) continue;
    const prev = ma5[i - 1] - ma20[i - 1];
    const cur = ma5[i] - ma20[i];
    if (prev <= 0 && cur > 0) return { type: 'golden', days: n - 1 - i };
    if (prev >= 0 && cur < 0) return { type: 'death', days: n - 1 - i };
  }
  return { type: 'none', days: null };
}

/**
 * 计算一只基金的最新指标。
 * @param {{d:string,c:number,chg?:number}[]} klines 按日期升序
 */
export function computeIndicators(klines) {
  const clean = (klines || []).filter((k) => k && Number.isFinite(k.c) && k.c > 0);
  const n = clean.length;
  const s = smaSeries(clean);
  const i = n - 1;
  const price = n ? s.closes[i] : null;
  const ma20 = n ? s.ma20[i] : null;
  const ma60 = n ? s.ma60[i] : null;

  const win = (arr, len) => (len <= 0 ? [] : arr.slice(Math.max(0, arr.length - len)));
  const last20 = win(s.closes, 20);
  const last60 = win(s.closes, 60);

  const high20 = last20.length ? Math.max(...last20) : null;
  const low20 = last20.length ? Math.min(...last20) : null;
  const high60 = last60.length ? Math.max(...last60) : null;
  const low60 = last60.length ? Math.min(...last60) : null;

  const chgs = win(clean, 21)
    .slice(1)
    .map((k, idx, arr) => {
      if (Number.isFinite(k.chg)) return k.chg / 100;
      return idx > 0 ? k.c / arr[idx - 1].c - 1 : 0;
    });
  const vol20 = chgs.length > 2 ? stdev(chgs) * Math.sqrt(252) * 100 : null;

  const latest = {
    price: price == null ? null : Math.round(price * 10000) / 10000,
    date: n ? clean[i].d : null,
    chg: n && Number.isFinite(clean[i].chg) ? clean[i].chg : null,
    ma5: s.ma5[i] == null ? null : Math.round(s.ma5[i] * 10000) / 10000,
    ma10: s.ma10[i] == null ? null : Math.round(s.ma10[i] * 10000) / 10000,
    ma20: ma20 == null ? null : Math.round(ma20 * 10000) / 10000,
    ma60: ma60 == null ? null : Math.round(ma60 * 10000) / 10000,
    rsi14: s.rsi14[i] == null ? null : Math.round(s.rsi14[i] * 10) / 10,
    bias20: price != null && ma20 ? pct(price / ma20 - 1) : null,
    slope20:
      ma20 != null && i >= 5 && s.ma20[i - 5] ? pct(ma20 / s.ma20[i - 5] - 1) : null,
    pos20: price != null && high20 != null && high20 > low20 ? pct((price - low20) / (high20 - low20)) : null,
    dd20: price != null && high20 ? pct(price / high20 - 1) : null,
    dd60: price != null && high60 ? pct(price / high60 - 1) : null,
    high20,
    low20,
    high60,
    low60,
    vol20: vol20 == null ? null : Math.round(vol20 * 10) / 10,
    support: low20,
    resistance: high20,
    trend: ma20 != null && ma60 != null ? (ma20 >= ma60 ? 'up' : 'down') : null,
    cross: crossInfo(s.ma5, s.ma20),
    bars: n,
  };

  latest.verdict = ruleVerdict(latest);
  return { series: s, latest };
}

function clamp(v, lo, hi) {
  return Math.max(lo, Math.min(hi, v));
}

/**
 * 纯规则的波段判断：可解释、无网络依赖。
 * 分数 0-100：>=62 偏买，<=38 偏卖，其余观望。
 */
export function ruleVerdict(latest) {
  const L = latest || {};
  if (L.price == null || L.ma20 == null) {
    return {
      action: 'hold',
      score: 50,
      confidence: 30,
      summary: '历史数据不足 20 个交易日，暂不判断',
      reasons: ['净值样本不足，无法计算 MA20'],
      position: '观望',
      support: L.support ?? null,
      resistance: L.resistance ?? null,
      risk: '数据不足',
      horizon: '5-15 个交易日',
    };
  }

  let score = 50;
  const factors = [];
  const add = (delta, text) => {
    score += delta;
    factors.push({ delta, text });
  };

  const bias = L.bias20 ?? 0;
  if (L.price >= L.ma20) add(8, `站上 MA20（偏离 ${bias > 0 ? '+' : ''}${bias}%）`);
  else add(-8, `位于 MA20 下方（偏离 ${bias}%）`);

  const slope = L.slope20;
  if (slope != null) {
    if (slope >= 0.6) add(10, `MA20 近 5 日上行 ${slope}%，短期趋势抬升`);
    else if (slope > 0) add(4, `MA20 缓步上行（${slope}%）`);
    else if (slope > -0.6) add(-4, `MA20 走平偏弱（${slope}%）`);
    else add(-10, `MA20 近 5 日下行 ${slope}%，趋势偏空`);
  }

  if (L.ma5 != null && L.ma20 != null) {
    if (L.ma5 > L.ma20) add(6, 'MA5 位于 MA20 上方，短期多头排列');
    else add(-6, 'MA5 位于 MA20 下方，短期空头排列');
  }

  if (L.cross && L.cross.type === 'golden') add(7, `MA5 上穿 MA20（${L.cross.days} 个交易日内金叉）`);
  else if (L.cross && L.cross.type === 'death') add(-7, `MA5 下穿 MA20（${L.cross.days} 个交易日内死叉）`);

  if (bias >= -3 && bias <= 2) add(8, '价格贴近 MA20，波段介入位置较好');
  else if (bias > 2 && bias <= 6) add(3, '略高于 MA20，仍属可接受区间');
  else if (bias > 6 && bias <= 10) add(-5, `偏离 MA20 达 ${bias}%，追高风险上升`);
  else if (bias > 10) add(-12, `偏离 MA20 达 ${bias}%，均值回归风险高`);
  else if (bias >= -8 && bias < -3) add(2, `略低于 MA20（${bias}%），等待企稳`);
  else if (bias >= -12 && bias < -8) add(-2, `低于 MA20 达 ${bias}%，弱势`);
  else if (bias < -12) add(-7, `远离 MA20（${bias}%），处于下跌趋势`);

  const rsi = L.rsi14;
  if (rsi != null) {
    if (rsi < 30) add(8, `RSI14 ${rsi}，超卖区`);
    else if (rsi < 45) add(5, `RSI14 ${rsi}，偏冷，具备修复空间`);
    else if (rsi <= 58) add(2, `RSI14 ${rsi}，中性偏暖`);
    else if (rsi <= 70) add(-2, `RSI14 ${rsi}，偏热`);
    else add(-10, `RSI14 ${rsi}，超买区，短期回调概率上升`);
  }

  const dd60 = L.dd60;
  if (dd60 != null) {
    if (dd60 >= -5) add(3, '接近 60 日高点，走势强势');
    else if (dd60 >= -12) add(1, `距 60 日高点 ${dd60}%，正常回撤`);
    else if (dd60 >= -25) add(-2, `较 60 日高点回撤 ${dd60}%`);
    else add(-5, `较 60 日高点回撤 ${dd60}%，趋势受损`);
  }

  if (L.trend === 'up') add(6, 'MA20 站上 MA60，中期结构偏多');
  else if (L.trend === 'down') add(-6, 'MA20 仍在 MA60 下方，中期尚未转强');

  score = Math.round(clamp(score, 0, 100));

  let action = 'hold';
  if (score >= 62) action = 'buy';
  else if (score <= 38) action = 'sell';

  const confidence = Math.round(clamp(50 + Math.abs(score - 50) * 1.5, 30, 92));
  const sorted = factors.slice().sort((a, b) => Math.abs(b.delta) - Math.abs(a.delta));
  const reasons = sorted.slice(0, 4).map((f) => f.text);

  let summary;
  if (action === 'buy') {
    summary =
      score >= 78 ? '趋势与位置共振，可分批建仓' : score >= 68 ? '偏多信号成立，适合小仓位试仓' : '偏多但强度一般，回踩再介入';
  } else if (action === 'sell') {
    summary = score <= 22 ? '趋势走坏且位置偏高，建议减仓离场' : '偏空信号出现，逢反弹减仓';
  } else {
    summary = '多空信号互相抵消，等待更明确的方向';
  }

  let position = '观望';
  if (action === 'buy') position = score >= 80 ? '5-6 成' : score >= 70 ? '4 成' : '3 成';
  else if (action === 'sell') position = score <= 22 ? '减至 0-1 成' : '减至 2 成';

  return {
    action,
    score,
    confidence,
    summary,
    reasons,
    position,
    support: L.support,
    resistance: L.resistance,
    risk:
      L.vol20 != null && L.vol20 > 30
        ? `年化波动 ${L.vol20}%，仓位需控制`
        : '单一基金仓位建议不超过总仓位的 20%',
    horizon: '5-15 个交易日',
  };
}

export function actionText(action) {
  return action === 'buy' ? '波段买入' : action === 'sell' ? '波段卖出' : '观望等待';
}

export function actionShort(action) {
  return action === 'buy' ? '买入' : action === 'sell' ? '卖出' : '观望';
}
