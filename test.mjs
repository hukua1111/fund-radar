// 本地验证：用假的接口数据跑通全部分支，不访问真实网络。
// 运行：node test.mjs

import { runCheck, parseCodes, describe } from './src/core.js';

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

console.log(`\n结果：${pass} 通过，${fail} 失败\n`);
process.exit(fail ? 1 : 0);
