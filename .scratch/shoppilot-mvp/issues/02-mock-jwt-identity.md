# 02 — mock JWT 发签与身份注入

**What to build:** 调用方用店铺与买家身份换一个签名 token，网关验签后把身份放进服务端上下文；不带或伪造 token 一律 401。落实 ADR 0014 与 ADR 0005 第一道防线。

**Blocked by:** 01 — 三模块骨架与中间件容器栈

**Status:** done

**Verify:** 用 A 店 token 与伪造 token 各请求一次 -> 前者 200 且下游取到的身份正确，后者 401。

- [x] `POST /auth/mock-token {tenantId, customerId}` 返回 HS256 JWT，claims 含 `tid` / `cid` / `exp`（30 分钟），密钥读 `SHOPPILOT_JWT_SECRET`
- [x] 验签 filter 解出身份写入 `TenantContext`；`ThreadLocal` + `finally` 显式清理，代码注释标记 `ScopedValue` 为未来替换点及不开 preview 的理由
- [x] 请求体、查询参数、Header 中出现的任何 `tenantId` 一律忽略并打告警日志
- [x] 下游取身份只能经 `TenantContext`，不存在第二条路径；用一个 ArchUnit 或等价测试守住这条约束
- [x] 用例：无 token -> 401；未签名伪造 token -> 401；过期 token -> 401

## Handoff notes

**关键决策**

1. **身份只从签名 token 来，请求体/查询参数/Header 里出现的任何 `tenantId` 一律忽略并打告警日志。** 这是 ADR 0005 的第一道防线：如果客户端能自选租户，后面所有行级隔离都是装饰。
2. **`POST /auth/mock-token {tenantId, customerId}` 是刻意保留的"不安全"入口**，HS256 自签、30 分钟过期、密钥读 `SHOPPILOT_JWT_SECRET`。它存在的意义是让"换一家店的 token"在演示里是一行 curl；生产替换成真实授权服务，网关侧代码不动。
3. **身份经 `TenantContext`（`ThreadLocal`）单点下发，`finally` 显式清理。** 虚拟线程下 `ThreadLocal` 有内存泄漏风险但不会被线程复用污染（虚拟线程用完即弃），所以够用；代码里标注 `ScopedValue` 为未来替换点，不开 `--enable-preview` 是因为预览特性不该进一个要给别人跑的面试项目。
4. **`X-Conversation-Id` 由客户端提供但只作会话键使用**，不参与鉴权；租户与买家身份仍然只来自 token，避免"会话 ID 里藏租户"这种第二身份通道。
5. **用 `IdentityArchitectureTest`（ArchUnit 风格的可控对象测试）守住"下游只能经 `TenantContext` 取身份"**：扫描业务包，禁止除 `identity` 包外任何类引用 JWT 解析或读取原始 Header。这条约束不守住，ticket 03/10/11 的隔离会在后续迭代里被绕过。

**你需要能当场回答的三个追问**

- *Q：伪造一个 claims 正确的 JWT 不行吗？* A：HS256 验签需要密钥；未签名（`alg=none`）与错签名都在 `JwtService` 解析阶段抛错，filter 直接 401。过期 token 同样 401，`AuthFilterTest` 三条用例分别覆盖无 token、伪造、过期。
- *Q：网关重启后 token 会失效吗？* A：不会，密钥在环境变量里，不在进程内存里。这是有意的——重启打断正在打字的用户已经够糟，再把所有人的会话踢掉是雪上加霜。
- *Q：为什么不直接把 tenantId 放进 MDC 或者方法参数往下传？* A：往下传要改一长串签名，改一处漏一处就是越权；MDC 只用于日志，不作为可信来源。`TenantContext` 是唯一可信入口，且有架构测试兜着。

**验证记录（2026-09-05）**

A 店 token 请求 200 且下游取到 `T001/C001`；伪造签名 token 与过期 token 均 401；带 `tenantId` 字段的请求体被忽略并告警。
