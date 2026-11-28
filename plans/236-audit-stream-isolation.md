# 票 236 契约：测试审计事件隔离

- Round: 63
- 类型: 证据隔离修复（R10；ADR 0054 / 0088）
- 开票依据: 所有者 2026-11-28「剩下问题按推荐执行」；ADR 0088 第一波
- 证据: `RefundReviewTest` 未替换审计通道，测试事件写真实 `shoppilot:audit`；
  活体 biz-mock drain 后把测试行落进 `audit_event`
- Implementer: 独立分支 `codex/236-audit-stream-isolation`
- Verifier: 独立 subagent；命令由 Verifier 亲跑
- Blocked by: 235

## 问题

`RedisAuditChannel` 把流名与消费组写死为 `AuditTopics.AUDIT` /
`AuditTopics.AUDIT_GROUP`。biz-mock 的部分 `@SpringBootTest` 没有替换
`AuditChannel`，于是测试发布的事件进入活体共享流；运行中的 biz-mock 在下一次
`/api/audit` 查询时惰性 drain，把 `agent-001` / `alice` 等测试行落进活体台账。

## 目标

流名与组名可配置，生产默认不变；JVM 测试使用独立流，活体审计面不再被测试污染。

## Allowed paths

- `plans/236-audit-stream-isolation.md`
- `.scratch/shoppilot-mvp/issues/236-audit-stream-isolation.md`
- `.scratch/shoppilot-mvp/README.md`
- `docs/EVIDENCE.md`
- `docs/CODE_MAP.md`
- `docs/STATUS.md`（生成）
- `docs/TUNING_PLAN.md`（仅机械计数）
- `README.md`（仅测试数 / 指标名如变化）
- `shoppilot-biz-mock/src/main/java/com/shoppilot/bizmock/audit/RedisAuditChannel.java`
- `shoppilot-biz-mock/src/main/resources/application.yml`
- `shoppilot-biz-mock/src/test/resources/application.properties`（新增或修改）
- `shoppilot-biz-mock/src/test/java/com/shoppilot/bizmock/audit/RedisAuditChannelWireTest.java`
- `shoppilot-biz-mock/src/test/java/com/shoppilot/bizmock/audit/AuditStreamIsolationTest.java`（新增）
- `scripts/verify-audit-isolation.ps1`

## Acceptance criteria

- **AC1（配置）**：`RedisAuditChannel` 从 `shoppilot.audit.stream` 与
  `shoppilot.audit.group` 读取流名/组名；未配置时默认仍是
  `shoppilot:audit` / `shoppilot-audit`。
- **AC2（生产不变）**：活体默认配置下发布、消费、ack、建组仍使用生产流名；
  审计事件字段、幂等键、直写兜底与查询接口零改动。
- **AC3（测试隔离）**：biz-mock 测试 classpath 的 `application.properties`
  把流名/组名指向独立测试值；跑 `RefundReviewTest` 不再写生产流。
- **AC4（0 token 线格式）**：新增测试钉住自定义流名被用于 `add` / `read` /
  `acknowledge` / `createGroup`；默认构造仍用生产常量。
- **AC5（受控复现）**：`scripts/verify-audit-isolation.ps1` 在活体 biz-mock
  运行时记录 `/api/audit` 快照，跑 `RefundReviewTest` 后再次读取；
  活体表不新增测试事件（按 eventId/总数双断言），输出 PASS/FAIL。
- **AC6（回归）**：窄测 + 全仓 `.\mvnw.cmd -B -ntp verify` BUILD SUCCESS；
  既有审计测试断言零弱化。
- **AC7（流程）**：独立 Verifier 亲跑 AC1–AC6，P0/P1 清零；所有者书面签收。

## Forbidden

- 不改事件字段、幂等键、`AuditService` 直写兜底或 `/api/audit` 契约。
- 不清理历史审计数据，不修改工单服务 `AuditPublisher` 或网关质量报表的流名。
- 不把生产默认流名改成测试值来「避免污染」。
- 不删除、跳过或弱化任何测试；不把未实跑写成 PASS。

## Verify

```powershell
.\mvnw.cmd -B -ntp -pl shoppilot-biz-mock -am test
.\mvnw.cmd -B -ntp verify
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
python scripts/gen_status.py --check
python scripts/verify_doc_truth.py
python scripts/check_doc_links.py
git diff --check
git status --short
```

活体：

```powershell
pwsh -NoProfile -File scripts/verify-audit-isolation.ps1
```
