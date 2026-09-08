# 10 — 工具契约与跨进程调用边界

**What to build:** 四个业务动作以一份 schema 同时生成给模型的 Function 描述与网关的 HTTP 调用代码，工具调用真的跨进程打到 biz-mock。落实 ADR 0002。

**Blocked by:** 01 — 三模块骨架与中间件容器栈；03 — biz-mock 数据底座与租户感知仓储

**Status:** done

**Verify:** 停掉 biz-mock 进程后问一次物流 -> 返回降级语义而非 500；schema 与调用签名由同一份 DTO 生成，改字段后两侧同步变化。

- [x] `shoppilot-tool-api` 定义四个工具：`queryOrderDetail` `queryLogistics` `modifyDeliveryAddress` `applyRefund`，含参数描述与必填项
- [x] Function Schema 由同一份 DTO 生成，不允许手写一份 schema 再手写一份调用签名
- [x] 网关侧 `RestClient` / `@HttpExchange` 调用 biz-mock，携带 `X-Internal-Token` 与来自 `TenantContext` 的身份
- [x] 超时、熔断、降级在网关侧统一配置，不在各工具调用点重复 try-catch
- [x] 工具执行失败不在模型层重试，回填结构化失败事实 `{status, tool}` 给模型组织人话
- [x] 用例：biz-mock 被停掉时链路返回降级语义而非 500

## Handoff notes

**关键决策**

1. **`shoppilot-tool-api` 是纯契约模块，零 Spring 依赖。** 四个工具的请求 DTO（`QueryOrderDetailRequest` 等）是 record，字段上打 `@ToolParam(desc, required)`；`ToolSchemaGenerator` 从 record components 反射生成 OpenAI function descriptor（含 `type/properties/required/description`）。**给模型的 schema 与网关的调用签名同源**，改字段两侧同步变化，不存在"手写两份再祈祷对齐"。
2. **`ToolContracts` 是登记表**：`ToolName -> requestType`，`functionDescriptors()` 全量下发，`descriptorsFor(intent)` 按意图裁剪——政策咨询链路一个工具都不下发（ADR 0007：不为分类单开一次模型请求，tools 是否被调用本身就是意图证据）。
3. **跨进程走真网络边界**（ADR 0002）：网关用 JDK `HttpClient` POST `http://127.0.0.1:8091/api/tools/{name}`，带 `X-Internal-Token` 与来自 `TenantContext` 的身份；biz-mock 只监听 127.0.0.1。双进程不是形式主义——它让"网关能不能在业务系统挂掉时优雅降级"变成一个可以用 `Stop-Process` 验证的事实。
4. **超时、熔断统一在 `BizMockClient` 一处**：连接 2s / 读 3s，Resilience4j `CircuitBreaker("bizmock")`，`waitDurationInOpenState=10s`。各工具调用点不写 try-catch，避免四个工具四套降级话术。
5. **工具执行失败不在模型层重试**，而是把结构化失败事实 `{status, tool}` 回填给模型组织人话。重试属于幂等与业务语义范畴，交给 ticket 12；在模型层重试会让"退款到底发起了几次"变成不可知。
6. **`ToolStatus` 是显式枚举**（OK / NOT_FOUND / STATE_NOT_ALLOWED / IDEMPOTENT_REPLAY / UNAVAILABLE / INVALID_ARGS / FORBIDDEN），网关按状态决定后续路径，而不是解析自然语言错误消息。

**你需要能当场回答的三个追问**

- *Q：为什么不直接用 Spring 的 `@HttpExchange` 声明式客户端？* A：可以用，但那样 schema 生成与调用签名仍是两份东西。真正的约束是"同一份 DTO 派生两侧"，用哪种 HTTP 客户端是次要的；选 JDK `HttpClient` 还省掉一层依赖并让超时与熔断的接线看得见。
- *Q：内部 token 泄漏到浏览器怎么办？* A：泄漏不到。浏览器只与同源网关通信，`X-Internal-Token` 由网关在服务端补上；调试台的运维动作经网关代理端点转发，代理额外要求 `X-Ops-Token`（ticket 15 的 Playwright 验收里有一条专门断言页面拿不到内部 token）。
- *Q：biz-mock 挂了用户看到什么？* A：熔断打开后请求不再打过去，直接 `TOOL_UNAVAILABLE` 降级并落工单，`GET /ops/circuit` 能看到 `CLOSED/OPEN/HALF_OPEN` 与模型名、当日 token 用量。实测停进程后返回降级语义而非 500。

**验证记录**

`scripts/verify-fallback.ps1` 覆盖 `TOOL_UNAVAILABLE`；ticket 14 的七种降级原因脚本包含这一条。`ToolSchemaGeneratorTest` 3 项：参数名来自 record components、`required` 列表与 `@ToolParam(required=true)` 一致（`applyRefund` 的 `amountFen` 为可选）、descriptor 携带 name/description/type。`scripts/verify-fallback.ps1` 覆盖 `TOOL_UNAVAILABLE`。
