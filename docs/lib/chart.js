// 基金净值 K 线（Canvas 自绘，无第三方依赖）
// 基金只有日频净值，没有盘中高低点：
//   开盘 = 前一日净值，收盘 = 当日净值，最高/最低 = 两者取大/小，
//   所以影线只表示当日涨跌方向，界面上会明确说明。

import { sma } from './indicators.js';

const RANGES = {
  '3m': 63,
  '6m': 126,
  '1y': 252,
  all: Infinity,
};

const LIGHT = {
  grid: '#eef1f5',
  axis: '#98a2b3',
  up: '#e5484d',
  down: '#12a150',
  upFill: 'rgba(229,72,77,0.10)',
  downFill: 'rgba(18,161,80,0.10)',
  ma5: '#f59e0b',
  ma10: '#3b82f6',
  ma20: '#8b5cf6',
  ma60: '#7c8798',
  cross: '#94a3b8',
  tipBg: 'rgba(255,255,255,0.97)',
  tipBorder: '#e4e7ec',
  tipText: '#101828',
  tipSub: '#667085',
  shadow: 'rgba(16,24,40,0.10)',
};

const DARK = {
  grid: '#1e2530',
  axis: '#6b7686',
  up: '#f2555a',
  down: '#22c07d',
  upFill: 'rgba(242,85,90,0.14)',
  downFill: 'rgba(34,192,125,0.14)',
  ma5: '#fbbf24',
  ma10: '#60a5fa',
  ma20: '#a78bfa',
  ma60: '#8b97a8',
  cross: '#64748b',
  tipBg: 'rgba(17,22,30,0.97)',
  tipBorder: '#2a3341',
  tipText: '#f2f4f7',
  tipSub: '#98a2b3',
  shadow: 'rgba(0,0,0,0.4)',
};

const PAD = { top: 14, right: 52, bottom: 22, left: 6 };

export function buildCandles(klines) {
  const out = [];
  for (let i = 0; i < klines.length; i++) {
    const c = klines[i].c;
    const o = i > 0 ? klines[i - 1].c : c;
    out.push({
      d: klines[i].d,
      o,
      c,
      h: Math.max(o, c),
      l: Math.min(o, c),
      chg: klines[i].chg,
      chgPct: o ? ((c - o) / o) * 100 : 0,
    });
  }
  return out;
}

export class KLineChart {
  constructor(canvas, opts = {}) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.theme = opts.theme === 'dark' ? DARK : LIGHT;
    this.onHover = opts.onHover || (() => {});
    this.mas = [5, 10, 20, 60];
    this.candles = [];
    this.series = null;
    this.range = opts.range || '6m';
    this.view = { end: 0, count: 0 };
    this.hover = null;
    this.drag = null;
    this.pointers = new Map();
    this.pinch = null;
    this.dpr = Math.min(window.devicePixelRatio || 1, 2);

    this._onDown = this.onPointerDown.bind(this);
    this._onMove = this.onPointerMove.bind(this);
    this._onUp = this.onPointerUp.bind(this);
    this._onWheel = this.onWheel.bind(this);
    this._onDbl = this.reset.bind(this);

    canvas.addEventListener('pointerdown', this._onDown);
    canvas.addEventListener('pointermove', this._onMove);
    canvas.addEventListener('pointerup', this._onUp);
    canvas.addEventListener('pointercancel', this._onUp);
    canvas.addEventListener('pointerleave', this._onUp);
    canvas.addEventListener('wheel', this._onWheel, { passive: false });
    canvas.addEventListener('dblclick', this._onDbl);

    this.ro = new ResizeObserver(() => this.resize());
    this.ro.observe(canvas.parentElement || canvas);
    this.resize();
  }

  setTheme(mode) {
    this.theme = mode === 'dark' ? DARK : LIGHT;
    this.draw();
  }

  setData(klines) {
    this.candles = buildCandles(klines || []);
    const closes = this.candles.map((k) => k.c);
    this.series = { ma5: sma(closes, 5), ma10: sma(closes, 10), ma20: sma(closes, 20), ma60: sma(closes, 60) };
    this.applyRange(this.range);
  }

  applyRange(range) {
    this.range = range;
    const n = this.candles.length;
    const want = RANGES[range] ?? 126;
    const count = Math.max(8, Math.min(n || 8, Number.isFinite(want) ? want : n || 8));
    this.view = { end: n, count };
    this.draw();
    this.emitHover(null);
  }

  reset() {
    this.applyRange(this.range);
  }

  resize() {
    const parent = this.canvas.parentElement;
    const w = (parent ? parent.clientWidth : this.canvas.clientWidth) || 320;
    const h = Number(this.canvas.dataset.height) || 300;
    this.canvas.style.width = w + 'px';
    this.canvas.style.height = h + 'px';
    this.canvas.width = Math.round(w * this.dpr);
    this.canvas.height = Math.round(h * this.dpr);
    this.w = w;
    this.h = h;
    this.draw();
  }

  geometry() {
    return {
      x0: PAD.left,
      x1: this.w - PAD.right,
      y0: PAD.top,
      y1: this.h - PAD.bottom,
    };
  }

  visible() {
    const n = this.candles.length;
    if (!n) return { start: 0, end: 0, count: 0 };
    const end = Math.max(Math.min(this.view.end || n, n), 1);
    const count = Math.max(6, Math.min(this.view.count || n, n));
    const start = Math.max(0, end - count);
    return { start, end, count: end - start };
  }

  scale() {
    const { start, end } = this.visible();
    let lo = Infinity;
    let hi = -Infinity;
    for (let i = start; i < end; i++) {
      const k = this.candles[i];
      if (!k) continue;
      lo = Math.min(lo, k.l);
      hi = Math.max(hi, k.h);
      for (const n of this.mas) {
        const v = this.series['ma' + n][i];
        if (v != null) {
          lo = Math.min(lo, v);
          hi = Math.max(hi, v);
        }
      }
    }
    if (!Number.isFinite(lo) || !Number.isFinite(hi)) return null;
    if (hi === lo) {
      hi += hi * 0.01 || 0.01;
      lo -= lo * 0.01 || 0.01;
    }
    const pad = (hi - lo) * 0.08;
    const g = this.geometry();
    const y = (v) => g.y1 - ((v - (lo - pad)) / (hi + pad - (lo - pad))) * (g.y1 - g.y0);
    return { lo: lo - pad, hi: hi + pad, y };
  }

  draw() {
    const ctx = this.ctx;
    const t = this.theme;
    if (!this.w) return;
    ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
    ctx.clearRect(0, 0, this.w, this.h);

    const g = this.geometry();
    const { start, end, count } = this.visible();

    // 网格
    const sc = this.scale();
    this._sc = sc;
    ctx.strokeStyle = t.grid;
    ctx.lineWidth = 1;
    ctx.font = '10px ui-monospace, SFMono-Regular, Menlo, monospace';
    ctx.fillStyle = t.axis;
    ctx.textAlign = 'left';
    ctx.textBaseline = 'middle';
    if (sc) {
      const ticks = 5;
      for (let i = 0; i < ticks; i++) {
        const v = sc.lo + ((sc.hi - sc.lo) * i) / (ticks - 1);
        const y = Math.round(sc.y(v)) + 0.5;
        ctx.beginPath();
        ctx.moveTo(g.x0, y);
        ctx.lineTo(g.x1, y);
        ctx.stroke();
        ctx.fillText(this.fmtPrice(v), g.x1 + 6, y);
      }
    }

    if (!count) {
      ctx.fillStyle = t.axis;
      ctx.font = '13px -apple-system, "PingFang SC", sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText('暂无净值数据', this.w / 2, (g.y0 + g.y1) / 2);
      return;
    }

    // 日期轴
    const labelCount = this.w < 380 ? 3 : 4;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'top';
    for (let i = 0; i < labelCount; i++) {
      const idx = Math.round(start + ((count - 1) * i) / (labelCount - 1 || 1));
      const k = this.candles[idx];
      if (!k) continue;
      const x = this.xOf(idx, start, count);
      const label = this.fmtDate(k.d);
      const tw = ctx.measureText(label).width;
      const cx = Math.max(tw / 2 + 2, Math.min(this.w - tw / 2 - 2, x));
      ctx.fillText(label, cx, g.y1 + 6);
    }

    const step = (g.x1 - g.x0) / count;
    const bodyW = Math.max(1.2, Math.min(11, step * 0.62));

    // 蜡烛
    if (sc) {
      for (let i = start; i < end; i++) {
        const k = this.candles[i];
        const x = this.xOf(i, start, count);
        const up = k.c >= k.o;
        const color = up ? t.up : t.down;
        const yH = sc.y(k.h);
        const yL = sc.y(k.l);
        const yO = sc.y(k.o);
        const yC = sc.y(k.c);
        ctx.strokeStyle = color;
        ctx.fillStyle = color;
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(Math.round(x) + 0.5, yH);
        ctx.lineTo(Math.round(x) + 0.5, yL);
        ctx.stroke();
        const top = Math.min(yO, yC);
        const hBody = Math.max(1, Math.abs(yC - yO));
        if (bodyW <= 2) {
          ctx.fillRect(Math.round(x) - 0.5, top, 1.6, hBody);
        } else {
          ctx.fillRect(x - bodyW / 2, top, bodyW, hBody);
        }
      }

      // 均线
      const maColors = { 5: t.ma5, 10: t.ma10, 20: t.ma20, 60: t.ma60 };
      for (const n of this.mas) {
        const arr = this.series['ma' + n];
        ctx.strokeStyle = maColors[n];
        ctx.lineWidth = 1.3;
        ctx.beginPath();
        let started = false;
        for (let i = start; i < end; i++) {
          const v = arr[i];
          if (v == null) {
            started = false;
            continue;
          }
          const x = this.xOf(i, start, count);
          const y = sc.y(v);
          if (!started) {
            ctx.moveTo(x, y);
            started = true;
          } else {
            ctx.lineTo(x, y);
          }
        }
        ctx.stroke();
      }
    }

    // 十字光标
    if (this.hover && this.hover.index >= start && this.hover.index < end && sc) {
      const k = this.candles[this.hover.index];
      const x = this.xOf(this.hover.index, start, count);
      const y = sc.y(k.c);
      ctx.save();
      ctx.setLineDash([3, 3]);
      ctx.strokeStyle = t.cross;
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(g.x0, Math.round(y) + 0.5);
      ctx.lineTo(g.x1, Math.round(y) + 0.5);
      ctx.moveTo(Math.round(x) + 0.5, g.y0);
      ctx.lineTo(Math.round(x) + 0.5, g.y1);
      ctx.stroke();
      ctx.restore();

      // 价格气泡
      const label = this.fmtPrice(k.c);
      ctx.font = '10px ui-monospace, Menlo, monospace';
      const tw = ctx.measureText(label).width + 10;
      ctx.fillStyle = k.c >= k.o ? t.up : t.down;
      this.roundRect(g.x1 + 2, y - 8, Math.min(tw, PAD.right - 6), 16, 4);
      ctx.fill();
      ctx.fillStyle = '#fff';
      ctx.textAlign = 'left';
      ctx.textBaseline = 'middle';
      ctx.fillText(label, g.x1 + 7, y + 0.5);

      this.drawTooltip(k, x, this.hover.index);
    }
  }

  drawTooltip(k, x, index) {
    const ctx = this.ctx;
    const t = this.theme;
    const gc = { r: ((k.c - k.o) / (k.o || 1)) * 100 };
    const rows = [
      ['日期', k.d],
      ['开/收', `${this.fmtPrice(k.o)} / ${this.fmtPrice(k.c)}`],
      ['高/低', `${this.fmtPrice(k.h)} / ${this.fmtPrice(k.l)}`],
      ['涨跌', `${gc.r >= 0 ? '+' : ''}${gc.r.toFixed(2)}%`],
    ];
    for (const n of this.mas) {
      const v = this.series['ma' + n][index];
      rows.push([`MA${n}`, v == null ? '—' : this.fmtPrice(v)]);
    }

    const fontSize = 10.5;
    const rowH = 15;
    const padX = 9;
    const padY = 8;
    const labelW = 42;
    const valueW = Math.max(...rows.map((r) => ctx.measureText(r[1]).width)) + 2;
    const boxW = labelW + valueW + padX * 2;
    const boxH = rows.length * rowH + padY * 2 - 3;

    let bx = x + 14;
    if (bx + boxW > this.w - 4) bx = x - boxW - 14;
    bx = Math.max(4, bx);
    const by = Math.max(4, Math.min(this.h - boxH - 4, 18));

    ctx.save();
    ctx.shadowColor = t.shadow;
    ctx.shadowBlur = 12;
    ctx.shadowOffsetY = 2;
    ctx.fillStyle = t.tipBg;
    this.roundRect(bx, by, boxW, boxH, 8);
    ctx.fill();
    ctx.restore();
    ctx.strokeStyle = t.tipBorder;
    ctx.lineWidth = 1;
    this.roundRect(bx + 0.5, by + 0.5, boxW - 1, boxH - 1, 8);
    ctx.stroke();

    ctx.textBaseline = 'middle';
    ctx.font = `${fontSize}px -apple-system, "PingFang SC", sans-serif`;
    rows.forEach((r, i) => {
      const y = by + padY + i * rowH + rowH / 2 - 1;
      ctx.textAlign = 'left';
      ctx.fillStyle = t.tipSub;
      ctx.fillText(r[0], bx + padX, y);
      ctx.textAlign = 'right';
      const isChange = i === 3;
      ctx.fillStyle = isChange ? (gc.r >= 0 ? t.up : t.down) : t.tipText;
      ctx.font = `${fontSize}px ${i >= 4 ? 'ui-monospace, Menlo, monospace' : '-apple-system, "PingFang SC", sans-serif'}`;
      ctx.fillText(r[1], bx + boxW - padX, y);
      ctx.font = `${fontSize}px -apple-system, "PingFang SC", sans-serif`;
    });
  }

  roundRect(x, y, w, h, r) {
    const ctx = this.ctx;
    const rr = Math.min(r, w / 2, h / 2);
    ctx.beginPath();
    ctx.moveTo(x + rr, y);
    ctx.arcTo(x + w, y, x + w, y + h, rr);
    ctx.arcTo(x + w, y + h, x, y + h, rr);
    ctx.arcTo(x, y + h, x, y, rr);
    ctx.arcTo(x, y, x + w, y, rr);
    ctx.closePath();
  }

  xOf(index, start, count) {
    const g = this.geometry();
    const step = (g.x1 - g.x0) / count;
    return g.x0 + step * (index - start) + step / 2;
  }

  indexAt(clientX) {
    const rect = this.canvas.getBoundingClientRect();
    const x = clientX - rect.left;
    const g = this.geometry();
    const { start, count } = this.visible();
    const step = (g.x1 - g.x0) / count;
    if (x < g.x0 || x > g.x1) return null;
    const idx = start + Math.floor((x - g.x0) / step);
    return Math.max(start, Math.min(start + count - 1, idx));
  }

  fmtPrice(v) {
    const sc = this._sc;
    const span = sc ? sc.hi - sc.lo : 1;
    const digits = span < 0.5 ? 4 : span < 5 ? 3 : 2;
    return Number(v).toFixed(digits);
  }

  fmtDate(d) {
    const s = String(d || '');
    if (s.length < 10) return s;
    return s.slice(2, 4) + '/' + s.slice(5, 7) + '/' + s.slice(8, 10);
  }

  emitHover(index) {
    if (index == null) {
      this.onHover(null);
      return;
    }
    const k = this.candles[index];
    if (!k) return;
    this.onHover({
      ...k,
      ma5: this.series.ma5[index],
      ma10: this.series.ma10[index],
      ma20: this.series.ma20[index],
      ma60: this.series.ma60[index],
    });
  }

  onPointerDown(e) {
    this.canvas.setPointerCapture(e.pointerId);
    this.pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (this.pointers.size === 2) {
      const [a, b] = [...this.pointers.values()];
      this.pinch = { dist: Math.abs(a.x - b.x), count: this.view.count, center: (a.x + b.x) / 2 };
      this.drag = null;
      return;
    }
    this.drag = { x: e.clientX, end: this.view.end, moved: false };
    if (e.pointerType !== 'mouse') {
      const idx = this.indexAt(e.clientX);
      if (idx != null) {
        this.hover = { index: idx };
        this.draw();
        this.emitHover(idx);
      }
    }
  }

  onPointerMove(e) {
    if (this.pointers.has(e.pointerId)) this.pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });

    if (this.pointers.size === 2 && this.pinch) {
      const [a, b] = [...this.pointers.values()];
      const dist = Math.abs(a.x - b.x);
      const ratio = this.pinch.dist / Math.max(20, dist);
      const n = this.candles.length;
      const count = Math.max(10, Math.min(n, Math.round(this.pinch.count * ratio)));
      const end = Math.min(n, Math.max(this.view.end, count));
      this.view = { end, count };
      this.draw();
      return;
    }

    if (this.drag) {
      const g = this.geometry();
      const { count } = this.visible();
      const step = (g.x1 - g.x0) / count;
      const dx = e.clientX - this.drag.x;
      if (Math.abs(dx) > 2) this.drag.moved = true;
      if (this.drag.moved) {
        this.hover = null;
        const shift = Math.round(dx / step);
        const n = this.candles.length;
        const end = Math.max(count, Math.min(n, this.drag.end - shift));
        this.view = { end, count };
        this.draw();
        this.emitHover(null);
        return;
      }
    }

    const idx = this.indexAt(e.clientX);
    if (idx == null) {
      if (this.hover) {
        this.hover = null;
        this.draw();
        this.emitHover(null);
      }
      return;
    }
    if (!this.hover || this.hover.index !== idx) {
      this.hover = { index: idx };
      this.draw();
      this.emitHover(idx);
    }
  }

  onPointerUp(e) {
    this.pointers.delete(e.pointerId);
    if (this.pointers.size < 2) this.pinch = null;
    if (this.pointers.size === 0) this.drag = null;
  }

  onWheel(e) {
    if (!this.candles.length) return;
    e.preventDefault();
    const n = this.candles.length;
    const factor = e.deltaY > 0 ? 1.12 : 1 / 1.12;
    const count = Math.max(10, Math.min(n, Math.round(this.view.count * factor)));
    this.view = { end: Math.min(n, this.view.end), count };
    this.draw();
    this.emitHover(null);
  }

  destroy() {
    this.ro && this.ro.disconnect();
    this.canvas.removeEventListener('pointerdown', this._onDown);
    this.canvas.removeEventListener('pointermove', this._onMove);
    this.canvas.removeEventListener('pointerup', this._onUp);
    this.canvas.removeEventListener('pointercancel', this._onUp);
    this.canvas.removeEventListener('pointerleave', this._onUp);
    this.canvas.removeEventListener('wheel', this._onWheel);
    this.canvas.removeEventListener('dblclick', this._onDbl);
  }
}
