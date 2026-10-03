# 81 网关登录端点与角色 claim

**Status:** implemented（2026-10-04）

## What to build

身份域面向用户的那一半（ADR 0058 第 1、2 条）。

- **`JwtService.issue` 加 `role` claim**；`TenantContext.Identity` 加 `role` 字段。
  既有调用方（`/auth/mock-token`）给一个显式角色，**不给就默认 `BUYER`**——调试台那条路的行为一个字不变。
- **`/auth/login`**：用户名 + 口令 → 经内部链路问 biz-mock 的 `/api/identity/authenticate` → 验签通过则签发带角色的令牌。
- **`/auth/register`**：创建买家账号并直接签发令牌（演示口径下不要求邮箱验证）。
- **`/auth/me`**：回读当前令牌的主体与角色（**需要令牌**，不能像 `/auth/login` 那样裸奔）。
- **`AuthFilter` 把 role 装进 `TenantContext`**；`RequestTrace`/MDC 照旧。

## Blocked by

[80](80-user-table-and-bcrypt-authenticate.md)。

## 口径

- **`/auth/` 前缀仍在 `AuthFilter` 的放行面上**，但 `/auth/me` 自己要求令牌——
  放行是过滤器不拦，不是端点不设防。
- **失败口径**：用户不存在与口令不对对外**同一句话**（401 + 既有错误体形状 ADR 0028），不泄露账号是否存在。
- **旧令牌照常可用**：没有 `role` claim 的历史令牌按 `BUYER` 解释——那正是 `/auth/mock-token` 发的，
  改了会让调试台与所有验收脚本一起红。
- 内部链路上会过一次明文口令，这是本仓第一次让口令离开进程（ADR 0058 Consequences 已记）。

## 验收

- JVM 用例覆盖：登录成功拿到带角色的令牌、错口令 401 且文案与「用户不存在」逐字相同、
  `/auth/me` 无令牌 401、有令牌回读出主体与角色、旧 mock 令牌仍按 `BUYER` 解释；
- **变异对照**：把 `role` claim 从令牌里去掉 → 「`/auth/me` 回读出角色」那条必须红；
- 配置面若有新增占位符，**显式登记**进 `ConfigValidationTest.applicationPlaceholderSetIsPinned` 的清单
  （round19 撞过一次：加 `${SHOPPILOT_*}` 会被那条用例当场拦住，处置是登记而不是绕过门禁）。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
```

## Handoff notes

### 落点

- `TenantContext.Identity` 加 `role` 与 `accountId` 两个坐标，并**保留三参便利构造**——
  「一个普通买家会话」是个明确概念，仓里 19 处测试与 mock 令牌都走它，不必逐个补 `UserRole.BUYER`。
  另加 `actor()`：有账号 id 用账号 id，没有就退回 `customerId`（**不用空串**——空串会被当成一个叫空的账号）。
- `JwtService`：新增 `issue(tenantId, customerId, role, accountId)`；角色与账号 id **只在真登录时写进 claim**，
  老令牌没有这两个 claim。`verify` 按「没有就是 `BUYER`」解释——写成具名方法 `roleOf(...)` 而不是内联三元，
  好让「这里的默认是 BUYER」被一眼看到。
- 新增 `IdentityClient`（网关 → biz-mock）：**刻意不套 `BizMockClient` 的熔断与工具语义**——
  那条路的失败会翻译成给模型看的 `ToolStatus`，而登录失败要回的是给人看的一句话。
- 新增 `AccountController`：`/auth/login`、`/auth/register`、`/auth/me`。**刻意与 `AuthController` 分开**：
  `/auth/mock-token` 是假身份领取口（回环才注册），这里是真身份入口，两者并存是有意的。
- 配置面**零新增占位符**，所以 `ConfigValidationTest` 的钉住清单一个字没动（round19 那次撞车的处置是登记，
  本轮干脆不制造这个需求）。

### 一个必须说清的「看起来违反 ADR 0005」的地方

登录请求体里有 `tenantId`，看起来就是「身份从 body 取」。实际分工是：
**请求里的 tenantId 只是「去哪张表里找这个用户名」的线索**，令牌里的 `tid` 取自**库里那一行的 `tenant_id`**。
用例 `tokenTenantComesFromTheStoredAccount` 钉的就是这一格：请求说 T001、库里那行属于 T002，签出来的令牌是 T002。
理由写在 `IdentityClient.call` 的注释里，不靠记忆。

### 读数

| 项 | 值 |
|---|---|
| JVM 全仓 | `5 + 55 + 315 + 22 = 397`（gateway 308 → 315，+7） |
| 覆盖率 | 四模块全过（gateway **62.67%**，门槛 54.00） |
| 变异对照 | 角色 claim 不写进令牌 → **2 条红**（`loginIssuesRoleBearingToken`、`meEchoesTheTokenSubject`），还原即绿 |

**一条判据都没动**；调试台与那批验收脚本**一行都不用改**（`/auth/mock-token` 与它们读的无 role 令牌行为不变）。

### 实现坑（两条）

1. **MockMvc 读中文会花屏**：响应字节按容器默认字符集解，写出去的是 UTF-8。
   判据必须 `getContentAsString(StandardCharsets.UTF_8)` 读回来（`RestErrorEnvelopeTest` 踩过一次，
   这里再踩一次并把读法收成一个具名方法）。
2. **`switch` 的 case 标签要是编译期常量**：`case HttpStatus.UNAUTHORIZED.value()` 编译不过
   （解构模式只能应用于 record）。写数字并在行尾点名 `HttpStatus.UNAUTHORIZED`。

### 现场三问

1. **为什么 `/auth/me` 不查身份域？** 它回答的是「我手里这张令牌是谁」，那只需要验签；
   查账号状态（停用之类）是角色守卫（票 82）的读者，不是它的。
2. **为什么老令牌按 BUYER 解释，而不是拒签？** `/auth/mock-token` 发的就是这种令牌，
   拒签等于让调试台和那批验收脚本一起红。安全性靠「签发权只在回环 + 签名密钥」，不靠这一处兼容分支。
3. **为什么角色进令牌而不是每次问身份域？** 网关自己就是验签方，令牌里带角色时判定是本地的；
   代价是「停用账号」不会立刻生效——**这一格本轮不覆盖，已登记给票 82 之后**。