# 132 B3 审计 actorAuthenticated 在 Redis 流路径丢失（回归修复）

**Status:** implemented

## What to build

修 ADR 0063 承诺在落账层的破口：审计事件的 `actorAuthenticated` 在 **Redis 流路径**被丢，
凡走流的审计事件（生产常态）一律落成「未认证」——资金动作的责任人查询面分不出来。

- `RedisAuditChannel.toMap` 补 `actorAuthenticated` 字段；`fromMap` 改十参构造解析，
  缺字段按未认证（fail-closed，换代期旧消息不能把自报名字读成认证过的）。
- 两个转换函数放开到包级可见（同 `HybridRetriever.rrf()` 先例），新增
  `RedisAuditChannelWireTest`：往返保真（true 保持 true）、反方向不静默升级、旧消息缺字段 fail-closed、
  坏消息返回 null 留在 pending。

## Blocked by

无（独立缺陷修复）。

## 口径

- 主判（staff-only 资金动作、biz-mock 要求 `X-Actor-Authenticated: true`）一字不动，
  修的是**判据已经依赖、但落账没记住**的那一格。
- `AuditEvent` 契约不变（仍是可选标注，`CURRENT_SCHEMA` 不 +1）。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
# 活体（不依赖模型）：reset → 直连 /api/tools/applyRefund → agent 登录 → 网关放行 → /api/audit
git diff --check; git status --short
```

## Handoff notes

- **三条防线为什么都没拦住**：JVM 用例走 `InMemoryAuditChannel`（对象直传，不过序列化边界）；
  round30 加的活体断言指向不存在的 `/ops/audit`（从未执行）；全量矩阵自 09-28 起没跑过。
  这是「测试替身绕过真实边界」的第二个实例（第一个是 round26 的幂等去重表）。
- **活体直证**：修复前 02:41 的活体行 `REFUND_APPROVED obj=9 actor=agent auth=False`；
  修复后 18:57Z `REFUND_APPROVED obj=1 actor=agent auth=True`（直连探针，不经模型）。
- **读数**：四模块 `10 + 73 + 423 + 45 = 551`（biz-mock +4）；覆盖率 biz-mock 81.18%；
  全仓 verify 绿。
- **顺带登记**：JVM 测试的审计事件经共享 Redis 流被活体 biz-mock 惰性消费、落进活体审计表
  （取证的 `agent-001`/`alice` 行来源）→ `registered-debt.md` R10。
- **三个现场追问**：
  1. 流字段是手工 `put` 的——要不要把 `toMap/fromMap` 换成对 `AuditEvent` 的 JSON 序列化
     （Jackson 读写同源，字段漏一格在编译期就不成立）？
  2. 换代期旧消息（缺字段）按未认证处理，正确的方向，但它们会永久留在审计表里——
     要不要给这些行打历史标记以便查询面区分？
  3. 测试与活体栈共用一条审计流（R10）——独立流名与「测试不许连生产 Redis」哪条更符合本仓纪律？
