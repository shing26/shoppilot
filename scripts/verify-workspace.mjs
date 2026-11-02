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

// 票 83：工作台改成**坐席账号登录**，凭证形态从「ops token + 自报坐席名」换成真登录。
// 口令来自环境变量而不是仓库默认值（ADR 0029 的同一条家法）；没给就明说别跑，别退回去
// 用 ops token——那正是本票要退役的东西，退回去等于这个门禁在验一个已经不存在的产品形态。
const TENANT = process.env.SHOPPILOT_IDENTITY_TENANT || 'T001';
const USERNAME = process.env.SHOPPILOT_IDENTITY_AGENT || 'agent';
const PASSWORD = process.env.SHOPPILOT_IDENTITY_DEMO_PASSWORD;
if (!PASSWORD) {
  console.error(
    '缺少 SHOPPILOT_IDENTITY_DEMO_PASSWORD：票 83 起工作台用坐席账号登录，' +
      '演示账号的口令不进仓库（ADR 0056）。请在 .env / 容器档 .env 里给一个再跑。',
  );
  process.exit(2);
}

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
check('页面标题是坐席工作台', (await page.locator('.brand').innerText()).includes('坐席'));

// **走页面上的登录表单**，而不是直接往 localStorage/sessionStorage 里塞令牌：
// 直接塞跳过了「登录面真的能用」这件事，而那正是票 83 的交付内容。
// （早先那版是塞 ops token + 自报坐席名；票 83 起那条路已经不存在了。）
//
// 未登录时先断一格：没有身份时必须给出可读原因，而不是冻在空态——那是票 61 修过的坑。
// round44 票 188 把未登录面重画成 `.login-card`：整页只画登录门（侧栏不出现），
// 所以「可读原因」落在门上那句 lede，而不是旧版的 `.notice`（那个类已经随拆分消失）。
await page.reload({ waitUntil: 'networkidle' });
await page.waitForSelector('.login-card', { timeout: 5000 }).catch(() => {});
const gateText = await page.locator('.login-card').innerText().catch(() => '');
check(
  '未登录时给出可读原因，而不是空态',
  gateText.includes('登录') && gateText.includes('队列'),
  gateText.replace(/\s+/g, ' ').slice(0, 80) || '(等 5 s 也没有登录门)',
);

await page.locator('.login-card button:has-text("用账号口令登录")').click();
await page.fill('input[placeholder="例如 T001"]', TENANT);
await page.fill('input[placeholder="例如 agent"]', USERNAME);
await page.fill('input[placeholder="演示口令见 .env"]', PASSWORD);
await page.locator('.login-form button[type="submit"]').click();
await page.waitForTimeout(600);
check(
  '登录后页面显示当前身份（署名取自令牌，页面上不能改）',
  (await page.locator('.identity').innerText().catch(() => '')).includes(USERNAME),
  await page.locator('.identity').innerText().catch(() => '(没有 .identity)'),
);
await page.locator('button:has-text("刷新队列")').click();
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

await page.locator('button:has-text("刷新队列")').click();
await page.waitForLoadState('networkidle');

// 有上限地等第一行渲染出来：Vue 重渲染发生在 fetch 之后，networkidle 不保证它已经画完。
// **等不到仍然判红**（超时后 catch 落空，下面 count 为 0 → FAIL），所以这不是放宽，是把「渲染没跟上」
// 和「队列真的空」这两种失败分开。
// round44 票 188：队列从 `<table>` 换成卡片列表（`.ticket-card`），行数口径不变。
await page.waitForSelector('.ticket-card', { timeout: 8000 }).catch(() => {});
const rows = page.locator('.ticket-card');
const rowCount = await rows.count();
// **队列为空判红，不判过**：这个门禁的承重部分就是「领取/处理真的会发生」，
// 队列空了就等于什么都没验，却输出一整屏 PASS。
check('队列里有待处理工单（空队列说明凭证或分派没通）', rowCount > 0, `${rowCount} 行`);

// 排序：**读优先级那一格**，不是拿队列去推。
// 反例就在真实数据里——ESCALATION 队列同时装着情绪升级(high) 与普通(normal)，
// 按队列名去反推优先级会把两种口径混成一列，于是「看起来排错了」其实是断言本身不成立。
const labels = await rows.locator('.priority').allInnerTexts();
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
// round36 / 票 151：确认从原生 confirm 换成带意见文本框的对话框——原来那条 confirm 只证明
// 「有人拦了一下」，而意见框证明的是「结论真的会被填进去」（ADR 0059 的结果回流正文）。
await page.locator('button:has-text("处理完成")').first().click({ noWaitAfter: true }).catch(() => {});
await page.waitForTimeout(300);
const dialogShown = await page.locator('.dialog').isVisible().catch(() => false);
check('处理完成先打开确认对话框，不是一键生效', dialogShown);

let emptyNoteBlocked = false;
if (dialogShown) {
  await page.locator('.dialog button:has-text("确认结单")').click({ noWaitAfter: true }).catch(() => {});
  await page.waitForTimeout(200);
  emptyNoteBlocked = await page.locator('.dialog .err').isVisible().catch(() => false);
}
check('结单意见为空时提交被拦（意见是结果回流的正文）', emptyNoteBlocked);

let dialogClosed = false;
if (dialogShown) {
  await page.locator('.dialog button:has-text("取消")').click().catch(() => {});
  await page.waitForTimeout(200);
  dialogClosed = !(await page.locator('.dialog').isVisible().catch(() => false));
}
check('取消后对话框关闭，不留下半截操作', dialogClosed);

// 防连点：一次只允许一个动作在飞。
// 领取：真点一次，并看它是否进了「已领取」那一档（领取人的名字出现在详情里）。
// round44 票 188：动作按钮在**详情栏**，不在每一行上——所以先记下当前选中的是哪张单，
// 再去认领取人。早先直接读「第一张卡片」会判错：刷新会保留上一次的选中行，
// 而门禁刚造了一张更新的单，那张新单排在第一行却不是被领取的那张（本轮实测）。
const claimTargetId = (await page.locator('.detail-head h2').innerText().catch(() => '')).trim();
const claimButtons = page.locator('button:has-text("领取")');
check('队列里有可领取的按钮', (await claimButtons.count()) > 0);
const firstClaim = claimButtons.first();
await firstClaim.click({ noWaitAfter: true }).catch(() => {});
// **轮询**而不是固定等 600 ms：领取要穿过网关再到工单服务（两跳），
// 固定等待在慢一点的机器上就是间歇红——本仓吃过「间歇红被当成偶发」的亏。
await page
  .waitForFunction(
    (agent) => (document.querySelector('.detail-pane')?.innerText ?? '').includes(`坐席 ${agent} 已领取`),
    USERNAME,
    { timeout: 15000 },
  )
  .catch(() => {});
const detailText = await page.locator('.detail-pane').innerText().catch(() => '');
check(
  `领取后 ${claimTargetId || '(未知单)'} 的详情出现领取人（动作真的打到了工单服务）`,
  // 票 83 起领取人取自登录令牌里的账号，不是页面上自报的名字，所以断言的是登录用的那个账号名
  detailText.includes(`坐席 ${USERNAME} 已领取`),
  detailText.replace(/\s+/g, ' ').slice(0, 80),
);

// --- 知识飞轮（round44 票 190）：五个状态在同一视图里走通 -----------------------------
// 门禁自己先造一条 PENDING 反馈，不去依赖别处留下的行——清过库之后那种断言恒真。
const kbConversation = `ws-kb-${Date.now()}`;
const feedbackResponse = await fetch(`${BASE}/api/v1/support/chat/feedback`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${buyerToken}` },
  body: JSON.stringify({
    conversationId: kbConversation,
    verdict: 'DOWN',
    reason: '工作台门禁：答案没写清 7 天的起算点',
  }),
});
const feedbackAck = await feedbackResponse.json().catch(() => ({}));
check(
  '点踩能落一条待复核反馈',
  feedbackResponse.ok && Boolean(feedbackAck.feedbackId) && feedbackAck.reviewQueued === true,
  `HTTP ${feedbackResponse.status} feedbackId=${feedbackAck.feedbackId ?? '(无)'} reviewQueued=${feedbackAck.reviewQueued}`,
);
const feedbackId = feedbackAck.feedbackId;

await page.locator('.side-nav button:has-text("知识飞轮")').click();
await page.waitForSelector('.kb', { timeout: 8000 }).catch(() => {});
const flowSteps = await page.locator('.kb-flow .flow-step').count();
check(
  '知识视图画出点踩 → 复核 → 附稿 → 发布 → 进入语料五步',
  (await page.locator('.kb').isVisible().catch(() => false)) && flowSteps === 5,
  `${flowSteps} 步`,
);

const reviewRow = page.locator('.review-row', { hasText: feedbackId ?? '(none)' });
await reviewRow.waitFor({ timeout: 8000 }).catch(() => {});
check('刚点踩的反馈出现在复核队列里', (await reviewRow.count()) > 0, feedbackId ?? '(没有 feedbackId)');

await reviewRow.locator('button:has-text("标为已复核")').click({ noWaitAfter: true }).catch(() => {});
await page.waitForSelector('.draft-form', { timeout: 10000 }).catch(() => {});
check(
  '复核完成后附稿表单直接展开到这条反馈上（复核的下一步就是附稿）',
  await page.locator('.draft-form').isVisible().catch(() => false),
  (await page.locator('.draft-form').innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 60),
);

await page.locator('.draft-form button[type="submit"]').click({ noWaitAfter: true }).catch(() => {});
await page.waitForTimeout(200);
check(
  '正文为空时提交被拦在提交前（这一稿会原样进语料）',
  await page.locator('.draft-form .err').isVisible().catch(() => false),
  await page.locator('.draft-form .err').innerText().catch(() => '(没有拦)'),
);

await page.fill('.draft-form input[type="text"]', '七天无理由退货的起算点（门禁写入）');
await page.fill('.draft-form textarea', '签收次日 0 点起算，满 7 天当日 24 点截止；生鲜类不适用。');
await page.locator('.draft-form button[type="submit"]').click({ noWaitAfter: true }).catch(() => {});
await page.waitForSelector('.revision-card.draft', { timeout: 10000 }).catch(() => {});
check(
  '附稿后出现一版待发布的修订稿（DRAFT）',
  (await page.locator('.revision-card.draft').count()) > 0,
  `${await page.locator('.revision-card.draft').count()} 版待发布`,
);

await page.locator('.revision-card.draft button:has-text("发布")').first().click({ noWaitAfter: true }).catch(() => {});
await page.waitForSelector('.ingest-note', { timeout: 10000 }).catch(() => {});
check(
  '发布后给出「不立即生效、需重跑 ingest」的显式提示',
  await page.locator('.ingest-note').first().isVisible().catch(() => false),
  (await page.locator('.ingest-note').first().innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 60),
);

// 驳回是这条链的**另一个终态**，票 190 验收项写的是「读、附稿、发布、驳回」四件事。
// 只测发布等于只测了一半：发布与驳回共用 DRAFT 前置，但出口、卡片样式与后续提示都不同。
// 这里再造一条反馈走完整链，最后驳回，断言它变成 rejected 且不再留在待发布里。
const rejectConversation = `ws-kb-reject-${Date.now()}`;
const rejectAck = await (await fetch(`${BASE}/api/v1/support/chat/feedback`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${buyerToken}` },
  body: JSON.stringify({
    conversationId: rejectConversation,
    verdict: 'DOWN',
    reason: '工作台门禁：驳回路径',
  }),
})).json().catch(() => ({}));
const rejectFeedbackId = rejectAck.feedbackId;
check('驳回路径的反馈也落到了复核队列', Boolean(rejectFeedbackId), rejectFeedbackId ?? '(无 feedbackId)');

await page.locator('.kb-head button:has-text("刷新")').click({ noWaitAfter: true }).catch(() => {});
const rejectRow = page.locator('.review-row', { hasText: rejectFeedbackId ?? '(none)' });
await rejectRow.waitFor({ timeout: 8000 }).catch(() => {});
await rejectRow.locator('button:has-text("标为已复核")').click({ noWaitAfter: true }).catch(() => {});
await page.waitForSelector('.draft-form', { timeout: 10000 }).catch(() => {});
await page.fill('.draft-form input[type="text"]', '驳回路径的修订稿（门禁写入）');
await page.fill('.draft-form textarea', '这一稿会被驳回，用来验证终态。');
await page.locator('.draft-form button[type="submit"]').click({ noWaitAfter: true }).catch(() => {});
await page.waitForSelector('.revision-card.draft', { timeout: 10000 }).catch(() => {});
await page.locator('.revision-card.draft button:has-text("驳回")').first().click({ noWaitAfter: true }).catch(() => {});
await page.waitForSelector('.revision-card.rejected', { timeout: 10000 }).catch(() => {});
const rejectedCount = await page.locator('.revision-card.rejected').count();
const draftLeft = await page.locator('.revision-card.draft').count();
check(
  '驳回后修订卡进入终态（REJECTED），不再留在待发布',
  rejectedCount > 0 && draftLeft === 0,
  `${rejectedCount} 版已驳回 / ${draftLeft} 版待发布`,
);

// 窄屏（round36 / 票 151）：宽表格进容器内滚动，页面本身不横向溢出。
await page.setViewportSize({ width: 390, height: 780 });
await page.waitForTimeout(200);
const overflow = await page.evaluate(() => ({
  doc: document.documentElement.scrollWidth,
  view: window.innerWidth,
}));
check('窄屏 390px 不横向溢出', overflow.doc <= overflow.view + 1, JSON.stringify(overflow));
await page.setViewportSize({ width: 1440, height: 900 });

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