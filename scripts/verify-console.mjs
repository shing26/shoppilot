// ticket 15 验收：浏览器打开调试台走完演示，并证明页面从不接触 biz-mock 与其内部凭证。
// 用法: $env:NODE_PATH="<repo>\.tools\node_modules"; node scripts/verify-console.mjs
// 用 createRequire 而不是静态 import：ESM 解析不吃 NODE_PATH，而 Playwright 装在 .tools 下，
// 不为一个验收脚本给项目拉一份 package.json。
import { createRequire } from 'node:module';
const { chromium } = createRequire(import.meta.url)('playwright');

const BASE = process.env.SHOPPILOT_CONSOLE_BASE || 'http://127.0.0.1:8082';
// 票 21（ADR 0029）起页面不再预填运维令牌：凭证不该出现在网关下发的静态资源里。
// 本脚本按各 verify-*.ps1 的 $OpsToken 家法自带这个值，代人在输入框里敲一次。
// 15 条断言一字未改，改的只是「谁来提供凭证」这一步前置。
const OPS_TOKEN = process.env.SHOPPILOT_OPS_TOKEN || 'dev-ops-token';
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

// 运维令牌由输入框提供，页面每个请求都带上它，只有 ops 端点会读。必须在点「注入」之前填好。
// fill 会触发 change，于是 boot 时因令牌为空而跳过的那次 ops 读数补回来。
await page.fill('#opsToken', OPS_TOKEN);

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

// 票 23（ADR 0026）：这盏灯第一次有能力变红。三态各喂一份 deps 读数去断，不靠运气等一次真故障。
// 另开一个页面打桩：主页面那条「页面无失败请求」的断言不许被 DOWN 时的 503 污染成假红。
const LIGHT_STATES = [
  // want = data-state（灯知道自己是什么），cls = 它据此挂上的 class。两者都要断：
  // 只断 cls 会放过「靠 CSS 蒙对颜色但状态机是坏的」，只断 want 会放过「状态对但没上色」。
  { name: 'deps 三格齐时灯为绿', want: 'ok', cls: 'dot', http: 200,
    body: { status: 'UP', components: { qdrant: { status: 'UP' }, elasticsearch: { status: 'UP' },
      knowledgeBase: { status: 'UP' } } } },
  // 503 是真的：Spring Boot 默认把 DOWN 映射成 503。页面只认 body.status，不拿 HTTP 码当判据。
  { name: '词法侧 DOWN 时灯为红并点名', want: 'bad', cls: 'dot bad', http: 503,
    body: { status: 'DOWN', components: { qdrant: { status: 'UP' }, elasticsearch: { status: 'DOWN' },
      knowledgeBase: { status: 'DOWN' } } } },
  { name: '读不到 deps 读数时灯为灰', want: 'unknown', cls: 'dot unknown', http: 200,
    body: { note: 'not a health payload' } },
];
for (const light of LIGHT_STATES) {
  const probe = await browser.newPage();
  await probe.route('**/actuator/health/deps', (route) => route.fulfill({
    status: light.http, contentType: 'application/json', body: JSON.stringify(light.body),
  }));
  await probe.goto(BASE + '/', { waitUntil: 'load' });
  const state = await probe.waitForFunction(
    () => document.getElementById('health').dataset.state || null, null, { timeout: 20000 })
    .then((handle) => handle.jsonValue()).catch(() => '');
  const cls = await probe.$eval('#health', (el) => el.className).catch(() => '');
  const tip = await probe.$eval('#health', (el) => el.title).catch(() => '');
  check(light.name, state === light.want && cls === light.cls,
    `state="${state}" class="${cls}" title="${tip}"`);
  await probe.close();
}

// 演示一：政策问答走打字机。先清一次答案缓存——上一轮跑测留下的 L1 命中会让"未命中路径"
// 退化成一次性下发，那条断言就变成在测缓存而不是测流式形态。
await page.click('#btnFlush');
await page.waitForTimeout(300);
await page.fill('#q', '生鲜坏了怎么赔');
await page.click('#btnSend');
await page.waitForSelector('.ev.done', { timeout: 90000 });
const frames = await page.$$eval('#timeline .ev', (els) => els.map((e) => e.className.replace('ev ', '')));
const tokenChunks = await page.$$eval('#timeline .ev.token .chip',
  (els) => els.map((e) => e.textContent || '')).catch(() => []);
const chipCount = tokenChunks.length;
const streamedChars = tokenChunks.join('').replace(/\s+/g, '').length;
check('policy answer streams with timeline frames', frames.includes('meta') && frames.includes('done'), frames.join(' > '));
// 帧数本身随 Ollama 合批波动：门禁里实测过 3 帧，空机重跑同一句是 6 帧、8 帧。
// 这条要钉的不变量是"未命中路径分块推出、推出的就是最后那段答案"，
// 而一次性下发（缓存命中那条路径）只会有 1 块——两条判据仍然互斥，没有放宽成永远绿。
check('miss path renders as typewriter (chunked stream covering the answer)',
  chipCount > 1 && streamedChars > 60, `${chipCount} chunks / ${streamedChars} chars`);
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
