// 买家中心的活体验收（round27 票 94 / ADR 0057）。
//
// 用法: $env:NODE_PATH="<repo>\.tools\node_modules"; node scripts/verify-buyer.mjs
//   环境变量：
//     SHOPPILOT_GATEWAY_BASE       默认 http://127.0.0.1:8082
//     SHOPPILOT_IDENTITY_TENANT    默认 T001
//     SHOPPILOT_IDENTITY_AGENT     默认 buyer
//     SHOPPILOT_IDENTITY_DEMO_PASSWORD   必给：登录口令不进仓库（ADR 0056）
//
// **本轮（2026-10-04）未实跑**：起栈要 ≈6.6 GB 而本机只剩约 1.9 GB（所有者裁定 A）。
// 脚本已写好并通过 `node --check`，实跑读数按**未达成登记**，不许声称「已验证」。
//
// 承重的一格是**跨买家隔离**：买家 A 的页面上不许出现买家 B 的工单号，
// 而且 **B 的工单必须真的存在过**——否则「A 的页面没有 B 的单」可能只是因为 B 压根没有单，
// 那是一条恒成立的断言（同 round23 抓到的「队列空也判过」同族假绿）。
import { createRequire } from 'node:module';
const { chromium } = createRequire(import.meta.url)('playwright');

const BASE = process.env.SHOPPILOT_GATEWAY_BASE || 'http://127.0.0.1:8082';
const PAGE = `${BASE}/buyer/`;
const TENANT = process.env.SHOPPILOT_IDENTITY_TENANT || 'T001';
const BUYER = process.env.SHOPPILOT_IDENTITY_AGENT || 'buyer';
const PASSWORD = process.env.SHOPPILOT_IDENTITY_DEMO_PASSWORD;

if (!PASSWORD) {
  console.error(
    '缺少 SHOPPILOT_IDENTITY_DEMO_PASSWORD：买家端用账号登录，' +
      '演示账号的口令不进仓库（ADR 0056）。请在 .env / 容器档 .env 里给一个再跑。',
  );
  process.exit(2);
}

const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
};

const tokenOf = async (customerId) =>
  (
    await (
      await fetch(`${BASE}/auth/mock-token`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ tenantId: TENANT, customerId }),
      })
    ).json()
  ).token;

const postChat = async (token, query) => {
  const response = await fetch(`${BASE}/api/v1/support/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ query, idempotencyToken: `buyer-gate-${Date.now()}-${Math.random()}` }),
  });
  return { status: response.status, body: await response.json() };
};

// --- 门禁自己先造两张工单：一张给演示买家，一张给「另一个买家」 --------------------------------

const buyerToken = await tokenOf('C001');
const escalated = await postChat(buyerToken, `我要转人工（买家端门禁 ${Date.now()}）`);
check('买家会话能走到显式转人工并落单', Boolean(escalated.body.ticketId), `ticketId=${escalated.body.ticketId || '(无)'}`);
const myTicketId = escalated.body.ticketId;

const otherToken = await tokenOf('C155');
const otherEscalated = await postChat(otherToken, `这条属于另一个买家（买家端门禁 ${Date.now()}）`);
const otherTicketId = otherEscalated.body.ticketId;
check(
  '另一个买家的工单真的落库了（否则「A 页面没有 B 的单」是一条恒成立的断言）',
  Boolean(otherTicketId),
  `ticketId=${otherTicketId || '(无)'}`,
);

// --- 页面 ------------------------------------------------------------------------------

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
const pageErrors = [];
page.on('pageerror', (error) => pageErrors.push(String(error)));

await page.goto(PAGE, { waitUntil: 'networkidle' });
check('买家中心页面可加载（/buyer/ 由网关同源提供）', (await page.title()).includes('买家'), await page.title());

// 未登录时必须给出可读原因，而不是冻在空态。
await page.locator('button:has-text("登录")').click().catch(() => {});
await page.waitForTimeout(300);
check(
  '未登录时「我的工单」给出可读原因，而不是空态',
  (await page.locator('.err').innerText().catch(() => '')).includes('登录'),
  await page.locator('.err').innerText().catch(() => '(没有 .err)'),
);

// 真走一遍登录表单：直接往 sessionStorage 塞令牌会跳过「登录面能用」这件事。
await page.fill('input[placeholder="店铺编号，如 T001"]', TENANT);
await page.fill('input[placeholder="买家账号"]', BUYER);
await page.fill('input[placeholder="口令"]', PASSWORD);
await page.locator('button:has-text("登录")').click();
await page.waitForTimeout(800);
check(
  '登录后页面显示当前身份（署名取自令牌，页面上不能改）',
  (await page.locator('.who').innerText().catch(() => '')).length > 0,
  await page.locator('.who').innerText().catch(() => '(没有 .who)'),
);

// --- 我的工单 --------------------------------------------------------------------------------

await page.waitForSelector('tbody tr', { timeout: 8000 }).catch(() => {});
const tableText = await page.locator('tbody').innerText().catch(() => '');
check('「我的工单」里能看到那张单', tableText.includes(myTicketId), myTicketId);
check(
  '**看不到另一个买家的工单**（本门禁的承重格）',
  !tableText.includes(otherTicketId),
  `另一个买家的工单号 ${otherTicketId}`,
);

// --- 提问 --------------------------------------------------------------------------------------

await page.fill('input[placeholder="七天无理由退货怎么操作"]', '七天无理由退货怎么操作');
await page.locator('button:has-text("提问")').click();
await page.waitForTimeout(2500);
const answerText = await page.locator('.answer').innerText().catch(() => '');
check('提问后有回答或一句说清为什么的降级说明', answerText.length > 0, answerText.replace(/\s+/g, ' ').slice(0, 60));

check('没有未捕获的页面异常', pageErrors.length === 0, pageErrors.join(' | '));

await browser.close();

const failed = results.filter((r) => !r.ok);
console.log(`\n买家中心断言 ${results.length - failed.length}/${results.length} 通过`);
process.exit(failed.length === 0 ? 0 : 1);