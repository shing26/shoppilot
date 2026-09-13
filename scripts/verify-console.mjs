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
// 负向探针（空输入、超长、错令牌）必然各自吃一发 4xx，浏览器会把「Failed to load resource」
// 记成 console error。那三条安全断言量的是演示路径，不能因为探针自己打的请求而变红；
// 所以探针阶段的噪声单独收一份，只放过明确预期的 400/403，其余（含 5xx、pageerror）照判。
let phase = 'demo';
const probeErrors = [];
const expectedProbe = /Failed to load resource: the server responded with a status of (400|403)/;
page.on('request', (req) => {
  seen.push(req.url());
  if (req.url().includes(':8091')) leaked.push('url:' + req.url());
  const headers = req.headers();
  if (headers['x-internal-token']) leaked.push('header on ' + req.url());
});
page.on('console', (msg) => {
  if (msg.type() !== 'error') return;
  if (phase === 'probe' && expectedProbe.test(msg.text())) return;
  (phase === 'probe' ? probeErrors : consoleErrors).push(msg.text());
});
page.on('pageerror', (err) => (phase === 'probe' ? probeErrors : consoleErrors).push('pageerror:' + err.message));

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
// fill 触发的是 input（change 要等失焦），页面那边是输入后去抖 400ms 补读数，
// 于是 boot 时因令牌为空而跳过的那次 ops 读数在这里补回来；下面这 600ms 等的就是它。
await page.fill('#opsToken', OPS_TOKEN);
await page.waitForTimeout(600);

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

// 走查第 2 项：首字到达之前那几秒界面是空的。这里清一次缓存保证是真冷启动，
// 然后在第一个 token 落地之前抓那行进行态，答完之后要求它已经撤掉。
await page.click('#btnFlush');
await page.waitForTimeout(300);
await page.fill('#q', '大促价保怎么算');
await page.click('#btnSend');
const typingText = await page.waitForSelector('#chat .typing', { timeout: 4000 })
  .then(() => page.textContent('#chat .typing'))
  .catch(() => null);
await page.waitForSelector('#timeline .ev.done', { timeout: 90000 }).catch(() => {});
const typingLeft = await page.$$eval('#chat .typing', (els) => els.length);
check('first-token wait shows an in-progress line and takes it away',
  !!typingText && /正在/.test(typingText) && typingLeft === 0,
  `typing="${(typingText || '(never shown)').trim()}" left=${typingLeft}`);

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
// 运维回执落在自己那条流里（走查第 10 项）：#timeline 只放问答事件，#opslog 放运维动作的回执。
const applied = await page.$$eval('#opslog .ops', (els) => els.map((e) => e.textContent).filter((t) => t.includes('已注入')).pop());
check('fault injection applied through gateway proxy', !!applied, (applied || '').trim());
const opsInStream = await page.$$eval('#timeline .ev', (els) => els.filter((e) => /(^|\s)ops(\s|$)/.test(e.className)).length);
const opsLogged = await page.$$eval('#opslog .ops', (els) => els.length);
check('ops receipts stay out of the answer event stream', opsInStream === 0 && opsLogged >= 1,
  `#timeline ops rows=${opsInStream}, #opslog rows=${opsLogged}`);
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

// 走查第 11 项：抽屉盖住 header 时，第二次点「工单队列」会被自己的抽屉拦住（那轮跑器在这里超时崩过）
const scrimUp = await page.$eval('#scrim', (el) => !el.hidden);
check('drawer opens with a scrim over the page', scrimUp, `scrim visible=${scrimUp}`);
const headerHit = await page.evaluate(() => {
  const box = document.getElementById('btnTickets').getBoundingClientRect();
  const top = document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2);
  return top ? (top.id || top.tagName) : 'none';
});
check('open drawer does not intercept the header button under the cursor',
  headerHit === 'btnTickets', 'elementFromPoint=' + headerHit);
await page.keyboard.press('Escape');
await page.waitForTimeout(250);
const escClosed = await page.evaluate(() => ({
  open: document.querySelectorAll('#drawer.open').length,
  scrimHidden: document.getElementById('scrim').hidden,
}));
check('Esc closes the drawer and takes the scrim with it',
  escClosed.open === 0 && escClosed.scrimHidden, JSON.stringify(escClosed));
await page.click('#btnTickets');
await page.waitForTimeout(250);
await page.click('#scrim');
await page.waitForTimeout(250);
const scrimClosed = await page.evaluate(() => ({
  open: document.querySelectorAll('#drawer.open').length,
  scrimHidden: document.getElementById('scrim').hidden,
}));
check('clicking the scrim closes the drawer', scrimClosed.open === 0 && scrimClosed.scrimHidden,
  JSON.stringify(scrimClosed));

// 走查第 12 项：小字对比度。量的是算出来的比值，不靠人眼看「差不多够深」。
// 清单按那一轮点名的那一族小字来（label / .dim / #mode），再加本票新写的三处（.hint、
// #opslog .cap、.opslog .empty）——同一支 --ink-3 换到浅灰底上会掉到 AA 线下，只量白底等于没量。
// .empty 只在空态存在，跑到这里已被内容替换：临时在 .opslog 里插一个同结构的节点量一次再撤，
// 量的是那段真实级联，不是在脚本里重算一个背景色。
const contrast = await page.evaluate(() => {
  const chan = (v) => { const s = v / 255; return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4); };
  const lum = (c) => { const m = c.match(/[\d.]+/g).map(Number); return 0.2126 * chan(m[0]) + 0.7152 * chan(m[1]) + 0.0722 * chan(m[2]); };
  const ratio = (a, b) => { const x = lum(a), y = lum(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); };
  const opaque = (c) => { const m = c.match(/[\d.]+/g); return !c.startsWith('rgba') || Number(m[3]) > 0; };
  const backOf = (el) => { for (let n = el; n; n = n.parentElement) { const c = getComputedStyle(n).backgroundColor; if (c && opaque(c)) return c; } return 'rgb(255, 255, 255)'; };
  const probe = (sel) => { const el = document.querySelector(sel); if (!el) return null;
    return { sel, r: +ratio(getComputedStyle(el).color, backOf(el)).toFixed(2) }; };
  const host = document.querySelector('.opslog');
  const ghost = document.createElement('div');
  ghost.className = 'empty';
  ghost.textContent = 'contrast-probe';
  host.appendChild(ghost);
  const rows = ['label', '.dim', '#mode', '.ev.status .k', '#opslog .cap', '.hint', '.opslog .empty']
    .map(probe);
  ghost.remove();
  return rows.filter(Boolean);
});
check('secondary text clears WCAG AA (>= 4.5:1)',
  contrast.length >= 6 && contrast.every((c) => c.r >= 4.5), JSON.stringify(contrast));

// 布局稳定性：长事件文本不得把时间线挤变形
const widths = await page.evaluate(() => {
  const pane = document.getElementById('pane-events').getBoundingClientRect().width;
  const widest = Math.max(...[...document.querySelectorAll('#timeline .ev')].map((e) => e.getBoundingClientRect().width));
  return { pane, widest };
});
check('timeline keeps its column width under long events', widths.widest <= widths.pane + 1, JSON.stringify(widths));

// 走查第 7 项：窄屏下事件时间线整块消失（原先 @media 里一句 display:none）
await page.setViewportSize({ width: 900, height: 820 });
await page.waitForTimeout(200);
const narrowPane = await page.evaluate(() => {
  const pane = document.getElementById('pane-events').getBoundingClientRect();
  return { h: Math.round(pane.height), top: Math.round(pane.top),
    rows: document.querySelectorAll('#timeline .ev').length,
    chatBottom: Math.round(document.getElementById('pane-chat').getBoundingClientRect().bottom) };
});
check('narrow viewport keeps the event timeline on screen',
  narrowPane.h > 60 && narrowPane.rows > 0 && narrowPane.top >= narrowPane.chatBottom - 1,
  JSON.stringify(narrowPane));

// 走查第 8 项：390px 下页脚横向溢出（实测要 1264px，可见 390px）
await page.setViewportSize({ width: 390, height: 780 });
await page.waitForTimeout(200);
const overflow = await page.evaluate(() => {
  const f = document.querySelector('footer');
  return { foot: f.scrollWidth, footView: f.clientWidth, doc: document.documentElement.scrollWidth, view: window.innerWidth };
});
check('narrow footer wraps instead of running off the screen',
  overflow.foot <= overflow.footView + 1 && overflow.doc <= overflow.view + 1, JSON.stringify(overflow));
await page.setViewportSize({ width: 1440, height: 900 });
await page.waitForTimeout(200);

// 票 22 那条「换身份清屏」此前只有静态页结构断言兜着（票 22 收尾双轴审查 S4），这里补上浏览器那一半
const bubblesBefore = await page.$$eval('#chat .msg', (els) => els.length);
const convBefore = await page.textContent('#conv');
await page.click('#btnLogin');
await page.waitForTimeout(400);
const bubblesSameId = await page.$$eval('#chat .msg', (els) => els.length);
check('re-issuing the same identity keeps the conversation',
  bubblesBefore > 0 && bubblesSameId === bubblesBefore, `${bubblesBefore} -> ${bubblesSameId}`);
await page.fill('#customer', 'C777');
await page.click('#btnLogin');
await page.waitForTimeout(500);
const afterSwitch = await page.$$eval('#chat .msg', (els) => els.length);
const convAfter = await page.textContent('#conv');
check('switching identity clears the bubbles and rotates the conversation id',
  afterSwitch === 0 && convAfter !== convBefore, `${bubblesBefore} -> ${afterSwitch}, conv rotated=${convAfter !== convBefore}`);

// 这条是安全断言：页面只跟同源网关说话，内部凭证一次都没出现在浏览器侧
check('browser never touches biz-mock :8091 or internal token', leaked.length === 0, leaked.slice(0, 3).join(' | '));
check('no failed or throwing requests in the page', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '));
check('every request went to the gateway origin', seen.every((u) => u.startsWith(BASE)),
  [...new Set(seen.map((u) => new URL(u).origin))].join(', '));

await page.screenshot({ path: 'docs/console.png', fullPage: false });

// ---- 负向探针：以下三处是故意打出去的失败，放在那三条安全断言与截图之后 ----
phase = 'probe';

// 走查第 3 项：空输入点发送，原先既不加气泡也不出一句话
await page.fill('#q', '   ');
const bubblesEmptyCase = await page.$$eval('#chat .msg', (els) => els.length);
await page.click('#btnSend');
await page.waitForTimeout(300);
const hintText = ((await page.textContent('#hint')) || '').trim();
const bubblesAfterEmpty = await page.$$eval('#chat .msg', (els) => els.length);
check('empty submission says so instead of going silent',
  /先输入/.test(hintText) && bubblesAfterEmpty === bubblesEmptyCase,
  `hint="${hintText}" bubbles ${bubblesEmptyCase} -> ${bubblesAfterEmpty}`);

// 走查第 4 项的前端那一半：票 25 把校验文案放进 message，这里要求它真的显在气泡里
await page.fill('#q', '退'.repeat(600));
await page.click('#btnSend');
const echoed = await page.waitForFunction(() => {
  const rows = document.querySelectorAll('#chat .msg');
  const last = rows.length ? rows[rows.length - 1].textContent : '';
  return /问题太长/.test(last) ? last : false;
}, null, { timeout: 20000 }).then((h) => h.jsonValue()).catch(async () => {
  const rows = await page.$$eval('#chat .msg', (els) => els.map((e) => e.textContent));
  return rows.length ? rows[rows.length - 1] : '(no bubble)';
});
const hintCleared = ((await page.textContent('#hint')) || '').trim();
check('overlong input echoes the backend validation message',
  /问题太长/.test(String(echoed)) && /500/.test(String(echoed)) && hintCleared === '',
  String(echoed).replace(/\s+/g, ' ').slice(0, 90));

// 走查第 9 项：读数格在失败时不许挂着上一次的成功值。两个失败原因各查一次，
// 外加「另一格不受牵连」——分家要的是每格有自己的状态，不是一起变灰。
const gauge = () => page.evaluate(() => ({
  refunds: document.getElementById('refunds').textContent,
  refundsState: document.getElementById('refunds').dataset.state,
  circuit: document.getElementById('circuit').textContent,
  circuitState: document.getElementById('circuit').dataset.state,
}));
const waitForGauge = (src) => page.waitForFunction(
  (pattern) => new RegExp(pattern).test(document.getElementById('refunds').textContent), src,
  { timeout: 8000, polling: 200 }).then(() => true).catch(() => false);

// 一：没有凭证。这不算出错，但也不能显示上一次的成功值
await page.fill('#opsToken', '');
const noTokenOk = await waitForGauge('未填令牌');
const noToken = await gauge();
check('empty ops token marks its own gauge stale instead of keeping the last value',
  noTokenOk && noToken.refundsState === 'stale' && noToken.circuitState === 'live',
  JSON.stringify(noToken));

// 二：凭证错。票 21 拆出的失败原因要能在格子里读到，票 25 之后报文里带着 code
await page.fill('#opsToken', 'definitely-not-the-ops-token');
const mismatchOk = await waitForGauge('token_mismatch');
const wrongToken = await gauge();
check('wrong ops token writes the failure reason into its own gauge',
  mismatchOk && /ops\.token_mismatch/.test(wrongToken.refunds)
    && wrongToken.refundsState === 'stale' && wrongToken.circuitState === 'live',
  JSON.stringify(wrongToken));
check('negative probes raise no page error and no unexpected status',
  probeErrors.length === 0, probeErrors.slice(0, 3).join(' | '));

await browser.close();
finish();

async function finish() {
  const failed = results.filter((r) => !r.ok);
  console.log(`\n${results.length - failed.length}/${results.length} console checks passed`);
  if (failed.length) console.log('failed: ' + failed.map((f) => f.name).join(', '));
  process.exit(failed.length ? 1 : 0);
}
