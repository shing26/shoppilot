# 82 角色守卫与自报身份退役

**Status:** implemented（2026-10-04）

## What to build

本轮兑现的那一格：让「谁做的」有账号可查（ADR 0058 第 3、4 条，spec §4 第一条）。

- **角色守卫**：工单动作（claim / release / resolve）与退款审核要求 `AGENT` 或 `ADMIN`，
  买家令牌打过去 → 403。守卫代码写一处，不在每个端点各写一遍。
- **自报身份退役**：
  - 网关向下游发 `X-Actor`，值取自**已验签的令牌主体**；
  - 认证身份在场时，请求头里的 `X-Agent` / `X-Reviewer` **一律忽略**（不是「优先用它」）；
  - 只有运维面凭证在场（无角色身份）时，才回落到自报值，并**在审计事件里显式标注该 actor 未经认证**。
- **审计契约**：`AuditEvent.actor` 保持原样，**加一个标注位**（`actorAuthenticated` 之类），
  用重载构造器保证既有调用点不必改。

## Blocked by

[81](81-gateway-login-endpoints-and-role-claim.md)。

## 口径

- **`X-Ops-Token` 路径一字不动**（ADR 0058 第 5 条、spec §4 第二条）：带 ops 凭证的既有调用仍然按原样工作。
  这条要有用例钉住，不是「应该还在」。
- **`AuditEvent` 的既有字段与既有动作名不改**：新字段带默认值，`CURRENT_SCHEMA` 不因加字段而 +1
  （字段是可选标注，不是新语义；真要改语义才换代）。
- **不写第二本账**：审计事件里不放角色以外的任何新负载（ADR 0056「审计记谁在什么时候对哪个对象做了什么」）。
- 一条判据都没动。

## 验收

- JVM 用例覆盖：
  - 买家令牌打 claim/release/resolve → 403；
  - 坐席令牌 → 通过，且下游收到的 `X-Actor` 等于令牌主体；
  - **请求头伪造 `X-Agent` 但令牌是别人的 → 下游拿到的是令牌主体，不是头里的值**；
  - 只有 ops 凭证 → 仍然通过，但审计事件的 actor 标注为未经认证；
  - 带 ops 凭证的既有退款审核与工单动作调用**行为不变**（spec §4 第二条的机器落点）；
- **变异对照**：把「忽略自报头」改成「优先用自报头」→ 伪造那条用例必须红。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
```

## Handoff notes

### 落点

- **契约**：`tool.audit.Actor`（名字 + 可不可信）、`tool.audit.ActorHeaders`（两个头与解析）、
  `AuditEvent` / `AuditView` 各加一个可选标注并**保留旧构造**（默认落到「未认证」，方向往严）。
- **biz-mock**：Flyway `V8` 加 `audit_event.actor_authenticated`（默认 false）；
  `AuditEventRow` / `AuditEventWriter` / `AuditService.publish` / `AuditController` 逐层带上；
  `RefundReviewController` 与 `FeedbackController` 只读 `X-Actor` + `X-Actor-Authenticated`。
- **ticket 服务**：`TicketController`（claim/release/resolve）、`RoutingRuleController` 同样只读新头；
  `AuditPublisher` 往流里多写一个 `actorAuthenticated` 字段；`WorkItemService` 与 `RoutingService`
  的签名从 `String` 改成 `Actor`（**刻意不留 String 重载**：两条写法会让「这个名字可不可信」再次变成调用点的事）。
- **网关**：`OpsController` 的 `guardedTicket(...)` 与退款审核共用一条守卫；
  `X-Actor` / `X-Actor-Authenticated` 由 `forward` / `forwardToTicket` 统一补。

### 守卫的语义（这一格最需要说清楚）

**两条准入线，取或**：坐席/管理员账号 **或** 运维凭证。

- 不是「放水」：ops token 本来就是平台级凭证（故障注入、清缓存、演示复位都在它后面），
  拿到它的人就是这套栈的运维者，让他以兜底坐席身份干活是合理的。
- 真正的变化是**另一条**：持有坐席账号的人**不必再持平台凭证**就能处理工单与审核退款——
  在那之前只有 ops token 能碰这些端点。买家账号仍然不行。
- 两条都不满足时：运维面被显式关掉仍然报 `ops.disabled`（那是运维自己做的选择，不该被说成角色不对），
  否则报 `role.denied`。用例同时断言「403 且请求没出网关」——只断言状态码的话，把守卫挪到转发之后也能绿。

### 操作人的规则

**有账号 id 就用它并标成已认证；没有账号 id（mock 令牌与老令牌）才回落到请求头里的自称，并标成未认证。**
自称**永远盖不过**令牌——否则任何登录用户都能替别人署名。
变异对照就是把这条反过来（优先用自称），`forgedSelfReportedAgentIsIgnored` 当场变红。

### 读数

| 项 | 值 |
|---|---|
| JVM 全仓 | `5 + 10 + 57 + 321 + 22` 中 tool-api **5 → 10**、biz-mock 55 → **57**、gateway 315 → **321**；合计 **410** |
| 覆盖率 | 四模块全过；tool-api **37.63% → 46.81%**（门槛 40.00，**中间一度跌破过**，见下） |
| 变异对照 | 自称优先于令牌 → 1 条红；还原即绿 |
| 收口审计 | `PASS 85 / FAIL 2 / SKIP 9`（`A2` 工作树未提交、`F1c` 那笔老账） |

**一条判据都没动**：`verify-console.mjs` / `verify-refund-approval.ps1` 走的仍是 ops token 那条线，
行为与断言逐字不变——用例 `opsTokenPathStillWorksButIsMarkedUnauthenticated` 就是钉这一格的。

### 实现坑（四条）

1. **HTTP 头按 ISO-8859-1 编码**：中文 actor 名在**发出去那一步**就花掉，
   断言读到乱码而机制本身没问题——那种红会去追一个不存在的问题。
   新用例的 actor 名一律用 ASCII，并把这个理由写在用例里。
2. **覆盖率棘轮是真会拦人的**：加了 `Actor` / `ActorHeaders` / 三个请求响应记录之后，
   tool-api 从 40.0% 掉到 **37.63%**，CI 的棘轮直接红。处置是**补该补的用例**
   （`ActorAndActorHeadersTest` 五条，量的是「缺失即未认证」这条安全默认取向），不是调门槛。
3. **`ActorHeaders.of(null, null)` 的两种合理读法**：我第一版把空 actor 也按未认证处理，
   用例立刻红——因为 `Actor.of` 的文档说 system 是可信的。改成**只对有名字的 actor 应用那个默认**：
   两个头都没带时根本没有操作人可言，标成「不可信的操作人」只会让真自报的那些更难被挑出来。
4. **既有测试要跟着改，不是跟着绕过**：`AuditEventFlowTest` / `TicketWorkflowTest` /
   `WorkItemSourceTest` / `RestErrorEnvelopeTest` 原本发的是 `X-Agent` / `X-Reviewer`。
   改的是它们发的新头，**断言一字未动**——其中 `RestErrorEnvelopeTest` 那条还顺带补上了运维凭证
   （它原先是一个「买家令牌 + 无 ops token 也过」的调用，那在票 82 之后本就该红）。

### 现场三问

1. **为什么 ops token 不退役？** 它守的是运维面（改状态的动作），不是「谁」。那批验收脚本与调试台全靠它，
   动它们的认证方式就是改判据。两条线的分工写死在 ADR 0058 第 5 条。
2. **为什么不留 `String` 重载？** 留了就等于把「这个名字可不可信」交还给每个调用点判断一次。
   签名从 `String` 换成 `Actor`，让编译器来问这件事。
3. **审计里的 `actorAuthenticated` 为什么默认 false？** 往严的一边倒：老事件与任何忘了传标注的写入
   都会出现在「可挑出来」的那一侧。往松的一边倒，那些遗漏会变成查不出来的假账。