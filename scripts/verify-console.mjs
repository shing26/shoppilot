// ticket 15 验收：浏览器打开调试台走完演示，并证明页面从不接触 biz-mock 与其内部凭证。
// 用法: $env:NODE_PATH="<repo>\.tools\node_modules"; node scripts/verify-console.mjs
// 用 createRequire 而不是静态 import：ESM 解析不吃 NODE_PATH，而 Playwright 装在 .tools 下，
// 不为一个验收脚本给项目拉一份 package.json。
import { createRequire } from 'node:module';
const { chromium } = createRequire(import.meta.url)('playwright');

const BASE = process.env.SHOPPILOT_CONSOLE_BASE || 'http://127.0.0.1:8082';
const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
};

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

const seen = [];
const leaked = [];
const consoleErrors = [];
page.on('request', (req) => {
  seen.push(req.url());
  if (req.url().includes(':8091')) leaked.push('url:' + req.url());
  const headers = req.headers();
  if (headers['x-internal-token']) leaked.push('header on ' + req.url());
});
page.on('console', (msg) => { if (msg.type() === 'error') consoleErrors.push(msg.text()); });
page.on('pageerror', (err) => consoleErrors.push('pageerror:' + err.message));

const dumpTimeline = async () => (await page.$$eval('#timeline .ev',
  (els) => els.map((e) => e.textContent.replace(/\s+/g, ' ').trim()))).join('\n    ');

// 卡住时把时间线原样吐出来：没有这份证据，超时只是一句"它没出现"，定位不了是哪一帧没渲染
process.on('unhandledRejection', async (err) => {
  console.log('ABORTED: ' + (err && err.message ? err.message : err));
  try { console.log('TIMELINE:\n    ' + await dumpTimeline()); } catch (e) { console.log('timeline dump failed'); }
  try { await page.screenshot({ path: 'docs/console-fail.png' }); } catch (e) { /* ignore */ }
  await browser.close();
  process.exit(1);
});

await page.goto(BASE + '/', { waitUntil: 'networkidle' });

// 身份区：claims 必须解出来，租户下拉必须来自代理而不是写死
const claims = await page.textContent('#claims');
const parsedClaims = JSON.parse(claims);
// 断言值而不是键名：空 tenantId 签出来的 token 照样"有 tid 字段"，但整条链路会以无租户身份跑
check('identity claims decoded with real tenant', parsedClaims.tid === 'T001' && parsedClaims.cid === 'C001',
  claims.replace(/\s+/g, ' ').slice(0, 90));
const tenantOptions = await page.$$eval('#tenant option', (els) => els.map((e) => e.value));
check('tenant picker populated via proxy', tenantOptions.length >= 3, tenantOptions.join(','));
const tenantLabels = await page.$$eval('#tenant option', (els) => els.map((e) => e.textContent));
check('tenant picker shows shop names from biz-mock', tenantLabels.some((t) => t.includes('数码旗舰店')), tenantLabels.join(' | '));

// 演示一：政策问答走打字机。先清一次答案缓存——上一轮跑测留下的 L1 命中会让"未命中路径"
// 退化成一次性下发，那条断言就变成在测缓存而不是测流式形态。
await page.click('#btnFlush');
await page.waitForTimeout(300);
await page.fill('#q', '生鲜坏了怎么赔');
await page.click('#btnSend');
await page.waitForSelector('.ev.done', { timeout: 90000 });
const frames = await page.$$eval('#timeline .ev', (els) => els.map((e) => e.className.replace('ev ', '')));
const chipCount = await page.$$eval('#timeline .ev.token .chip', (els) => els.length).catch(() => 0);
check('policy answer streams with timeline frames', frames.includes('meta') && frames.includes('done'), frames.join(' > '));
check('miss path renders as typewriter (many token frames)', chipCount > 3, chipCount + ' frames');
const citations = await page.$$eval('#chat .cite', (els) => els.length);
check('citations rendered under the answer', citations >= 1, String(citations));

// 演示二：缓存命中路径的推送形态必须不同（一次性下发 = 1 帧）
await page.click('#btnFlush');
await page.fill('#q', '七天无理由怎么算');
await page.click('#btnSend');
await page.waitForFunction(() => document.querySelectorAll('#timeline .ev.done').length > 0, null, { timeout: 90000 });
await page.waitForTimeout(400);
await page.click('#btnSend');
await page.waitForTimeout(1500);
const hitMeta = await page.$$eval('#timeline .ev.meta', (els) => els.map((e) => e.textContent).pop());
const hitChips = await page.$$eval('#timeline .ev.token .chip', (els) => els.length).catch(() => 0);
check('cache hit renders as one-shot (1 token frame)', hitChips === 1, `meta="${(hitMeta || '').trim()}" chips=${hitChips}`);

// 演示三：故障注入经代理生效，并落一张可查工单
await page.fill('#failRate', '1');
await page.click('#btnApply');
await page.waitForTimeout(400);
const applied = await page.$$eval('#timeline .ev', (els) => els.map((e) => e.textContent).filter((t) => t.includes('已注入')).pop());
check('fault injection applied through gateway proxy', !!applied, (applied || '').trim());
// 换新会话再问：3B 模型在带历史的多轮里可能直接沿用上一轮结论而不发 function call，
// 那样降级帧根本不该出现——断言会测到模型的记忆而不是降级链路。
await page.click('#btnNewConv');
await page.fill('#q', '订单 90002 到哪了');
await page.click('#btnSend');
const sawFallback = await page
  .waitForFunction(() => [...document.querySelectorAll('#timeline .ev.fallback')].length > 0, null, { timeout: 90000 })
  .then(() => true)
  .catch(async () => { console.log('  timeline was:\n    ' + await dumpTimeline()); return false; });
const fallbackText = sawFallback
  ? await page.$$eval('#timeline .ev.fallback', (els) => els.pop().textContent)
  : '';
check('injected failure degrades to a queryable ticket', /TOOL_UNAVAILABLE/.test(fallbackText) && /T\d{10}/.test(fallbackText), (fallbackText || '(no fallback frame)').trim());
await page.click('#btnClear');

// 工单抽屉
await page.click('#btnTickets');
await page.waitForSelector('#tickets .tk', { timeout: 15000 });
const ticketCount = await page.$$eval('#tickets .tk', (els) => els.length);
check('ticket drawer lists tickets via proxy', ticketCount > 0, ticketCount + ' tickets');
const assignBtn = page.locator('#tickets button[data-to="ASSIGNED"]').first();
await assignBtn.click();
await page.waitForTimeout(900);
const badges = await page.$$eval('#tickets .badge', (els) => els.map((e) => e.textContent));
check('ticket status transition works', badges.includes('ASSIGNED'), badges.join(','));

// 布局稳定性：长事件文本不得把时间线挤变形
const widths = await page.evaluate(() => {
  const pane = document.getElementById('pane-events').getBoundingClientRect().width;
  const widest = Math.max(...[...document.querySelectorAll('#timeline .ev')].map((e) => e.getBoundingClientRect().width));
  return { pane, widest };
});
check('timeline keeps its column width under long events', widths.widest <= widths.pane + 1, JSON.stringify(widths));

// 这条是安全断言：页面只跟同源网关说话，内部凭证一次都没出现在浏览器侧
check('browser never touches biz-mock :8091 or internal token', leaked.length === 0, leaked.slice(0, 3).join(' | '));
check('no failed or throwing requests in the page', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '));
check('every request went to the gateway origin', seen.every((u) => u.startsWith(BASE)),
  [...new Set(seen.map((u) => new URL(u).origin))].join(', '));

await page.screenshot({ path: 'docs/console.png', fullPage: false });
await browser.close();
finish();

async function finish() {
  const failed = results.filter((r) => !r.ok);
  console.log(`\n${results.length - failed.length}/${results.length} console checks passed`);
  if (failed.length) console.log('failed: ' + failed.map((f) => f.name).join(', '));
  process.exit(failed.length ? 1 : 0);
}
