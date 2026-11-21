# 票 221 契约：角色级 403 与菜单能力

**状态**：2026-11-20 冻结；2026-11-21 契约修订 1
**判定**：认证授权变更（认证/授权面）。按 `AGENTS.md` 走验收链路：
契约先冻结；Implementer 在独立分支 `codex/221-role-guard` 工作；独立 Verifier 亲跑命令；
证据绑定 HEAD SHA 与 `git status --short`；合并前所有者书面签收。
**开票依据**：所有者 2026-11-20 指令「按顺序接着下一步」；前序调查确认坐席账号当前可读写
渠道配置与店铺清单，工作台六个入口无条件渲染，403 文案无法区分「缺坐席」与「缺管理员」。
**基线说明**：工作树包含票 219（前端产品化重设计）与票 220（持久化档验收）的未提交改动；
本票不清理、不回退、不提交它们。221 的改动范围以 Allowed paths 为准。
当前 HEAD `20fe0965c47ab06e2d67788386c28f7e5769ae27`。

**本票不做**：真实身份生命周期（refresh token、改密、失败锁定）、登录审计、会话吊销、
新数据库表或迁移。这些是后续票，不混入本票。

## 目标

把「角色」从只用于页面标识，推进到可验证的权限契约：

1. 后端对管理员专属端点真正返回 403，而不是只靠前端隐藏菜单；
2. `/auth/login`、`/auth/demo-login`、`/auth/me` 返回后端派生的 `capabilities`；
3. 工作台侧栏按 `capabilities` 渲染入口，AGENT 看不到「渠道连接」；
4. 直接或程序化进入无权限视图时，页面显示明确的 403 状态，不伪装成登录失效；
5. 403 文案区分「需要坐席或管理员」与「需要管理员账号」，且 403 不触发登出。

## 权限矩阵

角色只回答「这个账号是谁」，不新增角色等级比较；每个端点仍按动作显式判定。

| 端点组 | BUYER | AGENT | ADMIN | `X-Ops-Token` 平台凭证 |
| --- | --- | --- | --- | --- |
| `/tickets` 队列、领取、释放、结单 | 403 | 允许 | 允许 | 允许（ADR 0058 双准入，本票不收紧） |
| `/channel-conversations` 渠道会话 | 403 | 允许 | 允许 | 允许 |
| `/refunds/**` 退款审核 | 403 | 允许 | 允许 | 403（ADR 0063 资金动作不变） |
| `/feedback/**`、`/knowledge/**` 复核与修订 | 403 | 允许 | 允许 | 允许（非资金动作） |
| `/quality/report` 编排质量 | 403 | 允许 | 允许 | 允许 |
| `/channels/**` 渠道连接 | 403 | **403（本票收紧）** | 允许 | 允许 |
| `/tenants` 店铺清单 | 403 | **403（本票收紧）** | 允许 | 允许 |
| 故障注入、清缓存、epoch、demo reset、negative-cache、retrieval、llm-fault | 403 | 403（无 ops token） | 403（无 ops token） | 允许 |

`/tickets/mine` 与 `/tickets/mine/{id}` 继续按买家身份放行，不要求坐席能力。

## Capability 契约

能力名只由后端定义一次，登录响应与 `/auth/me` 共用同一份派生：

| 角色 | `capabilities` |
| --- | --- |
| `BUYER` | `[]` |
| `AGENT` | `tickets`, `conversations`, `refunds`, `knowledge`, `quality` |
| `ADMIN` | `tickets`, `conversations`, `refunds`, `knowledge`, `quality`, `channels`, `tenants` |

顺序固定，便于前端与验收脚本逐项比较。`capabilities` 是「前端该显示哪些入口」的投影，
不是新的授权凭证，也不进入 JWT；后端每次从已验签角色重算。

## Allowed paths

- `shoppilot-gateway/src/main/java/com/shoppilot/gateway/identity/Capabilities.java`（新增）
- `shoppilot-gateway/src/main/java/com/shoppilot/gateway/identity/AccountController.java`
- `shoppilot-gateway/src/main/java/com/shoppilot/gateway/identity/AuthController.java`
- `shoppilot-gateway/src/main/java/com/shoppilot/gateway/web/OpsAuthorizationInterceptor.java`
- `shoppilot-gateway/src/main/java/com/shoppilot/gateway/web/OpsController.java`（仅 `/tenants` 守卫）
- `shoppilot-gateway/src/main/java/com/shoppilot/gateway/web/ChannelAdminController.java`（仅注释与守卫口径）
- `shoppilot-gateway/src/test/java/com/shoppilot/gateway/identity/CapabilitiesTest.java`（新增）
- `shoppilot-gateway/src/test/java/com/shoppilot/gateway/identity/AccountControllerTest.java`
- `shoppilot-gateway/src/test/java/com/shoppilot/gateway/identity/AuthControllerDemoLoginTest.java`
- `shoppilot-gateway/src/test/java/com/shoppilot/gateway/web/OpsAuthorizationInterceptorTest.java`
- `shoppilot-gateway/src/test/java/com/shoppilot/gateway/web/RoleGuardAndActorTest.java`
- `frontend-workspace/src/api.ts`
- `frontend-workspace/src/lib/nav.ts`（新增）
- `frontend-workspace/src/lib/nav.test.ts`（新增）
- `frontend-workspace/src/components/SideNav.vue`
- `frontend-workspace/src/App.vue`
- `shoppilot-gateway/src/main/resources/static/workspace/**`（重新构建产物）
- `scripts/verify-role-menu.ps1`（新增活体验收脚本）
- `plans/221-role-based-403-and-menu.md`
- `.scratch/shoppilot-mvp/issues/221-role-based-403-and-menu.md`
- `.scratch/shoppilot-mvp/README.md`
- `docs/EVIDENCE.md`（仅追加本轮证据）

## Acceptance criteria

1. `/auth/login`、`/auth/demo-login`、`/auth/me` 均返回 `capabilities`，三档角色的数组与
   Capability 契约逐项一致；旧令牌（无 role claim）按 BUYER 返回空数组。
2. AGENT 调用 `GET /api/v1/support/ops/channels` 与 `GET /api/v1/support/ops/tenants`
   均返回 403，响应体 `code=role.denied`，`message` 明确包含「管理员」。
3. ADMIN 调用上述两个端点不返回 403；渠道连接返回 200，店铺清单返回下游 200 或下游业务错误，
   但不得因角色被网关拒绝。
4. BUYER 调用任意坐席端点仍返回 403；`/tickets/mine` 与 `/tickets/mine/{id}` 仍按买家身份放行。
5. `X-Ops-Token` 平台凭证对 `/channels/**`、`/tenants`、工单、渠道会话、知识、质量仍保持
   既有放行；退款审核仍只认 AGENT/ADMIN，不因本票改变 ADR 0063。
6. 故障注入、清缓存、epoch bump、demo reset、negative-cache、retrieval、llm-fault 仍只认
   `X-Ops-Token`；AGENT/ADMIN 不持 ops token 时仍 403。
7. 工作台 `Session` 持久化 `capabilities`；AGENT 侧栏不渲染「渠道连接」，ADMIN 渲染全部六个入口；
   新登录与刷新后的入口集合均来自服务端字段。
8. 直接进入无权限视图（例如 AGENT 的 `view='channels'`）时，主工作区显示「403 / 没有访问权限」
   状态与返回入口，不显示空白页，也不触发登出。
9. 403 响应不再统一显示「这个账号没有坐席权限」；有后端 `message` 时透出后端文案，
   `describe()` 与 `describeKnowledge()` 口径一致。401 仍触发既有 `setUnauthorizedHandler` 登出。
10. `frontend-workspace` 的 `typecheck`、`vitest`、`vite build` 全绿；全仓
    `.\mvnw.cmd -B -ntp verify` 绿；`git diff --check` 退出 0。
11. 活体脚本 `scripts/verify-role-menu.ps1` 在容器 `full` 档或等价网关入口上输出
    `PASS/FAIL` 汇总，覆盖 AGENT/ADMIN 的 capabilities、渠道/店铺 403、管理员放行、
    工单/退款/质量/知识仍可用、ops token 平台路径不变。
12. 证据绑定 HEAD SHA 与 `git status --short`；票面 Handoff 写明命令、读数、未达成项与边界。

## Verification commands

```powershell
.\mvnw.cmd -B -ntp -pl shoppilot-gateway -am test
.\mvnw.cmd -B -ntp verify
cd frontend-workspace; npm run typecheck; npm test; npm run build
cd frontend-buyer; npm run typecheck; npm test; npm run build
cd ..
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
pwsh -NoProfile -File scripts/verify-role-menu.ps1
git diff --check
git status --short
```

活体验收前置：容器 `full` 档网关已用包含本票代码的镜像启动，
`.env` 提供 `SHOPPILOT_IDENTITY_DEMO_PASSWORD`；脚本不打印任何 token 或口令。

## Forbidden

- 不把 `X-Ops-Token` 当成 ADMIN 用户能力，也不把 ops token 写进 `capabilities`。
- 不改 ADR 0058 的双准入与 ADR 0063 的资金动作边界；不顺手收紧工单/知识/质量的 ops token 路径。
- 不删除或弱化既有 403 测试、断言、gold、阈值、提示词或迁移。
- 不只做前端隐藏菜单；没有后端 403 的实现视为未完成。
- 不新增数据库表、迁移、JWT claim 或 token 刷新逻辑。
- 不把 403 接到 401 的登出处理器上；403 必须保留当前会话。
- 不打印、记录或提交 secret、token、口令。
- 不清理、回退或提交票 219/220 的未提交改动。
- 发现缺陷时只记录复现与原始读数；需要扩大范围时先修订本契约。

## 契约修订 1（2026-11-21，实现完成后、独立验证前）

全仓 `mvnw verify` 已绿（821 tests / 136 suites，其中 gateway 667），但 CI 的
`gen_status.py --check` 与 `verify_doc_truth.py` 报红：`docs/STATUS.md`、`README.md`、
`docs/TUNING_PLAN.md` 仍写着上一基线 806 / 135 / gateway 652。这是新增测试后的机械计数漂移，
不是判据、阈值、gold 或指标分母变化。若不修，代码绿但 `ci-subset` 必红；若直接修，又越过原
Allowed paths。因此追加本修订，仅同步派生计数：

- Allowed paths 追加：`docs/STATUS.md`（仅由 `gen_status.py` 重生成）、
  `README.md`（仅 JVM 测试 badge / CI 段 / 复现段计数）、
  `docs/TUNING_PLAN.md`（仅 JVM 测试与套件计数）、
  `scripts/verify-role-ui.mjs`（AC7/8 的浏览器级验收脚本）。
- Acceptance criteria 追加第 13 条：`python scripts/gen_status.py --check` 与
  `python scripts/verify_doc_truth.py` 均退出 0，且读数与当前 surefire 一致。
- 不新增第 14 条判据；不修改任何既有验收阈值、gold、提示词、迁移或断言。

修订后仍需独立 Verifier 亲跑全部命令；原第 1–12 条与 Forbidden 其余条款不变。
