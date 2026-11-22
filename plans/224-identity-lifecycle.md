# 票 224 契约：身份生命周期生产口径

**状态**：2026-11-22 冻结（所有者指令开票）
**判定**：本票把演示级登录升级为可运营账号生命周期；属新功能面，实现前必须先新增
ADR 0086 并 Accepted。ADR 若与本契约冲突，先停下裁决，不得用实现倒推 ADR。

**基线**：`main` @ 开票时 HEAD；实现从独立分支开始，起点 SHA 由 Implementer 记录。

## 目标

1. access + refresh 双令牌，refresh 单次使用并轮换，复用检测撤销令牌族。
2. 改密：校验当前口令，成功后撤销全部 refresh，旧口令立即失效。
3. 登录失败锁定与限流：窗口内连续失败达到阈值即锁定，锁定期间正确口令也不放行。
4. 停用账号 / 角色变更：refresh 立即拒绝，access token 最迟在短 TTL 内失效。
5. 两端前端：401 单飞刷新、只重试一次、失败只登出一次；提供改密入口。

## Allowed paths

- `shoppilot-biz-mock/src/main/java/**`（身份表、账号服务、锁定/限流、迁移）
- `shoppilot-biz-mock/src/main/resources/db/migration/**` 与 `db/migration-postgresql/**`
- `shoppilot-gateway/src/main/java/com/shoppilot/gateway/identity/**`
- `shoppilot-gateway/src/main/java/com/shoppilot/gateway/config/**`（令牌 TTL / 锁定配置）
- `shoppilot-gateway/src/main/resources/application*.yml`
- `shoppilot-tool-api/src/main/java/**`（仅跨模块身份契约 DTO）
- `frontend-shared/src/**`
- `frontend-buyer/src/**`
- `frontend-workspace/src/**`
- `scripts/verify-identity-lifecycle.mjs`（新增）
- `scripts/check-ps-syntax.ps1`（仅在新增脚本需要时）
- `docs/adr/0086-*.md`（新增）
- `docs/STATUS.md`、`README.md`、`docs/EVIDENCE.md`（只同步测试数/能力口径）
- `plans/224-identity-lifecycle.md`
- `.scratch/shoppilot-mvp/issues/224-identity-lifecycle.md`
- `.scratch/shoppilot-mvp/README.md`

## Acceptance criteria

1. 登录返回 access + refresh；refresh 只能使用一次，轮换后旧值立即失效。
2. 同一旧 refresh 第二次使用被拒，并使该令牌族全部 refresh 失效；access token 按 TTL 失效。
3. 改密要求当前口令；错误当前口令返回可读拒绝；成功后全部 refresh 失效、旧口令登录失败、
   新口令登录成功。
4. 连续失败达到阈值后账号锁定；锁定期间正确口令也不放行；窗口结束自动恢复；
   对外错误文案不区分「账号不存在」与「口令错误」。
5. 登录限流有可观测计数与审计事件；不得在日志、响应、URL 或前端持久化中写 refresh token 或明文口令。
6. 停用账号 / 角色变更后 refresh 立即拒绝；access token 最迟在 TTL 内失效；
   `/auth/me` 与 `capabilities` 按服务端最新状态返回。
7. 买家与坐席前端在 access token 过期时只发起一次刷新；刷新成功重试原请求一次；
   刷新失败只登出一次，不出现 401 循环。
8. 两端提供可用的改密入口；错误与成功文案可读，不泄露内部账号名。
9. 既有 demo-login、ops token、221 的 403 能力语义与退款/工单路径不回归。
10. 新增活体脚本覆盖登录 → 刷新 → 复用拒绝 → 改密 → 旧凭证失效 → 新口令登录 →
    锁定 → 窗口恢复；证据绑定 HEAD SHA 与 `git status --short`。
11. 全仓 `mvnw verify`、两端 typecheck/vitest/build、文档真值检查全绿。

## Verification commands

```powershell
.\mvnw.cmd -B -ntp -pl shoppilot-biz-mock -am test
.\mvnw.cmd -B -ntp -pl shoppilot-gateway -am test
.\mvnw.cmd -B -ntp verify
cd frontend-buyer; npm run typecheck; npm test; npm run build
cd ..\frontend-workspace; npm run typecheck; npm test; npm run build
cd ..
node scripts/verify-identity-lifecycle.mjs
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
python scripts/gen_status.py --check
python scripts/verify_doc_truth.py
git diff --check
git status --short
```

## Forbidden

- 不做 SSO、MFA、邮箱找回、外部 IdP、全套 RBAC 重做或默认口令。
- 不改退款/工单/支付业务规则，不改 221 的 `capabilities` 与 403 契约。
- 不删除既有登录/演示入口，不跳过既有认证测试，不降低断言强度。
- 不把 refresh token 写进 localStorage、URL、日志或错误体。
- 不改 gold、阈值、指标分母来让测试变绿。
