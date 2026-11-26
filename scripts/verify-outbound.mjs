// 结果回流的活体验收（round26 票 89 / ADR 0059）。
//
// 用法: node scripts/verify-outbound.mjs
//   环境变量：
//     SHOPPILOT_GATEWAY_BASE   默认 http://127.0.0.1:8082
//     SHOPPILOT_OPS_TOKEN      必给：从进程环境或 .env 读（不再内置 dev 默认值）
//     SHOPPILOT_IDENTITY_TENANT / _AGENT / _DEMO_PASSWORD   可选：给了就用真账号登录
//
// 网关侧必须允许本机回环，否则 EgressPolicy 会按 SSRF 防护拒绝回声端点。正式入口
// `run-acceptance.ps1` 会在启动验收栈时合并
// `SHOPPILOT_OUTBOUND_ALLOWLIST=127.0.0.1`；单独跑本脚本时请用同样方式启动网关，
// 不要把这项写进生产默认值。
//
// **本轮（2026-10-04）未实跑**：要起四服务栈（≈6.6 GB）而本机只剩约 1.9 GB（裁定 A）。
// 脚本已写好并通过 `node --check`；实跑读数按**未达成登记**，不许声称「已验证」。
//
// 门禁的承重部分是「**回声端点真的收到了那一次 POST**」。所以这里用 node 自带的 http 起一个
// 本地端点，而不是断言某个计数变大了——把 HTTP mock 掉之后「调用过 send」与「送到了」
// 就分不开了，那正是 round23 抓到的「队列空也判过」同族假绿。
//
// **门禁自己造那张工单**，不依赖前一步的残留（round23 票 73 的同款纪律）：
// 工单服务的库是内存库，任何一次重启都会清空，靠别处留下就等于在别的顺序下变成假绿。
import http from 'node:http';
import { requireEnv } from './lib-dotenv.mjs';

const BASE = process.env.SHOPPILOT_GATEWAY_BASE || 'http://127.0.0.1:8082';
const OPS_TOKEN = requireEnv('SHOPPILOT_OPS_TOKEN',
  '缺少 SHOPPILOT_OPS_TOKEN：请在 .env 里给一个（python scripts/bootstrap_env.py）再跑。');
const TENANT = process.env.SHOPPILOT_IDENTITY_TENANT || 'T001';

const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
};

// --- 回声端点 -----------------------------------------------------------------------------

const received = [];
const echo = http.createServer((req, res) => {
  const chunks = [];
  req.on('data', (chunk) => chunks.push(chunk));
  req.on('end', () => {
    received.push({ url: req.url, headers: req.headers, body: Buffer.concat(chunks).toString('utf8') });
    res.writeHead(200).end();
  });
});
await new Promise((resolve) => echo.listen(0, '127.0.0.1', resolve));
const callbackUrl = `http://127.0.0.1:${echo.address().port}/outbound`;
console.log(`回声端点：${callbackUrl}`);

const postJson = async (path, body, headers = {}) => {
  const response = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8', ...headers },
    body: JSON.stringify(body),
  });
  const text = await response.text();
  let parsed;
  try {
    parsed = text ? JSON.parse(text) : {};
  } catch (badJson) {
    parsed = { message: text };
  }
  return { status: response.status, body: parsed };
};

const mockToken = async () => {
  const response = await postJson('/auth/mock-token', { tenantId: TENANT, customerId: 'C001' });
  return response.body.token;
};

// --- 门禁自己造一张带 webhook 目标的工单 ----------------------------------------------------

const buyerToken = await mockToken();
check('买家身份可取到（mock 令牌，本门禁不依赖演示账号口令）', Boolean(buyerToken));

// 「转人工」是唯一一条能确定落单的降级（ADR 0017），且它走的就是 webhook 渠道那条降级路径。
const escalated = await postJson(
  '/api/v1/support/webhook/webhook',
  { query: `我要转人工（出站门禁 ${Date.now()}）`, callbackUrl },
  { Authorization: `Bearer ${buyerToken}` },
);
check(
  'webhook 渠道能走到显式转人工并落单',
  Boolean(escalated.body.ticketId),
  `HTTP ${escalated.status} ticketId=${escalated.body.ticketId || '(无)'}`,
);

const ticketId = escalated.body.ticketId;
if (!ticketId) {
  console.log('\n没有工单号，后面的断言无意义——按未跑处理，不输出 PASS。');
  await new Promise((resolve) => echo.close(resolve));
  process.exit(1);
}

// --- 坐席领取并结单 --------------------------------------------------------------------------

const opsHeaders = { 'X-Ops-Token': OPS_TOKEN, Authorization: `Bearer ${buyerToken}` };
const claim = await postJson(`/api/v1/support/ops/tickets/${ticketId}/claim`, null, {
  ...opsHeaders,
  'X-Agent': 'outbound-gate',
});
check('坐席领取成功', claim.status === 204, `HTTP ${claim.status}`);

const resolve = await postJson(
  `/api/v1/support/ops/tickets/${ticketId}/resolve`,
  { note: '已为您办理完毕，这是出站门禁的结论' },
  { ...opsHeaders, 'X-Agent': 'outbound-gate' },
);
check('结单成功（这是出站事件的生产点）', resolve.status === 204, `HTTP ${resolve.status}`);

// --- 等回声端点真的收到 -----------------------------------------------------------------------

// 消费循环默认 5 s 一跳，所以这里给足轮询而不是固定等一次。
const deadline = Date.now() + 30_000;
while (received.length === 0 && Date.now() < deadline) {
  await new Promise((resolve) => setTimeout(resolve, 500));
}
check(
  '回声端点真的收到了那一次 POST（不是「调用过 send」，是「对端真的收到了」）',
  received.length > 0,
  `收到 ${received.length} 次`,
);

if (received.length > 0) {
  const hit = received[0];
  let payload = {};
  try {
    payload = JSON.parse(hit.body);
  } catch (unparsable) {
    payload = {};
  }
  check('投递内容指回那张工单', payload.ticketId === ticketId, `ticketId=${payload.ticketId}`);
  check(
    '投递内容是坐席写的那句结论',
    typeof payload.body === 'string' && payload.body.includes('出站门禁的结论'),
    payload.body,
  );
  check('重投不会产生第二次投递', received.length === 1, `收到 ${received.length} 次`);
}

// --- 计数那三格 ---------------------------------------------------------------------------

// --- 计数那两格（**分别在两个进程上**，第一版在这里写错过） ---------------------------------
//
// `published_total` 长在**工单服务**（它才是生产端），`delivered_total` 长在**网关**（消费端）。
// 第一版两个都去网关读，于是「发布计数」那条**结构上恒为 0**——清场日 2026-10-04 实跑才发现。
// 「读错进程」的断言不是偶尔失灵，它只是**从来没被真正执行过**。

const TICKET_BASE = process.env.SHOPPILOT_TICKET_BASE || 'http://127.0.0.1:8092';

const counterOf = async (base, name) => {
  const text = await (await fetch(`${base}/actuator/prometheus`)).text();
  return text
    .split('\n')
    .filter((line) => line.startsWith(name) && !line.startsWith('#'))
    .reduce((sum, line) => sum + Number(line.split(' ')[1] || 0), 0);
};

const published = await counterOf(TICKET_BASE, 'shoppilot_outbound_published_total');
const delivered = await counterOf(BASE, 'shoppilot_outbound_delivered_total');
check('发布计数可见（工单服务侧 :8092）', published >= 1, `${published}`);
check('送达计数可见（网关侧 :8082）', delivered >= 1, `${delivered}`);

await new Promise((resolve) => echo.close(resolve));

const failed = results.filter((r) => !r.ok);
console.log(`\n结果回流断言 ${results.length - failed.length}/${results.length} 通过`);
if (failed.length && !(process.env.SHOPPILOT_OUTBOUND_ALLOWLIST || '').includes('127.0.0.1')) {
  console.log('提示：若网关日志出现「回环/私网/链路本地」，请用 '
    + 'SHOPPILOT_OUTBOUND_ALLOWLIST=127.0.0.1 启动验收栈（run-acceptance.ps1 已自动处理）。');
}
process.exit(failed.length === 0 ? 0 : 1);
