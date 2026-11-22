# 票 224：身份生命周期生产口径

Round: 56（所有者 2026-11-22 指令「按 224 身份生命周期 → 225 → 226 开票」）
ADR: 0086（实现前新增并 Accepted；开票依据 = 所有者政策覆盖）
Status: ready-for-agent　Claimed by: 无　Blocked by: 无
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

- 待实现后补；实现前先新增 ADR 0086，冻结令牌模型、锁定阈值与状态码。

**验证落点**

- 待实现后补；证据绑定 HEAD SHA 与 `git status --short`。

**三个现场追问**

1. 锁定阈值与窗口按租户策略可配置，还是先固定为一组安全默认值？
2. 改密后是否允许当前 access token 继续用到 TTL，还是立即要求重新登录？
3. 买家端是否也需要「忘记口令」入口，还是本期只做持口令改密？
