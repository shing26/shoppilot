# 身份生命周期：HttpOnly 刷新令牌 + 失败锁定 + 服务端状态回读

Status: Accepted（2026-11-22 所有者指令开票，票 224 契约冻结后裁决）
Date: 2026-11-22
Deciders: 所有者
Round: 56（spec：`.scratch/shoppilot-mvp/round56-spec-identity-channel-hygiene.md`）

## Context

ADR 0056 把「演示账号能登录」做成了真账号，但当时明确把 refresh token、锁定策略与账号生命周期
列为非目标；ADR 0058 又把「密码与令牌策略只做到演示口径」写进交付材料。现在产品要回答的是
「这个账号能不能被运营」：令牌泄露后能否止损、口令泄露后能否改、暴力尝试能否被挡住、
停用或换角色后旧会话还能活多久。这些问题不解决，退款审批与工单领取的责任链仍然建立在一张
30 分钟不可撤销的令牌上。

约束来自既有契约，不能靠实现倒推：

- 令牌只在网关签发与验签（ADR 0058 第 1 条），签名密钥不出网关；
- 身份数据在 biz-mock，登录是租户级路径（ADR 0058 实现期更正）；
- 角色到能力的投影只留在 `Capabilities`，能力不进 JWT（票 221）；
- ops token 是运维凭证，不是用户身份（ADR 0058 第 5 条）；
- 仓库不存默认口令，演示账号只在显式给口令时播种（ADR 0029 / 0056）。

## Decision

### 1. 双令牌，刷新令牌只走 HttpOnly Cookie

- **access token**：网关自签 HS256 JWT，默认 TTL **15 分钟**，claim 在现有
  `tid` / `cid` / `role` / `aid` / `usr` 之外增加 `typ=access` 与 `pv`（口令版本）。
  响应体字段名继续叫 `token`，旧前端与验收脚本不需要改读法。
- **refresh token**：256 bit CSPRNG 不透明串，只在 biz-mock 落 **SHA-256 哈希**；
  默认 TTL **14 天**。网关通过 `Set-Cookie` 下发：
  `sp_rt=<opaque>; HttpOnly; SameSite=Strict; Path=/auth; Max-Age=<refresh-ttl>`；
  HTTPS 部署时加 `Secure`。**refresh token 不进 JSON 响应体、不进 URL、不进日志、
  不进 localStorage / sessionStorage**。
- 选 HttpOnly Cookie 而不是前端存储：契约禁止把 refresh token 写进前端持久化，
  而「刷新令牌放内存」会让每次刷新页面都掉登录。Cookie 是唯一同时满足两者的形状。
  CSRF 面由 `SameSite=Strict` + `Path=/auth` 收敛到刷新/登出两个端点；业务端点只认
  `Authorization` 头，不接受 Cookie 认证。

### 2. 刷新单次使用与令牌族撤销

- 每次刷新在 biz-mock 的 `refresh_tokens` 表里把旧行标记 `used_at`，并在同一
  `family_id` 下签发新行；旧值立即失效。
- 已使用或已撤销的 refresh token 再次出现 → 判定为泄露复用，**撤销整个 family**，
  返回 `401 refresh_reuse_detected`；access token 不追撤销，按 TTL 自然失效。
- 刷新时重新读账号行：`status != ACTIVE`、角色变化、`password_version` 变化任一命中，
  立即拒绝并撤销 family。这样停用/改密/换角色对 refresh 是即时的。

### 3. 改密

- `POST /auth/password`，请求体 `currentPassword` / `newPassword`；必须带有效 access token。
- biz-mock 校验当前口令，成功后更新 BCrypt 哈希、`password_version + 1`、撤销该账号
  全部 refresh family，并写审计事件。
- 旧口令登录立即失败；新口令立即可登录。改密前签发的 access token 允许用到 TTL
  （默认 15 分钟），不额外做每请求查库——这是「短 TTL」换「零每请求身份查询」的显式取舍。

### 4. 失败锁定与限流

- 计数键 = `(tenant_id, username)`。窗口 **15 分钟**内连续失败 **5 次**即锁定 **15 分钟**；
  成功登录清零。锁定期间即使口令正确也拒绝。
- 账号不存在与口令错误对外逐字同句、同状态码 `401`；账号不存在同样消耗一次哈希比较，
  保持响应时间不泄露存在性。
- 锁定响应为 `429` + `Retry-After`，文案统一为「尝试次数过多，请稍后再试」，
  不确认账号是否存在。另加每 IP 每分钟 10 次**失败登录**限流（成功登录不计数，
  避免把正常用户或验收脚本自己挡住），超限同样 `429`。
- 计数落 `users.failed_attempts` / `users.locked_until` / `users.last_failed_at`，
  进程重启后仍生效；阈值、窗口与锁定时长由 biz-mock 配置
  （`shoppilot.identity.*`，环境变量可覆盖）提供，代码内置同一组安全默认值。
  同时暴露 Micrometer 计数
  `shoppilot_identity_login_total{result=success|failure|locked}` 并写审计事件
  `identity.login.succeeded|failed|locked`。

### 5. 停用与角色变更

- 管理动作走 `POST /auth/accounts/{accountId}/status` 与
  `POST /auth/accounts/{accountId}/role`，控制器内显式要求 `ADMIN`；不复用 ops token，
  也不改 221 的 `capabilities` 与 403 语义。
- 停用/换角色后 refresh 立即拒绝；access token 最迟在 15 分钟 TTL 内失效。
- `/auth/me` 改为回读 biz-mock 的账号状态，`capabilities` 按服务端最新角色重算；
  令牌里的角色只用于请求期授权，不再作为「当前是谁」的最终答案。

### 6. 前端 401 单飞刷新

- `frontend-shared` 提供唯一刷新入口：401 时全局只允许一个 refresh 在飞；
  成功后用新 access token 重试原请求**一次**；失败只调用一次登出回调，不形成 401 循环。
- 两个入口都注册 `credentials: 'include'` 的刷新器；刷新失败清会话并回登录门。
- 两端顶栏提供「修改口令」入口，错误与成功文案可读，不回显内部账号名。

## Considered Options

- **refresh token 放 localStorage / sessionStorage**：否决。契约明文禁止前端持久化；
  且 XSS 可直接读走长期凭证。
- **refresh token 放 JSON 响应体、前端内存持有**：否决。刷新页面即掉登录，
  产品上不可接受；还会把长期凭证暴露给每个能读响应的脚本。
- **每次请求查库校验 access token 的 `pv` / 角色**：否决。网关是最热的进程，
  为 15 分钟的撤销窗口给每个请求加一次跨服务查询，成本与收益倒挂。
- **锁定按账号返回「账号已锁定」**：否决。等于提供一个账号枚举器；
  统一 429 文案与 `Retry-After` 足够让真人知道该等，不告诉攻击者账号是否存在。
- **引入 Spring Security 全套过滤器链**：否决。当前只需令牌签发/验签与两个守卫，
  引入整套框架会重写既有 `AuthFilter`、错误体与 ops 凭证语义（ADR 0028/0058）。

## Consequences

- 令牌撤销窗口从「不可撤销的 30 分钟」变成「refresh 即时、access ≤15 分钟」，
  这是可运营账号的最低口径，不是量产级密钥轮换。
- biz-mock 新增两张表（`refresh_tokens`、`login_attempts` 可选）与 `users` 三列；
  H2 与 PostgreSQL 两侧迁移版本钟必须同步（`PostgresMigrationParityTest`）。
- 登录链路多一次 refresh 端点与 Cookie 处理；网关 `AuthFilter` 的放行面不变
  （`/auth/**` 原本就放行，端点自己验令牌）。
- 前端不再把 refresh token 当会话字段；`Session` 只保留 access token 与展示信息。
- 锁定阈值、窗口、TTL 全部可配置并有安全默认值（配置面在 biz-mock；网关只下发
  access/refresh TTL 与 Cookie 安全开关）；不引入默认口令，不改变演示登录。
- 仍不做 SSO、MFA、邮箱找回、外部 IdP 与全套 RBAC 重做。
