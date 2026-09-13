# 21 — 密钥与运维端点按绑定地址 fail-fast

**What to build:** 把「dev 默认凭证什么时候算安全」这件事挂到绑定地址上：绑回环时一切照旧、并且能自证在吃默认值；一旦绑到非回环，带着仓库里的默认值就不许启动，运维端点要显式关掉或换成非默认凭证，mock 身份签发那个端点压根不注册。落实 ADR 0029。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

**Verify:** 单测面零额度断言（回环与非回环两侧各一组）-> 回环上起栈与三条演示一字不改地跑通 -> 17 步门禁全绿且未改任何一步判据 -> 零额度读数为 0。

- [x] 回环绑定时启动成功，三处默认值仍然可用：起栈脚本、干净克隆、三条演示都不因本票改动而红
- [x] 回环绑定时启动打 WARN，且观测面上能读到「当前是否在用 dev 默认值」的自述字段，取值由单测断
- [x] 非回环绑定时，三处默认值任一未被覆盖即启动失败，失败信息点名是哪一处（三处各一条用例）
- [x] 非回环绑定时，运维端点未显式关闭且未换成非默认凭证，同样启动失败
- [x] 非回环绑定时 mock 身份签发端点不注册；回环下它仍然可用（后半个是防止演示与门禁被顺手打死的保险）
- [x] 运维令牌的失败原因拆成「开关关闭」与「令牌不匹配」两个 code，403 可辨——这半条是票 25 的第一块砖
- [x] 页面不再预填运维令牌
- [x] README 与问答库里凡以「身份不可伪造」覆盖「身份领取」的措辞，按 `CONTEXT.md` 那两个新词收窄；并明写绑定回环只是必要条件、不是防线（反向代理场景）
- [x] 全程零模型额度：日额度计数在本票前后差值为 0
- [x] 审计仍判得出「本轮未碰判据、阈值、gold」，项数保持 95

## 收尾双轴审查（fixed point = `ba1fb31`，落点 `4c4b219`）

审的是 `ba1fb31..HEAD` 五笔（`da421d0` 实现 / `7144b9f` 门禁抓出的两处连带 / `5462899` 截图产物 / `893046b` 落点与常数换代 / `4c4b219` E5 换代）。
本环境没有并行 sub-agent 工具，Standards 与 Spec 两轴在同一会话里分头跑完，互不引用对方的结论。

**Spec 轴：报 10 勾 → 成立 9、部分成立 1、驳回 0。**

- 勾 1「起栈脚本 / 干净克隆 / 三条演示都不因本票改动而红」——**部分成立**：起栈与三条演示由 17/17 那份矩阵覆盖（`logs/acceptance-run-20260913-142932.log`）；
  `scripts/clean_clone_check.ps1` 本票**没重跑**，登记为残余风险。理由写在这儿而不是遮掉：三处凭证都不在仓库根 `.env` 里
  （那份只有 `SHOPPILOT_EMBED_MODEL` 与四个 `SHOPPILOT_LLM_*`），所以本机起栈与干净克隆走的是同一条 EPP 兜底路径；
  克隆独有的风险面是「有文件没提交」，而本票唯一新增的非源码文件 `META-INF/spring.factories` 已随 `da421d0` 入库，
  且 `HEAD == origin/main == 4c4b219`、`git status --porcelain` 空。完整克隆检查按历轮规矩放在轮次收口那一次跑。
- 其余九条逐条对上证据：勾 2 由 `logs/gateway-local.out` 14:25:24 那行 WARN 与 `/ops/circuit` 的 `bindLoopback`/`devDefaultsInUse` 对上；
  勾 3、4 由 `onlyMissingJwtSecretIsOnlyThatBlocker`、`onlyMissingInternalTokenIsOnlyThatBlocker`、`opsBlockedOnlyWhenEnabledAndDefault`、`externalBindWithAllDefaultsIsBlocked` 钉住；
  勾 5 由 `MockIdentityConditionTest` 的绑定地址矩阵钉住，回环那半边由三条演示全绿兜住；勾 6 由 `OpsAccessTest` 五条钉住；
  勾 7 由 `ServedConsoleHidesDevDefaultsTest` 两条 + `docs/console.png` 换图钉住；勾 9 由审计 A3（活体 `tokensUsedToday=0`）与 A3b（离线反证）双向钉住；
  勾 10 由 B7「禁面零命中」与 `PASS 95 FAIL 0 SKIP 0` 钉住。

**Standards 轴：报 5（含 1 条正面）→ 成立待办 2、接受 2、驳回 1。**

- S5 [P2] ops 403 的响应体从 `{"error":...}` 换成 `{"code":...,"message":...}` 是一次对外契约变更，而**没有任何测试钉住这个 body**：
  `OpsAccessTest` 测的是枚举自己，`/ops/stats` 那次 403 的报文是一时活体读数，不能长期引用。形状归票 25 的 advice 管，
  已把「补一条 HTTP 层的 ops 403 body 用例」写进票 25 的承接段。（成立，转票 25）
- S2 [P3] 「非回环还穿着默认值」这句报错在 `DevDefaultsEnvironmentPostProcessor` 与 `DevDefaultsConfiguration` 各写一份、措辞不同。
  第二道不能删（它防的是绕过 EPP 的启动方式），但该收成同一个句子工厂。同样归票 25 的错误出口收敛。（成立，转票 25）
- S1 [P3] `OpsController.java` 有两行 import 在 diff 里原样重出：那两行原本是 CRLF 混在一个 LF 文件里（改前 2 CRLF / 334 LF），
  本票把它们归齐成 LF。净效果是消掉混合行尾，代价是 2 行假改动；该文件不在 `verify_eval_judge.py` 的 EOL 基线表里，没有判据依赖它。（接受）
- S4 [正面] 六处各写一遍的 403 收敛成 `opsAccess` + `denied`，并把「开关关着」与「令牌错了」从装不下的布尔拆成三值枚举。
- S3 [P3] `OpsAccess.evaluate` 用 `String.equals` 比令牌，非常数时间。这是改动前就有的写法，且 ADR 0029 已明写回环只是必要条件；
  在本轮口径（面试作品、不投产）下它不是一条能被证伪的防线。（驳回为本票范围外，不因此改判据）

**Verify 行那句「17 步门禁全绿且未改任何一步判据」成立，但有一处非判据的门禁自身改动必须照登**：
`scripts/verify-console.mjs` 加了一步「谁提供运维凭证」的前置（代填输入框），15 条断言一字未动；
ADR 0029 里原先那句「`scripts/` 下的验证脚本一行不改」是错的，已按实测改口。
另按历轮规矩，落点之前有四次没拿全绿，全部照登在 README 票 21 落点段（含一次作废矩阵：
`logs/acceptance-run-20260913-134050.log`，成因是我把门禁接在管道后面导致子进程脱离、`plan` 被记成「PASS 0s」）。
