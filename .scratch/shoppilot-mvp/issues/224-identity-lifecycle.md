# 票 224：身份生命周期生产口径

Round: 56（所有者 2026-11-22 指令「按 224 身份生命周期 → 225 → 226 开票」）
ADR: 0086（实现前新增并 Accepted；开票依据 = 所有者政策覆盖）
Status: implemented（待独立 Verifier）　Claimed by: root　Blocked by: 无
契约：[plans/224-identity-lifecycle.md](../../../plans/224-identity-lifecycle.md)（2026-11-22 冻结）

## What to build

把当前「演示账号能登录」升级为可运营的身份生命周期：

1. access + refresh 双令牌；refresh 单次使用并轮换；旧 refresh 复用视为泄露，撤销整个令牌族。
2. 改密接口与两端入口：校验当前口令；成功后撤销全部 refresh，旧口令立即失效。
3. 登录失败锁定与限流：按租户 + 账号计数，达到阈值锁定；锁定期间正确口令也不放行；
   窗口结束自动恢复；错误文案不泄露账号是否存在。
4. 停用账号 / 角色变更：refresh 立即拒绝；access token 最迟在一个短 TTL 内失效；
   角色能力按服务端状态重算。
5. 前端 401 刷新：单飞刷新，只重试原请求一次；刷新失败只登出一次，不形成循环。

## 边界

- 不做 SSO、MFA、邮箱找回、外部 IdP、全套 RBAC 重做或默认口令。
- 不改退款/工单/支付业务规则；不改 221 的 `capabilities` 语义与 403 契约。
- ops token 路径保持现状；不得把 refresh token 写进日志、URL 或 localStorage。
- 不删除既有登录/演示入口；演示档仍可一键进入。

## Verify

```powershell
.\mvnw.cmd -B -ntp -pl shoppilot-biz-mock -am test
.\mvnw.cmd -B -ntp -pl shoppilot-gateway -am test
.\mvnw.cmd -B -ntp verify
cd frontend-buyer; npm run typecheck; npm test; npm run build
cd ..\frontend-workspace; npm run typecheck; npm test; npm run build
cd ..
node scripts/verify-identity-lifecycle.mjs
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
git diff --check
git status --short
```

活体脚本必须真发 HTTP，至少覆盖：登录 → 刷新 → 旧 refresh 复用被拒 → 改密 →
旧 refresh/旧口令失效 → 新口令登录 → 连续失败锁定 → 窗口后恢复。

## 验收项

- refresh 轮换：新 refresh 可用，旧 refresh 立即拒绝；同一旧 refresh 再用一次会撤销令牌族。
- 改密：错误当前口令被拒；正确改密后全部旧 refresh 拒绝、旧口令登录失败、新口令登录成功。
- 锁定：阈值内失败后正确口令也不放行；锁定窗口结束自动恢复；响应不区分账号不存在与口令错。
- 停用/角色变更：refresh 立即拒绝；access token 最迟在 TTL 内失效；能力按新角色返回。
- 前端：401 只触发一次刷新与一次重试；刷新失败只登出一次；两端改密入口可用。
- 日志、错误体与前端存储不出现 refresh token、明文口令或内部账号名。
- JVM、两端 typecheck/vitest/build、全仓 verify 与活体脚本全绿。

## Handoff notes

**关键决策**

- **双令牌形状**：access 仍是网关自签 HS256 JWT（`typ=access`、`pv` 口令版本，默认 15 分钟），
  响应字段继续叫 `token`；refresh 是 256 bit 不透明串，只以 `sp_rt` HttpOnly Cookie
  （`SameSite=Strict`、`Path=/auth`、HTTPS 时 `Secure`）下发，不进 JSON、URL、日志或前端存储。
- **refresh 无 access 时如何定位租户**：令牌自身带 `v1.<base64 tenant>.<secret>` 前缀，
  biz-mock 先解租户再查哈希；库内只落 SHA-256。每次刷新轮换旧行，旧值复用即撤销整个
  `family_id`。
- **状态回读**：刷新时重读账号行；停用、角色变化、`password_version` 变化任一命中即拒绝并撤族。
  `/auth/me` 改为回读 biz-mock 最新角色，`capabilities` 按服务端角色重算；access 不逐请求查库，
  最迟 15 分钟 TTL 失效。
- **锁定口径**：`(tenant_id, username)` 计数，15 分钟窗口内连续 5 次失败锁定 15 分钟；
  成功登录清零；锁定期间正确口令也返回 `429 login_locked` + `Retry-After`。阈值/窗口/时长
  已做成 biz-mock 配置（`shoppilot.identity.*`，环境变量可覆盖）并保留同一组安全默认值。
  账号不存在与口令错误统一 `401` 文案，且都消耗一次 BCrypt 比较。
- **改密取舍**：`POST /auth/password` 要求当前口令；成功后撤销全部 refresh、旧口令立即失败，
  但改密前签发的 access 允许用到 15 分钟 TTL，不引入每请求查库。
- **前端单飞**：`frontend-shared` 只有一个导出面，`index.ts` 内实现全局唯一 refresh Promise；
  并发 401 共用同一 Promise，成功只重试原请求一次，失败只登出一次。两端顶栏「修改口令」
  复用同一 `PasswordDialog`，不显示内部账号 id。
- **契约外机械同步（请 Verifier 裁决）**：`docs/TUNING_PLAN.md` 不在 224 Allowed paths，
  但 `verify_doc_truth.py` 强制比对测试数；已只改 `827/136 → 842/138` 三处，无口径改写。
  `frontend-shared/package.json` 的 `./auth` 子路径导出已撤回，身份调用并入 `index.ts`。

**验证落点**

- 实现提交：`efc80f9`（独立分支 `codex/224-identity-lifecycle`，基线 `ccf7ea6`）；
  本行之后的票面更新只追加验证记录，不再改业务代码。证据命令均在 `D:\ShopPilot` 执行。
- 全仓：`.\mvnw.cmd -B -ntp verify "-Dsurefire.forkCount=1"` → BUILD SUCCESS，
  `12 + 97 + 683 + 50 = 842 tests / 138 suites`，0 failures / 0 errors / 0 skipped。
  日志 `logs/verify-round56-224-final.log`（本机证据，未入库）。
- 覆盖率：`python scripts/check_coverage.py` → biz-mock 84.10% / gateway 70.21% /
  ticket 78.62% / tool-api 41.28%，四模块全 OK。
- 前端买家：`npm run typecheck`、`npm test`（5 files / 41 tests，含单飞刷新 4 条）、
  `npm run build` 全绿；
  坐席：`typecheck`、`vitest`（3 files / 15 tests）、`build` 全绿；静态产物已重建入库。
- 活体：`node scripts/verify-identity-lifecycle.mjs` **20/20 PASS**（真发 HTTP 到 8082，
  覆盖登录 → 刷新 → 旧 refresh 复用被拒 → 整族失效 → 改角色 → 改密 → 旧 refresh/旧口令失效 →
  新口令登录 → 停用 → 连续失败锁定 429 + Retry-After）。锁定窗口 15 分钟未实等，
  窗口恢复由 `IdentityLifecycleTest.lockoutBlocksCorrectPasswordUntilWindowEnds` 拨动
  `locked_until` 验证；脚本明确打印为 INFO，不写成 PASS。
- 文档/脚本：`gen_status.py --check`、`verify_doc_truth.py`、`check-ps-syntax.ps1`
  （37 files / 0 errors）、`git diff --check` 全绿。
- 宿主机内存告警：本轮多次遇到 45 GB 提交上限被其他项目占满导致原生分配失败；
  用 `-Dsurefire.forkCount=1` 后全绿，未停止或修改其他项目进程。

**三个现场追问**

1. 锁定阈值已做成 biz-mock 全局配置；是否还要按租户策略分别配置（需要租户级配置表）？
2. 改密后当前 access 继续用到 TTL 是 ADR 的显式取舍；若要求“立即全端登出”，
   需要每请求校验 `pv` 或引入吊销表，成本会回到热路径。
3. 本期只做持口令改密；“忘记口令”需要邮箱/短信通道与一次性令牌，未做，也未假装入口可用。
