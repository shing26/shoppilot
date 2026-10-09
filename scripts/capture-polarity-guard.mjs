// GIF 1 采集：极性守卫（PolarityGuard）现场——同桶反义问法为什么拿不到上一条答案。
//
// 两栏都是真的：左栏是调试台（http://127.0.0.1:8082/）的真实操作与真实事件时间线，
// 右栏是网关日志文件里的原始行 + /actuator 计数器的前后读数。本脚本只做点击与截图，
// 不生成任何画面内容。
//
// 为什么是这三问（docs/threshold-calibration.md 的实测对）：
//   源问法   「这个能退吗」   写入 L2
//   同极性   「这个能退么」   余弦 0.9980 → 命中 L2（语义缓存该做的事）
//   反义     「这个不能退吗」 余弦 0.9799 → 同样越过 0.95 阈值，但极性相反 → 复用前被拒
// 后两句与源问法的向量距离几乎一样，唯一差别是极性；L2 命中与否因此能直接归因到守卫。
//
// 边界（必须写清，否则这条演示会被读成它不是的东西）：
//   1. 系统**没有** "[PolarityGuard] Antonym polarity detected, bypass L2 cache."
//      这行日志——真实的那行是：
//      "L2 语义命中被极性守卫拒绝: polarity-conflict (cached=… incoming=…)"。
//   2. 反义问法拿到的答案不是「另一句针对否定问法的定制答案」，而是重新生成/复用的
//      平台政策正文；护栏说的是「绝不复用极性相反那条答案」，不是说「答案更聪明」。
//   3. 检索要能跑（Ollama bge-m3 + Qdrant + ES，且 Redis 里的知识库纪元与库内 chunk
//      一致），否则写回根本不发生，守卫无从触发——这一步红不代表防线失效。
//
// 用法（栈起着）：
//   $env:NODE_PATH="D:\ShopPilot\.tools\node_modules"
//   $env:SHOPPILOT_OPS_TOKEN="dev-ops-token"
//   node scripts/capture-polarity-guard.mjs
// 产物：logs/capture-polarity/frame-*.png（配 scripts/compose-polarity-gif.py 合成 GIF）
//
// 档位：本次采集跑在 **perf 档（MockLLM + 真 bge-m3 + 真 Qdrant/ES + 真 Redis）**——
// 被演示的链路（语义缓存、极性守卫、检索、写回）都真实发生，模型侧是 Mock。
// 这条链要能跑，Ollama bge-m3 / Qdrant / ES 都得在跑，且 Redis 里的知识库纪元
// （`shoppilot:kb:epoch`）与库内 chunk 一致，否则检索恒空、写回不发生、守卫不出现。
import { createRequire } from 'node:module';
import { mkdirSync, rmSync, existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const { chromium } = createRequire(import.meta.url)('playwright');

const BASE = process.env.SHOPPILOT_CAPTURE_BASE || 'http://127.0.0.1:8082';
const OPS_TOKEN = process.env.SHOPPILOT_OPS_TOKEN || 'dev-ops-token';
const GATEWAY_LOG = process.env.SHOPPILOT_GATEWAY_LOG ||
  join(process.cwd(), 'logs', 'gateway.log');
const OUT = process.env.SHOPPILOT_CAPTURE_OUT ||
  join(process.cwd(), 'logs', 'capture-polarity');
const METRIC = 'shoppilot_cache_l2_polarity_blocked_total';

const CONSOLE_W = 900, LOG_W = 560, PANEL_H = 940;

const SOURCE = '这个能退吗';
const SAME_POLARITY = '这个能退么';
const ANTONYM = '这个不能退吗';

rmSync(OUT, { recursive: true, force: true });
mkdirSync(OUT, { recursive: true });

// 采集起点：右栏只收这次跑出来的日志行（见 pickLogLines）。
const logStartLine = () => {
  try {
    const text = readFileSync(GATEWAY_LOG, 'utf8');
    return text.split(/\r?\n/).filter((l) => l.trim().length > 0).length;
  } catch {
    return 0;
  }
};
const linesAtStart = logStartLine();

/** 右栏：网关日志原始行 + 计数器读数。只做排版，内容不改。 */
const logPageHtml = `<!doctype html><html><head><meta charset="utf-8"><style>
  html,body{margin:0;height:100%;background:#0b1020;color:#d6e2ff;
    font:13px/1.6 "Cascadia Mono",Consolas,"Courier New",monospace}
  header{padding:10px 14px;background:#131a30;border-bottom:1px solid #243059;
    font:600 13px/1.4 "Microsoft YaHei",sans-serif;color:#8fb4ff}
  header small{display:block;font-weight:400;color:#5f729c;margin-top:3px}
  .gauge{margin:10px 14px 0;padding:9px 11px;background:#131a30;border:1px solid #243059;
    border-radius:6px;font:600 14px/1.5 "Microsoft YaHei",sans-serif;color:#ffd479}
  .gauge b{color:#7fe0b0;font-size:16px}
  pre{margin:10px 14px;padding:10px 12px;white-space:pre-wrap;word-break:break-all;
    overflow-y:auto;height:calc(100% - 190px);background:#0d1327;border:1px solid #1d2748;
    border-radius:6px}
  .line{display:block;padding:2px 0;border-bottom:1px dashed #16203c}
  .hit{background:#2a1f3d;color:#ffd479;padding:1px 3px;border-radius:3px}
  .dim{color:#5f729c}
</style></head><body>
  <header>网关日志 · logs/gateway.log 原始行<small>gateway :8082 · 左栏调试台同一时刻的真实日志</small></header>
  <div class="gauge" id="gauge">读数中…</div>
  <pre id="log"><span class="dim">等待提问…</span></pre>
</body></html>`;

/** 只保留本次采集产生的行：以开跑时的行号为起点，避免把上一轮探针的行也收进来。 */
function pickLogLines(text, fromLine) {
  const lines = text.split(/\r?\n/).filter((l) => l.trim().length > 0);
  return lines.slice(fromLine).filter((l) =>
    l.includes('极性守卫') ||
    l.includes('CacheService') ||
    l.includes('同步问答完成') ||
    l.includes('流式问答完成'));
}

async function shot(page, name) {
  await page.screenshot({ path: join(OUT, `frame-${name}.png`) });
  console.log(`  shot ${name}`);
}

async function ask(page, question) {
  // 调试台每轮开始会 clearTimeline()，所以「done 行数变多」这个判据不成立
  // （清了再加，数量可能不变）。改用发送键重新可用：它在 finally 里解锁，
  // Stream 读完后才走到，命中/未命中两条路都过这里。
  await page.fill('#q', question);
  await page.click('#btnSend');
  await page.waitForFunction(() => {
    const send = document.getElementById('btnSend');
    return !!send && !send.disabled && document.querySelectorAll('#timeline .ev.done').length > 0;
  }, { timeout: 120000 });
  // 写回是异步的（WriteBackPool），给它落地时间；否则下一问会读到空缓存。
  await page.waitForTimeout(700);
}

/** 把时间线里的 meta 行取出来：cache 层是最直接的判据。 */
async function dumpMeta(page, label) {
  const rows = await page.locator('#timeline .ev.meta .v').allInnerTexts();
  for (const row of rows.slice(-3)) {
    console.log(`    ${label} meta: ${row.replace(/\s+/g, ' ').trim()}`);
  }
}

async function counter() {
  const res = await consolePage.request.get(`${BASE}/actuator/metrics/${METRIC}`);
  if (!res.ok()) return null;
  const body = await res.json();
  const count = (body.measurements || []).find((m) => m.statistic === 'COUNT');
  return count ? Number(count.value) : null;
}

const browser = await chromium.launch();
const context = await browser.newContext({ viewport: { width: CONSOLE_W, height: PANEL_H } });
const consolePage = await context.newPage();
const logPage = await context.newPage({ viewport: { width: LOG_W, height: PANEL_H } });
await logPage.setContent(logPageHtml);

console.log('0/5 清空缓存（经网关 ops 代理，与调试台页脚那颗按钮同一条路）');
const mockToken = await consolePage.request.post(`${BASE}/auth/mock-token`, {
  data: { tenantId: 'T001', customerId: 'C001' },
});
if (!mockToken.ok()) {
  throw new Error(`mock-token 失败 HTTP ${mockToken.status()}`);
}
const token = (await mockToken.json()).token;
const flush = await consolePage.request.post(`${BASE}/api/v1/support/ops/cache/flush`, {
  headers: { Authorization: `Bearer ${token}`, 'X-Ops-Token': OPS_TOKEN },
});
if (!flush.ok()) {
  throw new Error(`cache/flush 失败 HTTP ${flush.status()}：确认栈起着且 ops token 对`);
}

console.log('1/5 打开调试台');
await consolePage.goto(`${BASE}/`, { waitUntil: 'domcontentloaded' });
await consolePage.waitForSelector('#btnSend', { timeout: 30000 });
// 调试台自己会去签 mock-token；等它签完（claims 那格有东西）再截图，否则身份栏是空的。
await consolePage.waitForFunction(
  () => document.getElementById('claims').textContent.trim() !== '未签发',
  { timeout: 30000 },
);
await consolePage.waitForTimeout(600);
const counterBefore = await counter();
console.log(`  ${METRIC} 初始读数 = ${counterBefore}`);
await shot(consolePage, '01-console-ready');
await shot(logPage, '01-log-idle');

console.log('2/5 源问法（先让答案写进缓存）');
await ask(consolePage, SOURCE);
await dumpMeta(consolePage, SOURCE);
await shot(consolePage, '02-console-source');
await shot(logPage, '02-log-source');

console.log('3/5 同极性近义问法（应该命中 L2）');
await ask(consolePage, SAME_POLARITY);
await dumpMeta(consolePage, SAME_POLARITY);
await shot(consolePage, '03-console-same');
await shot(logPage, '03-log-same');

console.log('4/5 反义问法（守卫要在复用前拒掉）');
await ask(consolePage, ANTONYM);
await dumpMeta(consolePage, ANTONYM);
await shot(consolePage, '04-console-anti');
await shot(logPage, '04-log-anti');

console.log('5/5 收右栏：真实日志行 + 计数器前后读数');
const counterAfter = await counter();
console.log(`  ${METRIC} 结束读数 = ${counterAfter}（Δ = ${counterAfter - counterBefore}）`);
if (!existsSync(GATEWAY_LOG)) {
  throw new Error(`找不到网关日志 ${GATEWAY_LOG}——右栏要的是真实日志行，没有就不合成 GIF`);
}
const lines = pickLogLines(readFileSync(GATEWAY_LOG, 'utf8'), linesAtStart);
if (!lines.some((l) => l.includes('极性守卫'))) {
  throw new Error('网关日志里没有「极性守卫」行：这次采集不成立，别拿它演示');
}
writeFileSync(join(OUT, 'gateway-lines.txt'), lines.join('\n'));

await logPage.evaluate(({ lines, question, before, after }) => {
  document.getElementById('gauge').innerHTML =
    `${question}<br><span class="dim">进程累计值</span> <b>${before}</b> → <b>${after}</b>`
    + `（增量 ${after - before}）`;
  const marked = lines.map((l) => {
    const escaped = l.replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
    return l.includes('极性守卫')
      ? `<span class="line hit">${escaped}</span>`
      : `<span class="line">${escaped}</span>`;
  });
  document.getElementById('log').innerHTML = marked.join('\n');
}, { lines, question: METRIC, before: counterBefore, after: counterAfter });
await logPage.waitForTimeout(200);
await shot(logPage, '05-log-guard');

await browser.close();
console.log(`\n帧已写入 ${OUT}`);
console.log(`日志行已存 ${join(OUT, 'gateway-lines.txt')}`);
