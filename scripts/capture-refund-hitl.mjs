// GIF 2 采集：退款涉资动作的人机协同阻断（ADR 0063 / ADR 0047）。
//
// 三个真实画面同屏：左侧买家端（/buyer/）发起退款 → 中间网关 SSE 事件流
// （同一笔请求的真实响应帧，由驱动的 response.body() 抓取后在中间栏原样呈现）
// → 右侧客服工作台（/workspace/）坐席看到待审退款并一键放行。
//
// 关键坑（本次采集中踩过并修掉）：
//   1. **不要在页面里 clone() 流式响应**：page.addInitScript 里 res.clone() +
//      自己起 reader 会把 Chromium 渲染进程搞崩（Target crashed），采集脚本
//      表现为 waitForFunction 静默挂住。改从驱动侧拿 response.body()。
//   2. **每次采集前必须 reset 演示夹具**：90001 一旦进入 PENDING_REVIEW，
//      再打同一单走的是「重复受理」支路，买家看到的是 MockLLM 的罐头政策答案，
//      而不是「已受理等待人工审核」。
//   3. goto 用 domcontentloaded 而不是 networkidle：调试台类页面有长连接，
//      networkidle 会白等。
//
// 用法（栈起着）：
//   $env:NODE_PATH="D:\ShopPilot\.tools\node_modules"
//   $env:SHOPPILOT_OPS_TOKEN="dev-ops-token"          # 回环默认值，见 verify-polarity.ps1
//   $env:SHOPPILOT_IDENTITY_DEMO_PASSWORD="…"         # 演示账号口令，只从环境读、不进仓库
//   node scripts/capture-refund-hitl.mjs
// 产物：logs/capture-refund/frame-*.png（配 scripts/compose-refund-gif.py 合成 GIF）
//
// 边界（两处，都得说清）：
//   1. 买家浏览器会话**没有**出站推送（出站只走 webhook/email，见
//      OutboundDeliveryService），所以本片不包含「买家收到审核结果推送」那一帧——
//      系统至今没有这条链路，编一帧就是造假。
//   2. 本片跑在 **perf 档（MockLLM）**：状态机、SSE 帧、工具编排、坐席审核都是真的，
//      模型侧是 Mock（工具参数与答案文案由 Mock 模板产出）。
import { createRequire } from 'node:module';
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const { chromium } = createRequire(import.meta.url)('playwright');

const BASE = process.env.SHOPPILOT_CAPTURE_BASE || 'http://127.0.0.1:8082';
const OPS_TOKEN = process.env.SHOPPILOT_OPS_TOKEN || 'dev-ops-token';
// 演示账号口令只从环境变量读，不进仓库（ADR 0056 同一条家法，与 verify-workspace /
// verify-buyer 的读法一致）；没给就明说别跑，不退回 ops token 自报身份。
const DEMO_PASSWORD = process.env.SHOPPILOT_IDENTITY_DEMO_PASSWORD;
if (!DEMO_PASSWORD) {
  console.error(
    '缺少 SHOPPILOT_IDENTITY_DEMO_PASSWORD：两端都要用账号登录，' +
      '演示口令不进仓库（ADR 0056）。请在 .env 里给一个再跑。',
  );
  process.exit(2);
}
const OUT = process.env.SHOPPILOT_CAPTURE_OUT ||
  join(process.cwd(), 'logs', 'capture-refund');
const QUERY = '订单 90001 我要申请退款，商品有质量问题';
const BUYER_W = 500, SEAT_W = 520, LOG_W = 400, PANEL_H = 720;

rmSync(OUT, { recursive: true, force: true });
mkdirSync(OUT, { recursive: true });

const logPageHtml = `<!doctype html><html><head><meta charset="utf-8"><style>
  html,body{margin:0;height:100%;background:#0b1020;color:#d6e2ff;
    font:13px/1.65 "Cascadia Mono",Consolas,"Courier New",monospace}
  header{padding:10px 14px;background:#131a30;border-bottom:1px solid #243059;
    font:600 13px/1.4 "Microsoft YaHei",sans-serif;color:#8fb4ff}
  header small{display:block;font-weight:400;color:#5f729c;margin-top:3px}
  pre{margin:0;padding:12px 14px;white-space:pre-wrap;word-break:break-all;
    overflow-y:auto;height:calc(100% - 62px)}
  .ev{color:#7fe0b0}.ar{color:#ffd479}.st{color:#8fb4ff;font-weight:600}
  .dim{color:#5f729c}
</style></head><body>
  <header>网关事件流 · SSE /api/v1/support/chat/stream<small>gateway :8082 · 左侧买家端同一笔请求的真实响应帧</small></header>
  <pre id="log"><span class="dim">等待请求…</span></pre>
</body></html>`;

/** 把 SSE 原始帧渲染成中间栏的事件流（只做排版，内容不改）。 */
function renderFrames(frames) {
  const lines = [];
  for (const f of frames) {
    let data;
    try { data = JSON.parse(f.data); } catch { data = f.data; }
    if (typeof data !== 'object' || data === null) data = {};
    if (f.event === 'status') {
      lines.push(`<span class="st">[${data.state || '?'}]</span> ${data.label || ''} <span class="dim">${data.detail || ''}</span>`);
    } else if (f.event === 'meta') {
      lines.push(`<span class="st">[meta]</span> intent=${data.intent} cache=${data.cacheLayer} channel=${data.channel}`);
    } else if (f.event === 'tool_executing') {
      lines.push(`<span class="ar">→</span> ${data.tool} <span class="dim">${data.label || ''}</span>`);
    } else if (f.event === 'tool_result') {
      lines.push(`<span class="ar">←</span> <b>${data.tool} ${data.status}</b> <span class="dim">${data.summary || ''}</span>`);
    } else if (f.event === 'token') {
      lines.push(`<span class="ev">「</span>${data}<span class="ev">」</span>`);
    } else if (f.event === 'fallback') {
      lines.push(`<span class="ar">[fallback]</span> ${data.reason} ticket=${data.ticketId || '-'}`);
    } else if (f.event === 'done') {
      const plan = (data.plan || []).map((p) => `${p.tool}:${p.status}`).join(', ');
      lines.push(`<span class="st">[done]</span> ${plan} <span class="dim">${data.usage ? data.usage.promptTokens + '/' + data.usage.completionTokens + ' tok' : ''}</span>`);
    }
  }
  return lines.join('\n');
}

async function shot(page, name) {
  await page.screenshot({ path: join(OUT, `frame-${name}.png`) });
  console.log(`  shot ${name}`);
}

async function login(page, ready, username) {
  await page.fill('section.ctl label:nth-of-type(1) input', 'T001');
  await page.fill('section.ctl label:nth-of-type(2) input', username);
  await page.fill('section.ctl label:nth-of-type(3) input', DEMO_PASSWORD);
  await page.click('section.ctl button:text-is("登录")');
  await page.waitForSelector(ready, { timeout: 30000 });
}

const browser = await chromium.launch();
const context = await browser.newContext({
  viewport: { width: BUYER_W, height: PANEL_H },
  deviceScaleFactor: 1,
});
const buyer = await context.newPage();
const seat = await context.newPage({ viewport: { width: SEAT_W, height: PANEL_H } });
const logPage = await context.newPage({ viewport: { width: LOG_W, height: PANEL_H } });
await logPage.setContent(logPageHtml);

buyer.on('dialog', (d) => d.accept());
seat.on('dialog', (d) => d.accept());

// SSE 从驱动侧抓：avoid in-page clone() crashing the renderer（见文件头坑 1）。
// Playwright 的 response.body() 是 Buffer（不提供增量流），所以中间栏在流结束后
// 一次性呈现全部真实帧。
const frames = [];
buyer.on('response', async (response) => {
  if (!response.url().includes('/api/v1/support/chat/stream')) return;
  try {
    const body = await response.body();
    let buffer = body.toString('utf8');
    let split = buffer.indexOf('\n\n');
    while (split >= 0) {
      const frame = buffer.slice(0, split);
      buffer = buffer.slice(split + 2);
      let event = '';
      const data = [];
      for (const line of frame.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim();
        else if (line.startsWith('data:')) data.push(line.slice(5).trim());
      }
      if (event || data.length) frames.push({ event, data: data.join(' ') });
      split = buffer.indexOf('\n\n');
    }
  } catch (error) {
    console.log(`  SSE 抓取失败：${error.message}`);
  }
});

async function paintLog() {
  await logPage.evaluate((html) => {
    const pre = document.getElementById('log');
    pre.innerHTML = html;
    pre.scrollTop = pre.scrollHeight;
  }, renderFrames(frames));
  // 帧是中间栏唯一的内容来源，所以把原始帧落一份：截图看不出渲染问题，
  // 这份 JSON 能（事后核对「屏上那几行」与「真实响应」是否一致）。
  writeFileSync(join(OUT, 'sse-frames.json'), JSON.stringify(frames, null, 2));
  console.log(`  SSE ${frames.length} 帧 → sse-frames.json`);
  for (const f of frames) {
    console.log(`    [${f.event}] ${f.data.slice(0, 160)}`);
  }
}

/** 把面板可见文字打出来：截图是像素，这段是内容，两者要能对上。 */
async function dumpPanel(page, name, selector, label) {
  try {
    const text = (await page.locator(selector).innerText()).replace(/\s+/g, ' ').trim();
    console.log(`  ${name} ${label}：${text.slice(0, 300)}`);
  } catch (error) {
    console.log(`  ${name} ${label}读取失败：${error.message}`);
  }
}

console.log('0/6 重置演示夹具（经网关 ops 代理）');
// ops 面要「已验签身份 + ops token」两个头（verify-polarity.ps1 同款口径）；
// mock-token 是买家侧演示令牌，不带口令，足够过身份闸。
const mockToken = await buyer.request.post(`${BASE}/auth/mock-token`, {
  data: { tenantId: 'T001', customerId: 'C001' },
});
if (!mockToken.ok()) {
  throw new Error(`mock-token 失败 HTTP ${mockToken.status()}`);
}
const token = (await mockToken.json()).token;
const reset = await buyer.request.post(`${BASE}/api/v1/support/ops/demo/reset`, {
  headers: { Authorization: `Bearer ${token}`, 'X-Ops-Token': OPS_TOKEN },
});
if (!reset.ok()) {
  throw new Error(`demo/reset 失败 HTTP ${reset.status()}：确认栈起着且 ops token 对`);
}

console.log('1/6 打开两端');
await buyer.goto(`${BASE}/buyer/`, { waitUntil: 'domcontentloaded' });
await seat.goto(`${BASE}/workspace/`, { waitUntil: 'domcontentloaded' });
await buyer.waitForSelector('section.ctl', { timeout: 30000 });
await seat.waitForSelector('section.ctl', { timeout: 30000 });
await buyer.waitForTimeout(600);
await shot(buyer, '01-login-buyer');
await shot(seat, '01-login-seat');

console.log('2/6 登录两端');
await login(buyer, 'section.ask', 'buyer');
await login(seat, '.refund-panel', 'agent');
await buyer.waitForTimeout(800);
await shot(buyer, '02-buyer-ready');
await shot(seat, '02-seat-empty-queue');
await paintLog();
await shot(logPage, '03-log-waiting');

console.log('3/6 买家输入退款请求');
await buyer.fill('section.ask input[type="text"]', QUERY);
await buyer.waitForTimeout(400);
await shot(buyer, '03-typing');

console.log('4/6 发送并跟踪真实 SSE 帧');
await buyer.click('section.ask button:text-is("提问")');
await buyer.waitForFunction(() => {
  const body = document.querySelector('section.answer .body');
  return !!(body && body.textContent.includes('等待人工审核'));
}, { timeout: 120000 });
await buyer.waitForTimeout(500);
await shot(buyer, '04-buyer-answer');
// response.body() 在流结束后才给全量，所以这里等它落齐（最多几秒）
for (let i = 0; i < 40 && frames.length === 0; i++) {
  await buyer.waitForTimeout(250);
}
if (frames.length === 0) {
  throw new Error('SSE 帧没抓到：中间栏会是空的，这一版 GIF 不成立');
}
await paintLog();
await shot(logPage, '05-log-full');

console.log('5/6 工作台重载（onMounted 补拉退款队列）');
// 退款面板没有独立刷新按钮（只在登录/挂载时拉一次），所以这里整页重载：
// 会话从 sessionStorage 恢复，onMounted 的 refreshRefunds() 会再拉一次队列。
await seat.reload({ waitUntil: 'domcontentloaded' });
await seat.waitForSelector('.refund-panel table tr td.mono', { timeout: 30000 });
await seat.waitForTimeout(400);
await shot(seat, '06-seat-pending');
await dumpPanel(seat, '06', '.refund-panel', '退款面板');

console.log('6/6 坐席放行');
await seat.click('.refund-panel table button:text-is("放行")');
await seat.waitForFunction(() => {
  const note = document.querySelector('.refund-panel .note');
  return !!(note && note.textContent.length > 0);
}, { timeout: 30000 });
await seat.waitForTimeout(400);
await shot(seat, '07-seat-approved');
await dumpPanel(seat, '07', '.refund-panel', '退款面板');
await dumpPanel(buyer, '04', 'section.answer', '买家答复');

await browser.close();
console.log(`\n帧已写入 ${OUT}`);
