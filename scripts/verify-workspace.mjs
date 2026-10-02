// 坐席工作台验收（round23 票 73 / ADR 0057）。
//
// 用法: $env:NODE_PATH="<repo>\.tools\node_modules"; node scripts/verify-workspace.mjs
// 用 createRequire 而不是静态 import：ESM 解析不吃 NODE_PATH，而 Playwright 装在 .tools 下，
// 不为一个验收脚本给项目拉一份 package.json（同 verify-console.mjs 的理由）。
//
// **本轮（2026-10-02）未实跑**：起栈要 ≈7 GB 而本机只剩 0.5 GB（所有者裁定 A）。
// 脚本已写好并通过 `node --check`，实跑读数按**未达成登记**，不许声称「已验证」。
import { createRequire } from 'node:module';
const { chromium } = createRequire(import.meta.url)('playwright');

const BASE = process.env.SHOPPILOT_WORKSPACE_BASE || 'http://127.0.0.1:8082';
const PAGE = `${BASE}/workspace/`;
const OPS_TOKEN = process.env.SHOPPILOT_OPS_TOKEN || 'dev-ops-token';
const AGENT = process.env.SHOPPILOT_AGENT || 'verify-agent';

const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
};

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

const consoleErrors = [];
const pageErrors = [];
page.on('console', (msg) => {
  if (msg.type() === 'error') consoleErrors.push(msg.text());
});
page.on('pageerror', (error) => pageErrors.push(String(error)));

await page.goto(PAGE, { waitUntil: 'networkidle' });

check('工作台页面可加载（/workspace/ 由网关同源提供）', (await page.title()).length > 0, await page.title());
check('页面标题是坐席工作台', (await page.locator('h1').innerText()).includes('坐席'));

// 凭证形态：本轮只有 ops token + 自报坐席名，真身份域是下一轮（ADR 0056）。
//
// **直接写 localStorage 再重载**，而不是 fill + 点刷新：fill 只触发 input、不触发 @change，
// 于是凭证存不进 localStorage、后续刷新全 401——第一版就是这么写的，结果队列 0 行、
// 三条交互断言「未触发」却整体 PASS。那是假绿（本仓的门禁不许自己跳过触发条件）。
await page.evaluate(([token, agent]) => {
  localStorage.setItem('shoppilot.opsToken', token);
  localStorage.setItem('shoppilot.agent', agent);
}, [OPS_TOKEN, AGENT]);
await page.reload({ waitUntil: 'networkidle' });
await page.locator('button:has-text("刷新")').click();
await page.waitForLoadState('networkidle');

// 无凭证时不许冻在空态——那是票 61 修过的坑（读数格停在「-」、抽屉停在「尚未拉取」）。
await page.fill('input[placeholder="ops token"]', '');
await page.locator('button:has-text("刷新")').click();
await page.waitForTimeout(300);
check(
  '没有运维令牌时给出可读原因，而不是空态',
  (await page.locator('.err').innerText().catch(() => '')).includes('运维令牌'),
);

await page.fill('input[placeholder="ops token"]', OPS_TOKEN);
await page.locator('button:has-text("刷新")').click();
await page.waitForLoadState('networkidle');

// **门禁自己先造一张工单**，不去依赖前一步的残留。
// 理由：工单服务的库是内存库（ADR 0053 的搬迁前提），任何一次重启都会把它清空；
// 而「队列里有东西」这件事如果靠别处留下，门禁就会在别的顺序下变成假绿。
// 这里走一遍最真实的来路：买家会话 → 显式转人工 → 网关落单 → 工单服务分派 → 出现在队列里。
const buyerToken = await (await fetch(`${BASE}/auth/mock-token`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ tenantId: 'T001', customerId: 'C001' }),
})).json().then((r) => r.token);
const seeded = await fetch(`${BASE}/api/v1/support/chat`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${buyerToken}` },
  body: JSON.stringify({ query: '我不想跟机器说了，转人工', idempotencyToken: `ws-gate-${Date.now()}` }),
});
check('买家会话能走到显式转人工（降级落单那条路是通的）', seeded.ok, `HTTP ${seeded.status}`);

await page.locator('button:has-text("刷新")').click();
await page.waitForLoadState('networkidle');

// 有上限地等第一行渲染出来：Vue 重渲染发生在 fetch 之后，networkidle 不保证它已经画完。
// **等不到仍然判红**（超时后 catch 落空，下面 count 为 0 → FAIL），所以这不是放宽，是把「渲染没跟上」
// 和「队列真的空」这两种失败分开。
await page.waitForSelector('tbody tr', { timeout: 8000 }).catch(() => {});
const rows = page.locator('tbody tr');
const rowCount = await rows.count();
// **队列为空判红，不判过**：这个门禁的承重部分就是「领取/处理真的会发生」，
// 队列空了就等于什么都没验，却输出一整屏 PASS。
check('队列里有待处理工单（空队列说明凭证或分派没通）', rowCount > 0, `${rowCount} 行`);

// 排序：**读优先级那一列**，不是拿队列去推。
// 反例就在真实数据里——ESCALATION 队列同时装着情绪升级(high) 与普通(normal)，
// 按队列名去反推优先级会把两种口径混成一列，于是「看起来排错了」其实是断言本身不成立。
const labels = await rows.locator('td:nth-child(4)').allInnerTexts();
const rank = (label) => ({ 紧急: 0, 资金: 1, 普通: 2 })[label.trim()] ?? 2;
const ranks = labels.map(rank);
if (new Set(ranks).size >= 2) {
  check(
    '列表按优先级排序（紧急 → 资金 → 普通）',
    ranks.every((value, i) => i === 0 || ranks[i - 1] <= value),
    labels.join(','),
  );
} else {
  // 队列里只有一种优先级时排序无从证明。**打 WARN 而不是静默跳过**：
  // 门禁自己跳过的断言和通过的断言，在读数里长得一样。
  console.log(`WARN  队列只有一种优先级（${labels.join(',') || '空'}），本轮未覆盖排序断言`);
}

// 处理完成是不可逆的一步，必须有人点一次确认（票 61 在退款面板上踩过这个坑）。
let confirmShown = false;
page.on('dialog', (dialog) => {
  confirmShown = true;
  void dialog.dismiss();
});
await page.locator('button:has-text("处理完成")').first().click({ noWaitAfter: true }).catch(() => {});
await page.waitForTimeout(400);
check('处理完成会先确认，不是一键生效', confirmShown, confirmShown ? '对话框已弹出并被脚本取消' : '没弹出确认框');

// 防连点：一次只允许一个动作在飞。
// 领取：真点一次，并看它是否进了「已领取」那一档（领取人的名字出现在该行）。
const claimButtons = page.locator('button:has-text("领取")');
check('队列里有可领取的按钮', (await claimButtons.count()) > 0);
const firstClaim = claimButtons.first();
await firstClaim.click({ noWaitAfter: true }).catch(() => {});
// **轮询**而不是固定等 600 ms：领取要穿过网关再到工单服务（两跳），
// 固定等待在慢一点的机器上就是间歇红——本仓吃过「间歇红被当成偶发」的亏。
await page
  .waitForFunction((agent) => document.querySelector('tbody tr')?.innerText.includes(agent), AGENT, { timeout: 15000 })
  .catch(() => {});
const firstRowText = await page.locator('tbody tr').first().innerText();
check(
  '领取后该行出现领取人（动作真的打到了工单服务）',
  firstRowText.includes(AGENT),
  firstRowText.replace(/\s+/g, ' ').slice(0, 80),
);

check('没有未捕获的页面异常', pageErrors.length === 0, pageErrors.join(' | '));
check(
  '没有意外的 console error',
  consoleErrors.filter((line) => !/Failed to load resource/.test(line)).length === 0,
  consoleErrors.join(' | '),
);

await browser.close();

const failed = results.filter((r) => !r.ok);
console.log(`\n坐席工作台断言 ${results.length - failed.length}/${results.length} 通过`);
process.exit(failed.length === 0 ? 0 : 1);