// 生成应用图标（纯 Node，无第三方依赖）
// 运行：node tools/gen-icons.mjs

import { deflateSync } from 'node:zlib';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';

const CRC = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();

function crc32(buf) {
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const t = Buffer.from(type, 'ascii');
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([t, data])));
  return Buffer.concat([len, t, data, crc]);
}

function encodePNG(w, h, rgba) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  const stride = w * 4 + 1;
  const raw = Buffer.alloc(stride * h);
  for (let y = 0; y < h; y++) {
    raw[y * stride] = 0;
    rgba.copy(raw, y * stride + 1, y * w * 4, (y + 1) * w * 4);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

const clamp01 = (v) => Math.max(0, Math.min(1, v));
const mix = (a, b, t) => a + (b - a) * t;

function sdfRoundRect(u, v, r) {
  const dx = Math.max(Math.abs(u - 0.5) - (0.5 - r), 0);
  const dy = Math.max(Math.abs(v - 0.5) - (0.5 - r), 0);
  return Math.hypot(dx, dy) - r;
}

function angleDiff(a, b) {
  let d = Math.abs(a - b) % (Math.PI * 2);
  if (d > Math.PI) d = Math.PI * 2 - d;
  return d;
}

function sample(u, v, radius) {
  // 背景
  const sdf = sdfRoundRect(u, v, radius);
  if (sdf > 0) return [0, 0, 0, 0];

  const t = clamp01(v);
  let r = mix(29, 10, t);
  let g = mix(43, 18, t);
  let b = mix(100, 52, t);
  let a = 255;

  // 右上角光晕
  const gd = Math.hypot(u - 0.86, v - 0.02);
  const glow = Math.max(0, 1 - gd / 0.85) ** 2;
  r = mix(r, 32, glow * 0.45);
  g = mix(g, 180, glow * 0.45);
  b = mix(b, 170, glow * 0.45);

  // 右下角压暗
  const vd = Math.hypot(u - 0.1, v - 1.0);
  const vig = Math.max(0, 1 - vd / 1.1) ** 2;
  r *= 1 - vig * 0.35;
  g *= 1 - vig * 0.35;
  b *= 1 - vig * 0.35;

  const cx = 0.5;
  const cy = 0.53;
  const d = Math.hypot(u - cx, v - cy);
  const ang = Math.atan2(v - cy, u - cx);
  const mint = [45, 212, 191];

  // 同心圆
  for (const [rad, alpha, width] of [
    [0.135, 0.34, 0.008],
    [0.235, 0.26, 0.007],
    [0.335, 0.2, 0.006],
  ]) {
    const ring = Math.abs(d - rad);
    if (ring < width) {
      const k = (1 - ring / width) * alpha;
      r = mix(r, mint[0], k);
      g = mix(g, mint[1], k);
      b = mix(b, mint[2], k);
    }
  }

  // 十字准线
  if (d < 0.335 && (Math.abs(u - cx) < 0.0026 || Math.abs(v - cy) < 0.0026)) {
    r = mix(r, 255, 0.1);
    g = mix(g, 255, 0.1);
    b = mix(b, 255, 0.1);
  }

  // 扫描扇形
  const base = -0.9;
  const diff = angleDiff(ang, base);
  if (d < 0.335 && diff < 0.7) {
    const k = (1 - diff / 0.7) ** 2 * 0.3;
    r = mix(r, mint[0], k);
    g = mix(g, mint[1], k);
    b = mix(b, mint[2], k);
  }
  // 扫描亮线
  if (d < 0.335 && diff < 0.02) {
    const k = (1 - diff / 0.02) * 0.95;
    r = mix(r, 230, k);
    g = mix(g, 255, k);
    b = mix(b, 250, k);
  }

  // 光点
  const blips = [
    [-0.35, 0.24],
    [0.95, 0.19],
    [2.1, 0.29],
  ];
  for (const [ba, br] of blips) {
    const bx = cx + Math.cos(ba) * br;
    const by = cy + Math.sin(ba) * br;
    const bd = Math.hypot(u - bx, v - by);
    if (bd < 0.05) {
      const k = Math.max(0, 1 - bd / 0.05) ** 2 * 0.55;
      r = mix(r, mint[0], k);
      g = mix(g, mint[1], k);
      b = mix(b, mint[2], k);
    }
    if (bd < 0.017) {
      r = mix(r, 240, 0.95);
      g = mix(g, 255, 0.95);
      b = mix(b, 250, 0.95);
    }
  }

  // 中心点
  if (d < 0.013) {
    r = 240;
    g = 255;
    b = 250;
    a = 255;
  }

  // 内描边
  if (sdf > -0.006) {
    const k = 0.18;
    r = mix(r, 255, k);
    g = mix(g, 255, k);
    b = mix(b, 255, k);
  }

  return [r, g, b, a];
}

function render(size, radius) {
  const SS = 3;
  const out = Buffer.alloc(size * size * 4);
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      let r = 0;
      let g = 0;
      let b = 0;
      let a = 0;
      for (let sy = 0; sy < SS; sy++) {
        for (let sx = 0; sx < SS; sx++) {
          const u = (x + (sx + 0.5) / SS) / size;
          const v = (y + (sy + 0.5) / SS) / size;
          const c = sample(u, v, radius);
          const alpha = c[3] / 255;
          r += c[0] * alpha;
          g += c[1] * alpha;
          b += c[2] * alpha;
          a += alpha;
        }
      }
      const n = SS * SS;
      const idx = (y * size + x) * 4;
      if (a > 0) {
        out[idx] = Math.round(r / a);
        out[idx + 1] = Math.round(g / a);
        out[idx + 2] = Math.round(b / a);
        out[idx + 3] = Math.round((a / n) * 255);
      }
    }
  }
  return out;
}

const outDir = join(process.cwd(), 'docs', 'icons');
mkdirSync(outDir, { recursive: true });
const targets = [
  ['icon-512.png', 512, 0.22],
  ['icon-192.png', 192, 0.22],
  ['apple-touch-icon.png', 180, 0],
  ['icon-maskable-512.png', 512, 0],
  ['favicon.png', 64, 0.22],
];
for (const [name, size, radius] of targets) {
  const data = render(size, radius);
  writeFileSync(join(outDir, name), encodePNG(size, size, data));
  console.log('生成', join('docs/icons', name), size + 'x' + size);
}
