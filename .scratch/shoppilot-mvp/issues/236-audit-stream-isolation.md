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
- ticket 测试 classpath 使用独立 Redis 逻辑库
  （`spring.data.redis.database=1`），避免 `TicketWorkflowTest` 等
  `@SpringBootTest` 把 `TICKET_*` 事件写进活体 DB 0；`AuditPublisher` 本体
  与生产流名不动。
- 新增 `AuditStreamIsolationTest` 3 条：默认构造生产值、自定义流/组用于
  publish/createGroup/read、自定义流/组用于 acknowledge。
- `RedisConfigConsistencyTest` 增加 1 条静态守卫，钉住 ticket 测试的 DB 1。
- 新增 `scripts/verify-audit-isolation.ps1`：活体 `/api/audit` 前后快照 +
  `RefundReviewTest` + eventId/总数/测试行三重断言 + surefire 10 条 +
  生产/测试流 `XLEN` 一平一增 + 基线零测试形态行。

**验收证据（HEAD `b0bb2b1` + 票内 dirty 工作树）**

- **AC1 / AC2 / AC4**：`AuditStreamIsolationTest` 3/3 +
  `RedisAuditChannelWireTest` 4/4 PASS；默认构造仍写生产流/组。
- **AC3**：`RefundReviewTest` 10/10 PASS，biz-mock 测试使用独立流/组；
  ticket 测试 51/51 PASS，审计事件落 DB 1，DB 0 生产流无新增。
- **AC5 活体（本机 Redis 16379 + biz-mock 8091）**：
  `pwsh -NoProfile -File scripts/verify-audit-isolation.ps1` 退出 0；
  `before rows=0 uniqueEventIds=0` → 跑 10 条退款审核测试 →
  `after rows=0 newEventIds=0 testShapedRows=0`；surefire 报告
  `tests=10 failures=0 errors=0 skipped=0 fresh=True`；生产流 `XLEN`
  11→11，测试流 118→124。脚本在测试未执行或基线已有测试行时会失败。
- **AC6**：`shoppilot-biz-mock -am test` 111 tests BUILD SUCCESS；
  全仓 `.\mvnw.cmd -B -ntp verify` BUILD SUCCESS，
  **900 tests / 147 suites，0 failures / 0 errors / 0 skipped**。
- **AC7**：独立 Verifier 首轮在 `39049b3` 判 FAIL（三项 P1：ticket 测试
  污染生产流、AC5 脚本可假通过、票外文件）。修订 1 已修复并回退票外改动，
  待 Verifier 复验；本票不自行宣称签收。

**修订 1（2026-11-28，Verifier 发现后）**

- 全仓 verify 曾让 ticket 测试写 `TICKET_*` 事件到生产流；根因是 ticket
  `@SpringBootTest` 共用 DB 0。修法是测试 classpath 切 DB 1，而不是改
  `AuditPublisher` 或生产流名。
- AC5 脚本原来只看退出码和新增行；现在解析 surefire 报告、做 `XLEN`
  正向控制，并要求基线本身零测试形态行。
- round63 spec 的票外状态改动已回退；该文件不在 Allowlist 内。

**三个现场追问**

1. 活体验收基线为 0 条：空表下 AC5 是否仍有区分力？——有。测试若写生产流，
   查询侧会 drain 出至少 10 条 `REFUND_*` 行；但空表不能证明历史非空表的
   eventId 去重边界，故同时保留 eventId 与总数双断言。
2. 测试流不清理：真实共享 Redis 上长期跑测试会在 `shoppilot:audit-test`
   累积 entry。本票只保证不污染生产流；是否给测试流加 `MAXLEN`/TTL，
   留待运维策略票决定。
3. 生产流名可被环境变量覆盖：若现场误设 `SHOPPILOT_AUDIT_STREAM`，
   活体会读写错误流。本票未加启动期防呆；登记为配置治理输入，不扩本票范围。
