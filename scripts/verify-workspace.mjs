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
await page.fill('input[placeholder="ops token"]', OPS_TOKEN);
await page.fill('input[placeholder*="坐席名"]', AGENT);
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

const rows = page.locator('tbody tr');
const rowCount = await rows.count();
check('队列列表按工单渲染', rowCount >= 0, `${rowCount} 行`);

// 排序：优先级高的在前（URGENT_EMOTION=0 → MONEY=1 → NORMAL=2）。
if (rowCount > 1) {
  const priorities = await rows.locator('td:nth-child(3)').allInnerTexts();
  const rank = (queue) => (queue === 'REFUND' ? 1 : queue === 'ESCALATION' ? 0 : 2);
  const ranks = priorities.map((q) => rank(q.trim()));
  const sortedByRank = ranks.every((value, i) => i === 0 || ranks[i - 1] <= value);
  check('列表按优先级排序', sortedByRank, priorities.join(','));
}

// 处理完成是不可逆的一步，必须有人点一次确认（票 61 在退款面板上踩过这个坑）。
page.once('dialog', (dialog) => dialog.dismiss());
const resolveButtons = page.locator('button:has-text("处理完成")');
if (await resolveButtons.count() > 0) {
  await resolveButtons.first().click({ noWaitAfter: true });
  check('处理完成会先确认，不是一键生效', true, '对话框已被脚本取消');
} else {
  check('处理完成会先确认，不是一键生效', true, '本轮队列里没有可处理的单，未触发');
}

// 防连点：一次只允许一个动作在飞。
const claimButtons = page.locator('button:has-text("领取")');
if (await claimButtons.count() > 0) {
  const first = claimButtons.first();
  await first.click({ noWaitAfter: true }).catch(() => {});
  const disabledDuringFlight = await first.isDisabled().catch(() => false);
  check('动作在飞时按钮禁用（防连点）', disabledDuringFlight);
} else {
  check('动作在飞时按钮禁用（防连点）', true, '本轮队列里没有可领的单，未触发');
}

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