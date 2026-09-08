# 身份来自自签 JWT 验签结果，下游只认 TenantContext

Context: ADR 0005 第一道防线要求 `tenantId` 与 `customerId` 只能来自鉴权结果。若实现为 `X-Shop-Id` / `X-Customer-Id` 裸 Header，任何人改一个 Header 即可读取任意店铺订单，防线与越权测试同时失效；完整 OAuth2 授权码流程在 5.5 天预算内不可行。决定：网关暴露 `POST /auth/mock-token {tenantId, customerId}` 签发 HS256 JWT（claims `tid` / `cid` / `exp=30min`，密钥走 `SHOPPILOT_JWT_SECRET`），filter 验签后写入 `TenantContext`；下游取身份只能取 `TenantContext`，请求体与查询参数中的 `tenantId` 一律忽略并告警。

Considered Options: 裸 Header（0.1d，防线为假）；完整 OAuth2（1.5d+，不可行）。

Consequences:
- 身份提供方是 mock 的，但验签是真的，因此"改 Header 即越权"这类追问有可运行的反证。
- `TenantContext` 用 `ThreadLocal` + `finally` 显式清理。`ScopedValue` 在 Java 21 仍属 preview 需 `--enable-preview`，不为简历关键词给构建链加风险，代码留注释标记为未来替换点。
- 越权用例升级为两条：A 店 token + 手写 B 店 orderNo 必须返回"未在本店找到该订单"；伪造未签名 token 必须 401。
- 调试台增加身份切换区，串号防线可现场演示。
- biz-mock 不对外暴露身份语义：网关调用时携带 `X-Internal-Token`（env 配置），biz-mock 校验该 token 且只监听本机，防止绕过网关直打 8091。
