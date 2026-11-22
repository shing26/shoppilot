# 票 221：角色级 403 与菜单能力

Round: 53（认证授权面；所有者 2026-11-20 指令「按顺序接着下一步」）
ADR: 0058（角色守卫与自报身份退役）、0063（资金动作不可抵赖）；正文不改
Status: done（2026-11-21 所有者书面签收；2026-11-23 票 226 状态校正：已随 round51–53 统一提交于 `4fc0309`）　Claimed by: root　Blocked by: 无
契约：[plans/221-role-based-403-and-menu.md](../../plans/221-role-based-403-and-menu.md)（2026-11-20 冻结）

## What to build

把坐席工作台的角色边界补成可验证的产品契约：

1. 新增后端 `capabilities` 派生（BUYER 空；AGENT 五项；ADMIN 再加 `channels` / `tenants`），
   在 `/auth/login`、`/auth/demo-login`、`/auth/me` 返回。
2. `/channels/**` 与 `/tenants` 收紧为「ADMIN 或 X-Ops-Token」；AGENT 返回 403，
   文案明确要求管理员账号。
3. 其余坐席端点保持现有行为：工单/会话/退款/知识/质量对 AGENT/ADMIN 放行；
   退款仍不接受 ops token；平台动作仍只认 ops token。
4. 工作台 `Session` 持久化 `capabilities`，侧栏按能力渲染；AGENT 不显示「渠道连接」。
5. 直接进入无权限视图时显示明确的 403 状态与返回入口；403 不登出。
6. 新增 `scripts/verify-role-menu.ps1`，覆盖 AGENT/ADMIN/买家/ops token 四条路径。

## 边界

- 不改 ADR 0058/0063 的既有结论；不把 ops token 当用户能力。
- 不做 refresh token、改密、失败锁定、会话吊销或数据库迁移；这些是后续票。
- 不改买家 API、gold、阈值、提示词、既有 403 断言或迁移。
- 不清理票 219/220 当时尚未落盘的改动。

## Verify

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

## 验收项

- 三档角色的 `capabilities` 与契约逐项一致；旧令牌按 BUYER 返回空数组。
- AGENT 访问 `/channels` 与 `/tenants` 均 403、`code=role.denied`、文案含「管理员」；
  ADMIN 不被网关拒绝；BUYER 仍 403；`/tickets/mine` 仍放行。
- ops token 对渠道/店铺/工单/会话/知识/质量保持既有放行；退款仍只认 AGENT/ADMIN；
  平台动作仍只认 ops token。
- AGENT 侧栏不显示「渠道连接」，ADMIN 显示全部六个入口；无权限视图显示 403 状态。
- 403 文案透出后端 message，401 仍触发登出。
- 后端测试、两端 typecheck/vitest/build、全仓 `mvnw verify` 全绿。
- 活体脚本输出 PASS/FAIL 汇总；证据绑定 HEAD SHA 与 `git status --short`。

## Handoff notes

**Status**：done（2026-11-21 所有者书面签收；2026-11-23 校正：已随 round51–53 统一提交于 `4fc0309`）

### 关键决策

- 角色到能力的映射只写在后端 `Capabilities` 一处，`/auth/login`、`/auth/demo-login`、
  `/auth/me` 共用；能力不进 JWT，每次按已验签角色重算。
- 管理员专属面只收紧 `/channels/**` 与 `/tenants`；工单、会话、退款、知识、质量保持原行为。
  `X-Ops-Token` 仍是平台兜底路径，不是 ADMIN 用户能力，也不写进 `capabilities`。
- 退款资金动作继续只认 AGENT/ADMIN，不接受 ops token；平台故障注入等动作继续只认 ops token。
- 前端只把 `capabilities` 当作“画哪些入口”的投影；真正的边界在后端。403 不接 401 的登出
  处理器，保留当前会话并显示可返回的 403 面板。
- 旧 `sessionStorage` 没有 `capabilities` 时按角色补齐，避免升级即强制全员重登；服务端显式返回
  空数组时保持空数组，不再按角色回填。
- 契约修订 1：新增测试使 821/136 的派生计数与旧文档不一致。只允许同步
  `docs/STATUS.md`、`README.md`、`docs/TUNING_PLAN.md` 的机械计数，并新增
  `scripts/verify-role-ui.mjs` 验证 AC7/8；不改任何判据、阈值、gold 或指标分母。

### 验证落点

- `MAVEN_OPTS=-Xmx512m; .\mvnw.cmd -B -ntp verify` → **BUILD SUCCESS**，四模块
  `12 + 92 + 673 + 50 = 827 tests / 136 suites`，0 failures / 0 errors / 0 skipped。
- `frontend-workspace`：`typecheck` 通过；`vitest` **2 files / 13 tests**；`build` 通过
  （`index-CYMnA0Uy.js` / `index-C5u1B2NP.css`）。
- `frontend-buyer`：`typecheck` 通过；`vitest` **4 files / 37 tests**；`build` 通过
  （`index-CUUWRcjp.js` / `index-DriS6bU5.css`）。
- `pwsh -NoProfile -File scripts/check-ps-syntax.ps1` → `files=37 errors=0`。
- `python scripts/gen_status.py --check` → `STATUS ok`；
  `python scripts/verify_doc_truth.py` → `827 tests / 136 suites` 全锚点一致。
- 活体 API：`scripts/verify-role-menu.ps1` → **PASS 39 / FAIL 0 / SKIP 0**。覆盖
  AGENT 五项能力、渠道/店铺 403 + `role.denied` + 管理员文案、ADMIN 七项能力与两端点 200、
  BUYER 空能力与 `/tickets/mine` 200、AGENT 工单/退款/知识/质量 200、ops token 平台路径，
  平台动作无 token 403（含 `GET /llm-fault` 的 AGENT/ADMIN 两档与 ops token 200），
  `;x` 矩阵参数路径的 AGENT/ADMIN/ops token 三档，
  以及 403 后 `/auth/me` 仍 200 且仍带能力。
- 活体浏览器：`scripts/verify-role-ui.mjs` → **PASS 11 / FAIL 0**。AGENT 侧栏 5 个入口且无
  「渠道连接」；程序化进入 `channels` 显示 403 面板，未回登录页，`sessionStorage` 会话保留；
  ADMIN 侧栏 6 个入口且含「渠道连接」；无未捕获页面异常。
- 证据日志（本机，不入库）：
  `logs/role-menu-221-r3-20261121-045017.log`、
  `logs/role-ui-221-r3-20261121-045017.log`、`logs/status-221-r3-20261121-045017.txt`、
  `logs/verify-221-jvm-r3-20261121-045017.log`。
- HEAD `20fe0965c47ab06e2d67788386c28f7e5769ae27`；`git diff --check` 退出 0。

### 第二轮：首轮 Verifier FAIL 与修复

- 首轮独立 Verifier 判定 **FAIL**：AC1–5、AC7–13 通过，但 AC6 的
  `GET /api/v1/support/ops/llm-fault` 在 AGENT/ADMIN 不带 `X-Ops-Token` 时仍返回 200。
  根因是 `OpsController.getLlmFault()` 没有 token 参数，`OpsAuthorizationInterceptor`
  也只对渠道/店铺做管理员判定，平台动作依赖逐方法守卫，漏掉了这一个 GET。
- 修复没有给单个方法补一行，而是在拦截器里建立平台动作默认拒绝集合
  （`fault`、`stats`、`demo/reset`、`retrieval`、`epoch/bump`、`cache/flush`、
  `llm-fault`、`negative-cache`）：正确 ops token 放行；AGENT/ADMIN 无 token 返回
  **403 `role.denied`**、文案含“运维”；运维面关闭时保留 `ops.disabled`。
- 新增 `OpsAuthorizationInterceptorTest` 覆盖全部平台路径的 token/角色矩阵；
  `RoleGuardAndActorTest` 增加 `agentCannotReadLlmFault`，并在 standalone MockMvc 中
  注册同一拦截器。定向测试 **30/30** 通过。
- 活体脚本补 `AGENT / llm-fault 403 + 文案`、`ADMIN / llm-fault 403`、
  `ADMIN / fault 403`、`ops token / llm-fault 200` 四组断言，最终 **34/34**。
- 全量从 `821/136` 增至 **`827/136`**（gateway `667 → 673`）；`docs/STATUS.md`、
  `README.md`、`docs/TUNING_PLAN.md` 已同步机械计数。

### 第三轮：矩阵参数绕过与修复

- 第二轮独立 Verifier 又判 **FAIL**：规范路径三档已正确，但 Spring MVC 在路由前剥离
  分号矩阵参数，而拦截器用 `getRequestURI()` 原始值比对，导致
  `GET /ops/channels;x` 与 `GET /ops/llm-fault;x` 对 AGENT 返回 200——同一 handler
  的等价路径绕过了整个授权面。
- 修复：拦截器改用 `UrlPathHelper.defaultInstance.getPathWithinApplication(request)`
  取路径，与 MVC 的路由口径一致；`;x` 从每个路径段剥离后再做前缀/精确匹配。
  这不是给两个端点打补丁，而是消除“拦截器看的路径”和“MVC 路由的路径”之间的差异。
- 回归：`OpsAuthorizationInterceptorTest` 增加管理员面与全部平台路径的矩阵参数用例；
  `RoleGuardAndActorTest` 增加端到端 `;x` 用例。定向 **33/33**。
- 变异对照：把修复临时退回 `getRequestURI()`，三条新用例 **3/3 全红**；恢复后全绿。
- 活体补 `AGENT /channels;x`、`AGENT /llm-fault;x`、`ADMIN /channels;x`（应 200，
  管理员不被降级）、`ADMIN /llm-fault;x`、`ops token /llm-fault;x` 五组断言，
  最终 **PASS 39 / FAIL 0 / SKIP 0**。

### 独立 Verifier 结论（第三轮）

- **PASS**。Verifier 亲跑：定向 **33/33**；全量 **827 tests / 136 suites**、0 失败；
  两端 typecheck + vitest（13 / 37）+ build；PowerShell `files=37 errors=0`；
  `gen_status --check` 与 `verify_doc_truth.py` 均通过；`git diff --check` 退出 0；
  活体 API **39/39**、浏览器 **11/11**。
- 矩阵参数复验：AGENT `/channels;x` 与 `/llm-fault;x` 均 403 `role.denied`；
  ADMIN `/llm-fault;x` 403、`/channels;x` 200；正确 ops token `/llm-fault;x` 200；
  规范路径三档仍为 403/403/200。
- 等价路径探测：`/channels/`、`//channels`、`///channels`、额外矩阵参数、编码字符、
  `%2F`/`%5C`、双编码、Unicode 分号、`.`/`..`、HEAD、BUYER 变体均无 200 数据绕过；
  未执行任何写请求。
- 边界备注（不在本票 AC 内）：匿名 `/tickets/mine;x.html` 会到达控制器但 fail-closed
  返回 502“缺少已验签身份”，未返回数据；属既有 AuthFilter 后缀放行与 buyer-safe
  前缀交互，不是角色绕过。建议在所有者签收记录中保留该备注。
- 证据：`logs/verifier-221-r3-20261121-0500.log`；HEAD
  `20fe0965c47ab06e2d67788386c28f7e5769ae27`，`git status --short` 112 行。

### 所有者书面签收（2026-11-21）

- 所有者对话内书面指令：**「签收票221」**。
- 签收范围：票 221 的 AC1–AC13、契约修订 1 与两轮 Verifier FAIL 的修复；以第三轮独立
  Verifier **PASS** 为验收依据（定向 33/33、全量 827/136、活体 API 39/39、浏览器 11/11）。
- 保留边界：矩阵参数与等价路径无第三条数据绕过；匿名 `/tickets/mine;x.html` 为既有
  fail-closed 502，无数据返回，不属本票角色绕过，不在 AC 内。
  2026-11-23 校正（票 226）：以下历史描述保留当时现场；这些改动后来已随
  round51–53 统一提交于 `4fc0309`，不再代表当前工作树状态。
- 签收时工作树同时含票 219/220 的改动，二者当时尚未取得所有者书面签收；
  按验收链路硬规则「无签收合并即失败」，本票不单独提交或合并。待 219/220 签收后再按
  分支与提交顺序统一收口。

### 三个现场追问

1. **为什么菜单隐藏了还要后端 403？** 菜单只防误点；直接请求、旧深链和脚本都能绕过前端。
   本票的验收对象是端点级 403，不是 `v-if`。
2. **为什么 ops token 不在 `capabilities` 里？** 它是平台/运维凭证，不是某个登录用户的角色能力。
   如果混进登录响应，前端会把“运维路径”和“管理员角色”画成同一个东西，后续无法区分。
3. **为什么 `/tenants` 在拦截器和控制器各有一道守卫？** 拦截器负责整个 `/ops/**` 的统一准入，
   控制器守卫保证即使路由映射或拦截器顺序变化，店铺枚举也不会被 AGENT 读到；两者共用
   `Capabilities` 映射，不是第二套角色比较。

### 边界

- 本机内存仅约 446 MB，可用的其他项目 15 个容器仍在运行；未启动容器 `full` 档。活体使用
  本机 JVM 三服务（biz-mock / ticket / gateway，H2 默认档）+ 独立 Redis 容器，验证的是同一套
  网关鉴权与前端产物，不是 Postgres 容器镜像行为；持久化档由票 220 单独覆盖。
- 活体网关以降级模式启动（Qdrant/ES/embedding sidecar 未起）；221 只验证身份、授权与菜单，
  不调用聊天/检索路径，因此该降级不影响本票断言。`/actuator/health/readiness` 为 UP。
- 未验证 refresh token、改密、登录失败锁定、会话吊销、登录审计、渠道配置热生效；这些是后续票，
  未混入本票。
- 签收时工作树同时包含票 219（前端重设计）与票 220（持久化验收）的改动；本票当时
  未清理、未回退、未单独提交它们。2026-11-23 校正：三票改动已统一提交于 `4fc0309`，
  三票均取得所有者书面签收。
