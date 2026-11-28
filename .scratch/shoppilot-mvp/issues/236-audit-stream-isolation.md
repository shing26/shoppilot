# 票 236：测试审计事件隔离（R10）

Round: 63（内部试用就绪第一波；ADR 0091）
ADR: 0054（事件与审计）、0091（程序范围）
Status: implemented（待独立 Verifier）　Claimed by: Codex　Blocked by: 无（235 已于 2026-11-28 收口）
契约：[plans/236-audit-stream-isolation.md](../../plans/236-audit-stream-isolation.md)（2026-11-28 冻结）

## What to build

把 biz-mock 的审计流名与消费组变成配置项，并让 JVM 测试写独立流：

1. 生产默认仍是 `shoppilot:audit` / `shoppilot-audit`；
2. 测试配置使用独立流名与组名；
3. 新增 0-token 线格式/流名测试；
4. 受控复现：跑 `RefundReviewTest` 前后，活体 `/api/audit` 不新增测试行。

## 边界

- 不改审计事件字段、幂等键、直写兜底或查询接口。
- 不改工单服务的 `AuditPublisher` 与网关质量报表读取的生产流名。
- 不清历史审计数据、不删除既有测试断言。

## Verify

见契约 AC1–AC7。

## Handoff notes

**开票时事实（2026-11-28）**

- `RedisAuditChannel` 硬编码 `AuditTopics.AUDIT` / `AUDIT_GROUP`；
  `RefundReviewTest` 未替换审计通道，会向真实 Redis 流写测试事件；
  活体 biz-mock 惰性 drain 后把 `agent-001` / `alice` 落进 `audit_event`。
- 既有 `EventReconciliationTest` 用内存通道，覆盖不到这条泄漏。

**实现（2026-11-28，分支 `codex/236-audit-stream-isolation`）**

- `RedisAuditChannel` 新增四参构造，从 `shoppilot.audit.stream` /
  `shoppilot.audit.group` 读取流名与组名；空白值回落生产常量。两参构造保留，
  供测试与手工装配使用生产默认值。
- `publish` / `read` / `acknowledge` / `createGroup` 全部改用实例字段；
  事件字段、幂等键、`AuditService` 直写兜底与 `/api/audit` 契约零改动。
- `shoppilot-biz-mock/src/main/resources/application.yml` 增加
  `SHOPPILOT_AUDIT_STREAM` / `SHOPPILOT_AUDIT_GROUP` 映射，生产默认不变。
- 测试 classpath 的 `application.properties` 指向
  `shoppilot:audit-test` / `shoppilot-audit-test`。
- 新增 `AuditStreamIsolationTest` 3 条：默认构造生产值、自定义流/组用于
  publish/createGroup/read、自定义流/组用于 acknowledge。
- 新增 `scripts/verify-audit-isolation.ps1`：活体 `/api/audit` 前后快照 +
  `RefundReviewTest` + eventId/总数/测试行三重断言。

**验收证据（HEAD `b0bb2b1` + 票内 dirty 工作树）**

- **AC1 / AC2 / AC4**：`AuditStreamIsolationTest` 3/3 +
  `RedisAuditChannelWireTest` 4/4 PASS；默认构造仍写生产流/组。
- **AC3**：`RefundReviewTest` 10/10 PASS，测试 classpath 使用独立流/组。
- **AC5 活体（本机 Redis 16379 + biz-mock 8091）**：
  `pwsh -NoProfile -File scripts/verify-audit-isolation.ps1` 退出 0；
  `before rows=0 uniqueEventIds=0` → 跑 10 条退款审核测试 →
  `after rows=0 newEventIds=0 testShapedRows=0`。若测试仍写生产流，
  第一次 `/api/audit` 会惰性 drain 出测试行，after 必然非空。
- **AC6**：`shoppilot-biz-mock -am test` 111 tests BUILD SUCCESS；
  全仓 `.\mvnw.cmd -B -ntp verify` BUILD SUCCESS，
  **899 tests / 147 suites，0 failures / 0 errors / 0 skipped**。
- **AC7**：独立 Verifier 尚未启动；本票不自行宣称签收。

**三个现场追问**

1. 活体验收基线为 0 条：空表下 AC5 是否仍有区分力？——有。测试若写生产流，
   查询侧会 drain 出至少 10 条 `REFUND_*` 行；但空表不能证明历史非空表的
   eventId 去重边界，故同时保留 eventId 与总数双断言。
2. 测试流不清理：真实共享 Redis 上长期跑测试会在 `shoppilot:audit-test`
   累积 entry。本票只保证不污染生产流；是否给测试流加 `MAXLEN`/TTL，
   留待运维策略票决定。
3. 生产流名可被环境变量覆盖：若现场误设 `SHOPPILOT_AUDIT_STREAM`，
   活体会读写错误流。本票未加启动期防呆；登记为配置治理输入，不扩本票范围。
