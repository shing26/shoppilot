// round43 票 187 活体验收：知识反向沉淀全链路（ADR 0078）。
// 链路：问答点踩 → 复核队列 markReviewed → 附修订稿（DRAFT）→ 显式发布（PUBLISHED）
//   → biz-mock /api/admin/knowledge/revisions 可见（ingest 的第二语料源）→ 修订计数器 +1。
// ingest 的纪元推进是离线作业，不入本脚本：跑完本脚本后另起
//   ./mvnw.cmd -q spring-boot:run -pl shoppilot-gateway -Dspring-boot.run.profiles=ingest
//   并观察日志「文档 N 篇，知识修订 M 条」与 /api/v1/support/ops/epoch 的新纪元。
// 前置：网关 dev/local（:8082）+ biz-mock（:8091）+ 容器栈。用法：node scripts/verify-knowledge-backflow.mjs
const BASE = process.env.SHOPPILOT_GATEWAY_BASE || 'http://127.0.0.1:8082';
const BIZMOCK = process.env.SHOPPILOT_BIZMOCK_BASE || 'http://127.0.0.1:8091';
// 内部令牌按 verify-console.mjs 的家法从环境读：仓库里不放默认口令（ADR 0056）。
const INTERNAL_TOKEN = process.env.SHOPPILOT_INTERNAL_TOKEN;
if (!INTERNAL_TOKEN) {
  console.error('缺少 SHOPPILOT_INTERNAL_TOKEN：脚本要直连 biz-mock 的 /api/admin 只读口读已发布修订。');
  process.exit(2);
}
const OPS_TOKEN = process.env.SHOPPILOT_OPS_TOKEN || 'dev-ops-token';

const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`);
};

async function call(url, options = {}) {
  const response = await fetch(url, options);
  let body;
  const text = await response.text();
  try { body = JSON.parse(text); } catch { body = text; }
  return { status: response.status, body };
}

const tenant = 'T001';
const customer = 'C190';
const conversation = `verify-kb-backflow-${process.pid}`;

const tokenRes = await call(`${BASE}/auth/mock-token`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ tenantId: tenant, customerId: customer }),
});
const userToken = tokenRes.body?.token;
check('0 mock-token 可取', Boolean(userToken), JSON.stringify(tokenRes.body).slice(0, 80));
if (!userToken) process.exit(1);

const bearer = { Authorization: `Bearer ${userToken}` };
const json = (extra = {}) => ({ ...bearer, 'Content-Type': 'application/json; charset=utf-8', ...extra });

// 1) 正常问答，拿到引用块 —— 修订要挂的就是这条链上的人工复核。
const chat = await call(`${BASE}/api/v1/support/chat`, {
  method: 'POST',
  headers: json(),
  body: JSON.stringify({ query: '七天无理由退货需要自己出运费吗', idempotencyToken: conversation }),
});
check('1 问答返回引用块', Array.isArray(chat.body?.citations) && chat.body.citations.length > 0,
  JSON.stringify(chat.body?.citations ?? chat.body).slice(0, 80));

// 2) 点踩 → 复核队列落行。
const fb = await call(`${BASE}/api/v1/support/chat/feedback`, {
  method: 'POST',
  headers: json(),
  body: JSON.stringify({ conversationId: conversation, verdict: 'DOWN', reason: 'round43 验收脚本点踩' }),
});
const feedbackId = fb.body?.feedbackId;
check('2 点踩落行且进复核队列', Boolean(feedbackId) && fb.body?.reviewQueued === true, `feedbackId=${feedbackId}`);

// 3) markReviewed（票 186 新增的网关代理）：PENDING → REVIEWED，这是附稿的前置门。
const review = await call(`${BASE}/api/v1/support/ops/feedback/${feedbackId}/review`, {
  method: 'PATCH',
  headers: json({ 'X-Ops-Token': OPS_TOKEN }),
  body: JSON.stringify({ decision: 'REVIEWED' }),
});
check('3 复核完成（REVIEWED）', review.status === 200, `status=${review.status}`);

// 4) 未复核不可附稿的反例早已由 JVM 用例钉死；这里走正例：附稿 → DRAFT。
const revision = await call(`${BASE}/api/v1/support/ops/feedback/${feedbackId}/revision`, {
  method: 'POST',
  headers: json({ 'X-Ops-Token': OPS_TOKEN }),
  body: JSON.stringify({
    title: '七天无理由退货运费口径（round43 验收）',
    body: '## 运费承担\n自 2026-11 起，七天无理由退货的退货运费由买家承担；质量问题退货运费由商家承担。'
      + '这一段刻意写长，保证入库时能独立成块，不与相邻短段落合并。',
    intent: 'POLICY_RETURN',
    domain: 'ECOMMERCE',
    scope: 'SHOP',
  }),
});
const revisionId = revision.body?.id;
check('4 附稿成 DRAFT', revision.status === 200 && revision.body?.status === 'DRAFT',
  `id=${revisionId} status=${revision.status}`);

// 4b) 校验口在网关前：缺 intent 的稿子 400，不打下游。
const bad = await call(`${BASE}/api/v1/support/ops/feedback/${feedbackId}/revision`, {
  method: 'POST',
  headers: json({ 'X-Ops-Token': OPS_TOKEN }),
  body: JSON.stringify({ title: 'x', body: 'y' }),
});
check('4b 残稿被网关前校验拦下', bad.status === 400, `status=${bad.status}`);

// 5) 显式发布：DRAFT → PUBLISHED；重复发布 409（单向、终态语义的一部分）。
const publish = await call(`${BASE}/api/v1/support/ops/knowledge/revisions/${revisionId}/publish`, {
  method: 'POST', headers: json({ 'X-Ops-Token': OPS_TOKEN }),
});
check('5 发布成 PUBLISHED', publish.status === 200 && publish.body?.status === 'PUBLISHED',
  `status=${publish.status}`);
const republish = await call(`${BASE}/api/v1/support/ops/knowledge/revisions/${revisionId}/publish`, {
  method: 'POST', headers: json({ 'X-Ops-Token': OPS_TOKEN }),
});
check('5b 重复发布 409', republish.status === 409, `status=${republish.status}`);

// 6) ingest 的第二语料源可见：biz-mock 的 admin 口（带内部令牌、跨租户）列出这条 PUBLISHED。
const admin = await call(`${BIZMOCK}/api/admin/knowledge/revisions`, {
  headers: { 'X-Internal-Token': INTERNAL_TOKEN },
});
const rows = Array.isArray(admin.body) ? admin.body : [];
const mine = rows.find((row) => row.id === revisionId);
check('6 已发布修订进 ingest 拉取面', Boolean(mine) && mine.tenantId === tenant,
  mine ? `tenantId=${mine.tenantId}` : `共 ${rows.length} 行`);

// 7) 修订计数器：filed / published 各 +1（闸口在网关，读 Prometheus 文本）。
const metrics = await (await fetch(`${BASE}/actuator/prometheus`)).text();
const filed = /shoppilot_knowledge_revisions_total\{[^}]*result="filed"[^}]*\}\s+([0-9.]+)/.exec(metrics);
const published = /shoppilot_knowledge_revisions_total\{[^}]*result="published"[^}]*\}\s+([0-9.]+)/.exec(metrics);
check('7 修订计数器已埋且动过', Boolean(filed && published)
  && Number(filed[1]) >= 1 && Number(published[1]) >= 1,
  `filed=${filed?.[1]} published=${published?.[1]}`);

const failed = results.filter((r) => !r.ok).length;
console.log(`\n${failed === 0 ? 'ALL PASS' : 'FAILED'}：${results.length - failed}/${results.length} 通过`);
process.exit(failed === 0 ? 0 : 1);
