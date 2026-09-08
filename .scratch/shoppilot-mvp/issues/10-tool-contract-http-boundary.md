# 10 — 工具契约与跨进程调用边界

**What to build:** 四个业务动作以一份 schema 同时生成给模型的 Function 描述与网关的 HTTP 调用代码，工具调用真的跨进程打到 biz-mock。落实 ADR 0002。

**Blocked by:** 01 — 三模块骨架与中间件容器栈；03 — biz-mock 数据底座与租户感知仓储

**Status:** ready-for-agent

**Verify:** 停掉 biz-mock 进程后问一次物流 -> 返回降级语义而非 500；schema 与调用签名由同一份 DTO 生成，改字段后两侧同步变化。

- [ ] `shoppilot-tool-api` 定义四个工具：`queryOrderDetail` `queryLogistics` `modifyDeliveryAddress` `applyRefund`，含参数描述与必填项
- [ ] Function Schema 由同一份 DTO 生成，不允许手写一份 schema 再手写一份调用签名
- [ ] 网关侧 `RestClient` / `@HttpExchange` 调用 biz-mock，携带 `X-Internal-Token` 与来自 `TenantContext` 的身份
- [ ] 超时、熔断、降级在网关侧统一配置，不在各工具调用点重复 try-catch
- [ ] 工具执行失败不在模型层重试，回填结构化失败事实 `{status, tool}` 给模型组织人话
- [ ] 用例：biz-mock 被停掉时链路返回降级语义而非 500

## Handoff notes

（收尾时填写：关键决策 + 你需要能当场回答的三个追问）
