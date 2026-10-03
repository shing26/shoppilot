# 80 身份域骨架：用户表 + bcrypt + 内部认证端点

**Status:** ready-for-agent

## What to build

身份域的第一层，落在 `shoppilot-biz-mock`（ADR 0058 第 1 条）。

- **`users` 表**（Flyway `V7__identity_users.sql`）：`id` / `tenant_id` / `username` / `password_hash` /
  `role`（`BUYER` / `AGENT` / `ADMIN`）/ `subject_ref` / `display_name` / `status`。
  - `subject_ref` 是「这个账号动作的对象」：`BUYER` 指向 `customers.id`，`AGENT` / `ADMIN` 为空。
  - 唯一约束按 **(tenant_id, username)**，不是全局 username——跨店同名是正常业务，不是冲突。
- **实体与仓储**：`UserAccount` + `UserAccountRepository`，`@Table` 上标索引依据（照仓内既有先例）。
- **BCrypt 哈希**：用 `spring-security-crypto` 的 `BCryptPasswordEncoder`（单 artifact，不引整个 Spring Security）。
- **内部端点** `/api/identity/register`、`/api/identity/authenticate`，在 `X-Internal-Token` 之后。
- **种子账号**：每租户一个买家、一个坐席、一个管理员，口令来自配置且**不进仓库**（非回环时缺失即拒启动）。

## Blocked by

无。round23 全部收口（[75](75-round23-closeout.md)）。

## 口径

- **`ddl-auto: validate` 不变**：新表只能走 Flyway，否则启动红（ADR 0041）。
- **biz-mock 的域范围变宽是有意偏差**（ADR 0058 Consequences）：0053 给它的定义不含身份，
  本票追加「身份与账号」，`docs/CODE_MAP.md` 要跟着改，不许按 0053 的清单去找身份在哪。
- **`InternalAuthFilter` 的登录面按平台级放行**：登录那一刻还没有租户，而租户是账号的属性——
  与 `/api/admin/` 同理，不放行就是「永远登不上」。
- **口令不回显**：任何响应里不出现 `password_hash`；错误消息区分「用户不存在」与「口令不对」是**明确不做**的
  （演示口径下不必，但要在代码注释里写明这是有意的简化，不是漏了）。
- 一条判据都没动，CI 九步不动。

## 验收

- JVM 用例覆盖：注册成功、重复用户名（同租户拒 / 跨租户允许）、口令错、账号禁用、跨租户查不到、
  哈希不落明文、`/api/identity/*` 缺 internal token 即 401；
- **变异对照**：把 `BCryptPasswordEncoder.matches(...)` 换成恒真 → 用例必须红；
- `.\mvnw.cmd -B -ntp verify` 绿；biz-mock 覆盖率不低于棘轮。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
python .scratch/shoppilot-mvp/round3-closeout-audit.py
```

## Handoff notes

（收口时补）