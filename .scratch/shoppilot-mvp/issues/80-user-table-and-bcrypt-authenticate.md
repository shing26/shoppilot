# 80 身份域骨架：用户表 + bcrypt + 内部认证端点

**Status:** implemented（2026-10-04）

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

### 落点

- Flyway **`V7__identity_users.sql`**：`users` 表 + `uk_users_tenant_username` + `idx_users_tenant_role`。
- 实体 `UserAccount`（挂 `@TenantId`）、仓储 `UserAccountRepository`（**刻意不提供不带租户的重载**）。
- `IdentityService`：BCrypt cost 10、最短口令 8 位、注册 / 认证 / 按角色建号。
- `IdentityController`：`/api/identity/register`、`/api/identity/authenticate`。
- 契约 `UserRole` / `RegisterAccountRequest` / `AuthenticateRequest` / `AccountView` 落在 **`shoppilot-tool-api`**。
- 演示账号播种 `IdentitySeedRunner`（`@Order(20)`，排在 `SeedRunner` 之后）。
- 依赖面 **+1**：`org.springframework.security:spring-security-crypto`（单 artifact，不引 starter-security；版本由 spring-boot-dependencies 管）。

### 三处与票面不同，都改了 ADR 0058 而不是悄悄做

1. **登录是租户级路径，不是平台级放行**。票面原本写「登录那一刻还没有租户，按平台级放行」——
   实际不需要：用户名按 **(租户, 用户名)** 唯一（跨店同名是正常业务），登录表单先选店即可。
   换来两样：`InternalAuthFilter` 一行不改，`users` 表能照常挂 `@TenantId`，
   **不必给 ADR 0005 的租户隔离开例外**。ADR 0058 已按实现期更正改写。
2. **仓库里没有默认口令**。`shoppilot.bizmock.identity.demo-password` 默认空串，语义是
   「一条演示账号都不建」；给了才给每租户建 `buyer` / `agent` / `admin`。
   与 ADR 0029 对三处服务凭证是同一条家法。`.env.example` 与 compose 都留了口子，后者同样**不给默认值**。
3. **`AccountView` 里没有状态字面量**：停用账号仍要能被查到（审计要能解释它什么时候不再有权限），
   但「登不进来」这件事发生在 `authenticate`，不靠视图透出状态。

### 一条自己造的假绿（变异对照抓出来的）

第一版的「停用账号登不进来」把「口令错」的对照锚点放在了**同一个已停用账号**上——
两边都返回 401，断言恒成立，于是把「BCrypt 校验被绕过」也一起放了过去。
跑变异（`encoder.matches(...)` 换成恒真）时它没红，是对照实验告诉我的：
另外两条立刻红，它不红。修法是**锚点换成一个仍然启用的账号 + 额外断言「停用前它是登得进去的」**，
改完再跑变异，两条都红。

### 读数

| 项 | 值 |
|---|---|
| JVM 全仓 | `5 + 55 + 308 + 22 = 390`（biz-mock 45 → 55，+10） |
| 覆盖率 | biz-mock **77.03%**（门槛 76.00）/ gateway 62.72% / ticket 74.18% / tool-api 40.00%，`COVERAGE OK modules=4` |
| 变异对照 | `matches(...)` 恒真 → **2 条红**（`wrongPasswordAndUnknownUserLookIdentical`、`disabledAccountCannotLogIn`），还原即绿 |
| 收口审计 | `PASS 85 / FAIL 2 / SKIP 9`，两红是 `A2`（工作树未提交）与 `F1c`（round20 那笔不可逆的本机日志删除） |

**一条判据都没动**；G6 常数未换代（它比的是入仓的落点日志，票 85 收口时一起换）。

### 实现坑（三条）

1. **`@Order` 注解与 `domain.Order` 撞简名**：`SeedRunner` 里 `import org.springframework.core.annotation.Order`
   与本包的订单实体 `Order` 二义，编译期直接报「对 Order 的引用不明确」。
   改用 `implements Ordered`（接口名不撞），而不是换个注解位置糊过去。
2. **`TestRestTemplate` 断言 401 会变成传输层报错**：`HttpURLConnection` 拿到 401 会尝试重新认证，
   而请求体是流式发送的、重发不了，于是每次都抛
   `cannot retry due to server authentication, in streaming mode`——
   报错指向传输层，把「401 是不是真返回了」这件事盖掉。本类一律用 JDK `HttpClient`。
3. **`schema` 断言要跟着加表**：`SchemaMigrationTest` 的 `BASELINE_TABLES` 是 **V1 基线**的 9 张表，
   不是全库表清单（`V3`/`V5` 加的表本来就不在里面）。所以 V7 的 `users` 由本票自带的
   `usersTableExists` 覆盖，不去动那条 V1 断言。

### 现场三问

1. **为什么身份域落在 biz-mock 而不是网关或第五个服务？** ADR 0058 的 Considered Options 逐条写了，
   核心是资源账（0053 已因同一条理由否决第五个服务）与「网关没有数据源」这两条。
2. **为什么口令不落默认值？** 仓库里的默认口令等于一把没换过的锁，ADR 0029 对三处服务凭证已经这么判过一次。
   代价是演示前要先给一个口令，写在 `.env` / 容器档 `.env` 里——那本来就是凭证该待的地方。
3. **「用户不存在」与「口令错」为什么对外同一句话？** 防账号枚举。自用/演示口径下这条够用，
   但它**不是量产级**（没有失败锁定、没有速率限制），已按 ADR 0056 的非目标照登。