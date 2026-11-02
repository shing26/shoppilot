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
// round36 / 票 151：店铺码走 URL（ADR 0070 B2），页面不再有「店铺编号」输入框。
// round37：产品默认免密游客进入；这条门禁验的是口令登录与跨买家隔离，所以显式关掉自动游客。
const PAGE = `${BASE}/buyer/?store=${encodeURIComponent(process.env.SHOPPILOT_IDENTITY_TENANT || 'T001')}&guest=0`;
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
check(
  '买家会话能走到显式转人工并落单',
  Boolean(escalated.body.ticketId),
  `HTTP ${escalated.status} ticketId=${escalated.body.ticketId || '(无)'}`,
);
const myTicketId = escalated.body.ticketId;

// 第二个买家的种子问句**必须含 T0 升级词**。第一版写的是「这条属于另一个买家」——
// 那句话里没有「转人工」，于是它压根不会落单，第二条断言拿着一个 null 去判「页面看不到它」，
// 而它本来就是不存在的。判红的是门禁自己，产品行为是对的（清场日 2026-10-04 实测）。
const otherToken = await tokenOf('C155');
const otherEscalated = await postChat(otherToken, `我也要转人工（另一个买家 买家端门禁 ${Date.now()}）`);
const otherTicketId = otherEscalated.body.ticketId;
check(
  '另一个买家的工单真的落库了（否则「A 页面没有 B 的单」是一条恒成立的断言）',
  Boolean(otherTicketId),
  `HTTP ${otherEscalated.status} ticketId=${otherTicketId || '(无)'}`,
);

// --- 页面 ------------------------------------------------------------------------------

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
const pageErrors = [];
page.on('pageerror', (error) => pageErrors.push(String(error)));

await page.goto(PAGE, { waitUntil: 'networkidle' });
check('买家中心页面可加载（/buyer/ 由网关同源提供）', (await page.title()).includes('买家'), await page.title());

// 未登录时必须给出可读的入口说明，而不是冻在空态。
// **断言写在任何登录动作之前**：第一版先点了「登录」（空表单），于是 `.err` 里是
// 「用户名或口令不对」——那断的是登录失败，不是未登录时的工单列表（清场日 2026-10-04 实测）。
// round44 起未登录渲染的就是登录门（`guest=0` 不进游客身份），侧栏整块不出现；
// 所以「空白」的判据变成「门有没有把产品和下一步讲清楚」，不再去找侧栏里那句 `.notice`。
await page.waitForSelector('.gate', { timeout: 5000 }).catch(() => {});
const gateText = await page.locator('.gate').innerText().catch(() => '');
check(
  '未登录时把「能做什么、怎么开始」讲清楚，而不是留一段空白',
  gateText.includes('工单') && gateText.includes('转人工') && gateText.includes('账号登录'),
  gateText.replace(/\s+/g, ' ').slice(0, 80) || '(等 5 s 也没有登录门)',
);

// 真走一遍登录表单：直接往 sessionStorage 塞令牌会跳过「登录面能用」这件事。
// round36 / 票 151：店铺码从 URL 来，先拦慢一拍「我的工单」，让加载态可观测——
// 「读取中」不能被渲染成「你还没有需要人工处理的工单」。
await page.locator('.gate button:has-text("用买家账号登录")').click();
await page.route('**/api/v1/support/ops/tickets/mine', async (route) => {
  await new Promise((resolve) => setTimeout(resolve, 900));
  await route.continue();
});
await page.fill('input[placeholder="例如 buyer"]', BUYER);
await page.fill('input[placeholder="演示口令见 .env"]', PASSWORD);
await page.locator('.login button[type="submit"]').click();

// 侧栏默认停在「订单」页签；工单的加载态与列表都在「工单」页签里。
await page.locator('.side-tab:has-text("工单")').click();
const loadingSeen = await page.waitForSelector('text=正在读取…', { timeout: 5000 }).then(() => true).catch(() => false);
const loadingText = await page.locator('.side-activity').innerText().catch(() => '');
check(
  '读取工单时先说「正在读取」，不冒充空态',
  loadingSeen && loadingText.includes('正在读取') && !loadingText.includes('你还没有'),
  loadingText.replace(/\s+/g, ' ').slice(0, 80),
);

await page.waitForSelector('.ticket-card', { timeout: 12000 }).catch(() => {});
check(
  '登录后页面显示当前身份（署名取自令牌，页面上不能改）',
  (await page.locator('.who').innerText().catch(() => '')).length > 0,
  await page.locator('.who').innerText().catch(() => '(没有 .who)'),
);
check(
  '店铺码从 URL 生效、页面上不再要求输入店铺编号',
  (await page.locator('input[placeholder="店铺编号，如 T001"]').count()) === 0
    && (await page.locator('.store-card').innerText().catch(() => '')).length > 0,
  (await page.locator('.store-card').innerText().catch(() => '(没有店名/店铺码)')).replace(/\s+/g, ' ').slice(0, 60),
);

// --- 我的工单 --------------------------------------------------------------------------------

await page.waitForSelector('.ticket-card', { timeout: 8000 }).catch(() => {});
const tableText = await page.locator('.ticket-list').innerText().catch(() => '');
check('「我的工单」里能看到那张单', tableText.includes(myTicketId), myTicketId);
check(
  '**看不到另一个买家的工单**（本门禁的承重格）',
  !tableText.includes(otherTicketId),
  `另一个买家的工单号 ${otherTicketId}`,
);

// --- 提问（round36 / 票 151：对话流，不是只显示最后一条） --------------------------------------

const ask = async (question) => {
  await page.fill('input[placeholder="输入问题，例如：订单 90001 到哪了"]', question);
  await page.locator('.composer button:has-text("发送")').click();
  // 模型档的等待比 mock 长：20 s 在活体档会把「正在回答」当成已经答完（round44 实测）。
  await page
    .waitForFunction(() => {
      const bubbles = document.querySelectorAll('.msg.assistant .bubble');
      const last = bubbles[bubbles.length - 1];
      return Boolean(last && last.textContent.trim().length > 10 && !last.textContent.includes('正在回答'));
    }, null, { timeout: 45000 })
    .catch(() => {});
};

await ask('七天无理由退货怎么操作');
const firstAnswer = await page.locator('.msg.assistant .bubble').last().innerText().catch(() => '');
// 判据不能是「长度 > 0」：占位文案「正在回答」也非空，那会把「还没答」判成「答了」
// （round44 清场时这条以 `正在回答` 的原样 PASS 过一次，正是本行要堵的假绿）。
check(
  '提问后有回答或一句说清为什么的降级说明',
  firstAnswer.length > 10 && !firstAnswer.includes('正在回答'),
  firstAnswer.replace(/\s+/g, ' ').slice(0, 60),
);

// 依据面板（round44 票 190）：只在真有 citationDetails 时断言分组结构。
// 本机 ES/Qdrant 未起时检索降级、`citationDetails` 为空——那是环境缺口，如实打成 INFO，
// 不让它伪装成 PASS（同 round23 抓到的「队列空也判过」同族）。
const citeCount = await page.locator('.cites').count();
if (citeCount > 0) {
  await page.locator('.cites summary').first().click();
  const citeSummary = await page.locator('.cites summary').first().innerText();
  const groupCount = await page.locator('.cite-group').count();
  check(
    '依据面板写清「依据 N 条知识」并按文档分组',
    /依据 \d+ 条知识/.test(citeSummary) && groupCount > 0,
    `${citeSummary.replace(/\s+/g, ' ')} · ${groupCount} 组`,
  );
} else {
  console.log('INFO  依据面板：本次回答没有 citationDetails（本机 ES/Qdrant 不可用，检索降级），面板未实测');
}

await ask('订单 90002 到哪了');
const threadText = await page.locator('.thread').innerText().catch(() => '');
check(
  '连续提问保留历史（对话流不是只显示最后一条）',
  (await page.locator('.msg.user').count()) >= 2 && threadText.includes('七天无理由退货怎么操作'),
  `user 气泡 ${await page.locator('.msg.user').count()} 条`,
);

// 窄屏（round36 / 票 151）：宽表格进容器内滚动，页面本身不横向溢出。
await page.setViewportSize({ width: 390, height: 780 });
await page.waitForTimeout(200);
const overflow = await page.evaluate(() => ({
  doc: document.documentElement.scrollWidth,
  view: window.innerWidth,
}));
check('窄屏 390px 不横向溢出', overflow.doc <= overflow.view + 1, JSON.stringify(overflow));
await page.setViewportSize({ width: 1280, height: 900 });

check('没有未捕获的页面异常', pageErrors.length === 0, pageErrors.join(' | '));

await browser.close();

const failed = results.filter((r) => !r.ok);
console.log(`\n买家中心断言 ${results.length - failed.length}/${results.length} 通过`);
process.exit(failed.length === 0 ? 0 : 1);