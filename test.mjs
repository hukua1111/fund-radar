// 本地验证：用假的接口数据跑通全部分支，不访问真实网络。
// 运行：node test.mjs

import { runCheck, parseCodes, describe, fetchHistory } from './src/core.js';
import { sma, rsiSeries, computeIndicators, ruleVerdict } from './docs/lib/indicators.js';
import { parseVerdict, normalizeBaseUrl, buildMessages, normalizeAction } from './docs/lib/ai-core.js';

let pushed = [];
let funds = {
  '005827': { SHORTNAME: '易方达蓝筹精选混合', SGZT: '限大额', MAXSG: '50000', MINSG: '10', DWJZ: '1.3806' },
  '004243': { SHORTNAME: '广发道琼斯石油指数人民币C', SGZT: '限大额', MAXSG: '100', MINSG: '10', DWJZ: '3.2240' },
  '162411': { SHORTNAME: '华宝标普油气上游股票人民币A', SGZT: '暂停申购', MAXSG: '10', MINSG: '10', DWJZ: '1.0363' },
};

function makeFetch(failCodes = []) {
  return async (url, init) => {
    const u = String(url);
    if (u.includes('fundmobapi')) {
      const code = new URL(u).searchParams.get('FCODE');
      const d = failCodes.includes(code) ? null : funds[code];
      return { ok: true, json: async () => ({ Datas: d || null, ErrMsg: d ? null : '无数据' }) };
    }
    if (u.includes('api.day.app')) {
      pushed.push(JSON.parse(init.body));
      return { ok: true, status: 200, text: async () => '{"code":200}' };
    }
    throw new Error('unexpected url: ' + u);
  };
}

let pass = 0;
let fail = 0;
function check(name, cond, extra) {
  if (cond) {
    pass++;
    console.log(`  ok   ${name}`);
  } else {
    fail++;
    console.log(`  FAIL ${name}`, extra === undefined ? '' : JSON.stringify(extra));
  }
}

const codes = parseCodes('005827, 004243，162411 162411 999999 abc');

console.log('\n[1] parseCodes 解析与去重');
check('解析出 4 个（999999/abc 会被当成合法代码或丢弃）', codes.length >= 3, codes);
check('重复代码只保留一次', new Set(codes).size === codes.length, codes);

console.log('\n[2] describe 文案');
check('限大额 5 万', describe({ sgzt: '限大额', maxsg: '50000' }) === '限大额 · 50,000 元');
check('不限购', describe({ sgzt: '开放申购', maxsg: '100000000000' }) === '开放申购 · 不限');
check('暂停申购', describe({ sgzt: '暂停申购', maxsg: '10' }) === '暂停申购');

console.log('\n[3] 首次运行建立基线');
pushed = [];
let r = await runCheck({ codes: ['005827', '004243', '162411'], barkKey: 'k', prevRaw: null, fetchImpl: makeFetch() });
check('isFirstRun 为真', r.isFirstRun === true);
check('没有变化项', r.changes.length === 0);
check('发出 1 条启动通知', pushed.length === 1, pushed);
check('通知标题正确', pushed[0] && pushed[0].title === '基金限额监控已启动', pushed[0]);

const baseline = JSON.stringify(r.next);

console.log('\n[4] 数据未变，不推送');
pushed = [];
r = await runCheck({ codes: ['005827', '004243', '162411'], barkKey: 'k', prevRaw: baseline, fetchImpl: makeFetch() });
check('无变化', r.changes.length === 0);
check('不推送', pushed.length === 0, pushed);

console.log('\n[5] 限额从 5 万变成 10 万');
pushed = [];
funds['005827'].MAXSG = '100000';
r = await runCheck({ codes: ['005827', '004243', '162411'], barkKey: 'k', prevRaw: baseline, fetchImpl: makeFetch() });
check('检测到 1 处变化', r.changes.length === 1, r.changes);
check('变化前文案', r.changes[0] && r.changes[0].fromText === '限大额 · 50,000 元', r.changes[0]);
check('变化后文案', r.changes[0] && r.changes[0].toText === '限大额 · 100,000 元', r.changes[0]);
check('推送 1 条', pushed.length === 1);
check('推送正文含箭头', pushed[0] && pushed[0].body.includes('→'), pushed[0]);

console.log('\n[6] 申购状态变化：暂停 → 开放');
pushed = [];
funds['162411'].SGZT = '开放申购';
funds['162411'].MAXSG = '100000000000';
r = await runCheck({ codes: ['005827', '004243', '162411'], barkKey: 'k', prevRaw: baseline, fetchImpl: makeFetch() });
check('检测到变化', r.changes.length >= 1);
const c162411 = r.changes.find((c) => c.code === '162411');
check('识别为 暂停申购 → 开放申购', c162411 && c162411.toText === '开放申购 · 不限', c162411);

console.log('\n[7] 抓取失败不覆盖旧基线');
const before = JSON.stringify(r.next);
pushed = [];
r = await runCheck({ codes: ['005827', '004243', '162411'], barkKey: 'k', prevRaw: before, fetchImpl: makeFetch(['004243']) });
check('记录为失败', r.failed.includes('004243'), r.failed);
check('失败项保留旧快照', r.next['004243'] && r.next['004243'].sig === JSON.parse(before)['004243'].sig);
check('失败不产生变化', r.changes.length === 0, r.changes);

console.log('\n[8] 未配置 Bark Key 时依然能跑');
pushed = [];
r = await runCheck({ codes: ['005827'], barkKey: '', prevRaw: null, fetchImpl: makeFetch() });
check('返回成功', r.ok === true);
check('没有推送', pushed.length === 0);

console.log('\n[9] ALWAYS_NOTIFY 每日推送');
pushed = [];
r = await runCheck({
  codes: ['005827', '004243'],
  barkKey: 'k',
  prevRaw: JSON.stringify(r.next),
  alwaysNotify: true,
  fetchImpl: makeFetch(),
});
check('无变化也推送 1 条', pushed.length === 1, pushed);
check('标题为每日检查', pushed[0] && pushed[0].title === '基金限额每日检查', pushed[0]);


// ---------- 以下为 K 线 / 指标 / AI 相关 ----------
function series(days, step) {
  return Array.from({ length: days }, (_, i) => {
    const c = Number((1 * Math.pow(step, i)).toFixed(4));
    const prev = i === 0 ? c : Number((1 * Math.pow(step, i - 1)).toFixed(4));
    return { d: `2026-${String(Math.floor(i / 28) + 1).padStart(2, '0')}-${String((i % 28) + 1).padStart(2, '0')}`, c, chg: Number((((c - prev) / prev) * 100).toFixed(2)) };
  });
}

console.log('\n[10] 均线计算');
const ma = sma([1, 2, 3, 4, 5], 3);
check('长度与输入一致', ma.length === 5, ma);
check('前两项为 null', ma[0] === null && ma[1] === null, ma);
check('第 3 项 = (1+2+3)/3', ma[2] === 2, ma);
check('最后一项 = (3+4+5)/3', ma[4] === 4, ma);

console.log('\n[11] RSI');
const upSeries = Array.from({ length: 40 }, (_, i) => 1 + i * 0.01);
const downSeries = Array.from({ length: 40 }, (_, i) => 2 - i * 0.01);
check('单边上涨 RSI = 100', Math.round(rsiSeries(upSeries, 14)[39]) === 100, rsiSeries(upSeries, 14)[39]);
check('单边下跌 RSI = 0', Math.round(rsiSeries(downSeries, 14)[39]) === 0, rsiSeries(downSeries, 14)[39]);
check('样本不足返回 null', rsiSeries([1, 2, 3], 14)[2] === null);

console.log('\n[12] 上升趋势的规则判断');
const upInd = computeIndicators(series(120, 1.005));
check('MA20 在 MA60 上方', upInd.latest.ma20 > upInd.latest.ma60, [upInd.latest.ma20, upInd.latest.ma60]);
check('趋势识别为 up', upInd.latest.trend === 'up');
check('评分进入买入区', upInd.latest.verdict.action === 'buy', upInd.latest.verdict);
check('给出理由与仓位', upInd.latest.verdict.reasons.length >= 3 && upInd.latest.verdict.position !== '观望');

console.log('\n[13] 下跌趋势的规则判断');
const downInd = computeIndicators(series(120, 0.995));
check('MA20 在 MA60 下方', downInd.latest.ma20 < downInd.latest.ma60);
check('评分进入卖出区', downInd.latest.verdict.action === 'sell', downInd.latest.verdict);
check('偏离率为负', downInd.latest.bias20 < 0, downInd.latest.bias20);

console.log('\n[14] 数据不足时保守处理');
const few = computeIndicators(series(10, 1.01));
check('不足 20 日给观望', few.latest.verdict.action === 'hold', few.latest.verdict);
check('说明里提示数据不足', few.latest.verdict.reasons.join('').includes('不足'));
check('现金价与样本数正常', few.latest.bars === 10);

console.log('\n[15] AI 返回解析');
const v1 = parseVerdict('```json\n{"action":"买入","confidence":0.72,"summary":"回踩到位","reasons":["站上 MA20","RSI 回落"],"support":1.2,"resistance":1.4,"position":"3 成"}\n```');
check('中文动作识别为 buy', v1.action === 'buy', v1.action);
check('0-1 的信心换算成百分比', v1.confidence === 72, v1.confidence);
check('理由保留 2 条', v1.reasons.length === 2, v1.reasons);
check('支撑阻力解析', v1.support === 1.2 && v1.resistance === 1.4);
check('仓位解析', v1.position === '3 成', v1.position);

const v2 = parseVerdict('随便说点什么 {"action":"SELL","confidence":88,"reasons":"破位"} 结束');
check('容忍前后缀文字', v2.action === 'sell', v2.action);
check('单条理由转数组', Array.isArray(v2.reasons) && v2.reasons[0] === '破位', v2.reasons);

let threw = false;
try {
  parseVerdict('模型今天不想输出 JSON');
} catch (e) {
  threw = true;
}
check('非法内容会抛错', threw);
check('normalizeAction 兜底为 hold', normalizeAction('') === 'hold');

console.log('\n[16] 接口地址归一化');
check('补全 /chat/completions', normalizeBaseUrl('https://api.deepseek.com/v1') === 'https://api.deepseek.com/v1/chat/completions');
check('去掉末尾斜杠', normalizeBaseUrl('https://api.deepseek.com/v1/') === 'https://api.deepseek.com/v1/chat/completions');
check('已完整则不动', normalizeBaseUrl('https://x.com/v1/chat/completions') === 'https://x.com/v1/chat/completions');
check('省略协议自动补 https', normalizeBaseUrl('api.deepseek.com/v1').startsWith('https://'));
check('空值返回空串', normalizeBaseUrl('') === '');

console.log('\n[17] 提示词内容');
const kl = series(80, 1.004);
const ind = computeIndicators(kl);
const msgs = buildMessages({ fund: { code: '005827', name: '易方达蓝筹精选混合', type: '混合型' }, latest: ind.latest, series: ind.series, klines: kl });
check('包含 system 与 user', msgs.length === 2 && msgs[0].role === 'system' && msgs[1].role === 'user');
check('提示词含 MA20 指标', msgs[1].content.includes('MA20'));
check('提示词含基金代码', msgs[1].content.includes('005827'));
check('提示词含日期数据表', /\d{4}-\d{2}-\d{2},\d/.test(msgs[1].content));
check('要求输出 JSON', msgs[1].content.includes('"action"'));
check('提示词长度可控', msgs[1].content.length < 6000, msgs[1].content.length);

console.log('\n[18] 历史净值解析');
const fakeHist = async () => ({
  ok: true,
  json: async () => ({
    Datas: [
      { FSRQ: '2026-11-05', DWJZ: '1.4164', JZZZL: '1.11', LJJZ: '1.4164' },
      { FSRQ: '2026-11-04', DWJZ: '1.4009', JZZZL: '1.43', LJJZ: '1.4009' },
      { FSRQ: '2026-11-03', DWJZ: '1.3811', JZZZL: '--', LJJZ: '1.3811' },
    ],
  }),
});
const hist = await fetchHistory('005827', { fetchImpl: fakeHist });
check('解析成功', hist.ok === true, hist);
check('按日期升序', hist.klines[0].d === '2026-11-03' && hist.klines[2].d === '2026-11-05', hist.klines.map((k) => k.d));
check('涨跌幅缺失记为 null', hist.klines[0].chg === null, hist.klines[0]);
check('净值转为数字', hist.klines[2].c === 1.4164, hist.klines[2]);

console.log('\n[19] 无数据时抓取失败');
const badHist = await fetchHistory('999999', { fetchImpl: async () => ({ ok: true, json: async () => ({ Datas: null, ErrMsg: '无数据' }) }) });
check('标记为失败', badHist.ok === false && badHist.error === '无数据', badHist);

console.log(`\n结果：${pass} 通过，${fail} 失败\n`);
process.exit(fail ? 1 : 0);
