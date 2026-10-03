# 81 网关登录端点与角色 claim

**Status:** ready-for-agent

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

（收口时补）