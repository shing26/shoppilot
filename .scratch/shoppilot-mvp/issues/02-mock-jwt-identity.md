# 02 — mock JWT 发签与身份注入

**What to build:** 调用方用店铺与买家身份换一个签名 token，网关验签后把身份放进服务端上下文；不带或伪造 token 一律 401。落实 ADR 0014 与 ADR 0005 第一道防线。

**Blocked by:** 01 — 三模块骨架与中间件容器栈

**Status:** ready-for-agent

**Verify:** 用 A 店 token 与伪造 token 各请求一次 -> 前者 200 且下游取到的身份正确，后者 401。

- [ ] `POST /auth/mock-token {tenantId, customerId}` 返回 HS256 JWT，claims 含 `tid` / `cid` / `exp`（30 分钟），密钥读 `SHOPPILOT_JWT_SECRET`
- [ ] 验签 filter 解出身份写入 `TenantContext`；`ThreadLocal` + `finally` 显式清理，代码注释标记 `ScopedValue` 为未来替换点及不开 preview 的理由
- [ ] 请求体、查询参数、Header 中出现的任何 `tenantId` 一律忽略并打告警日志
- [ ] 下游取身份只能经 `TenantContext`，不存在第二条路径；用一个 ArchUnit 或等价测试守住这条约束
- [ ] 用例：无 token -> 401；未签名伪造 token -> 401；过期 token -> 401

## Handoff notes

（收尾时填写：关键决策 + 你需要能当场回答的三个追问）
