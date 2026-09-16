# 21 — 密钥与运维端点按绑定地址 fail-fast

**What to build:** 把「dev 默认凭证什么时候算安全」这件事挂到绑定地址上：绑回环时一切照旧、并且能自证在吃默认值；一旦绑到非回环，带着仓库里的默认值就不许启动，运维端点要显式关掉或换成非默认凭证，mock 身份签发那个端点压根不注册。落实 ADR 0029。

**Blocked by:** None — can start immediately

**Status:** done（落点 `logs/acceptance-run-20260913-142932.log` 17/17，双轴审账见文末；本行由票 22 收尾时代偿补上——票 21 只落了审账没改状态位，frontier 于是把它重新摆回了可领取）

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

**Spec 轴（含收口时补跑的那一次干净检出检查）：报 10 勾，成立 10、部分成立 0、驳回 0。**

- 勾 1「起栈脚本 / 干净克隆 / 三条演示都不因本票改动而红」——**成立，两半都实跑过**。起栈与三条演示由 17/17 那份矩阵覆盖
  （`logs/acceptance-run-20260913-142932.log`）。干净克隆这一半，本账第一次落笔时只给了推理没给复跑，当场判的是"部分成立"，
  收口时补跑了：`pwsh -NoProfile -File scripts\clean_clone_check.ps1 -At D:\ShopPilot-clean-r13`，克隆源是
  `https://github.com/shing26/shoppilot.git`，拿到的那份是 `e909534`（等于当时的 `origin/main`，所以验的是推出去的那份而不是硬盘上这份），
  克隆目录里没有 `.env`，冷启动 213s / 预算 600s，三条演示的 7 条预期输出逐条命中，完整记录
  `logs/clean-clone-check-20260913-152311.log`（`clone 17s / up 89s / demo 104s`）。
  这一条恰是本票最该复跑的量：`application.yml` 那三处默认值被本票清空，裸检出能不能起来全看
  `DevDefaultsEnvironmentPostProcessor` 的兜底生不生效——实测生效。两处现场照登，不遮：起栈前本机空闲物理内存只剩 1.8 GB
  （脚本自己建议 3 GB 以上，但它判"检查通过"没拦）；上一轮遗留的克隆目录 `D:\ShopPilot-cleancheck`（那份是 `3c99739`，09-10 的）
  还占着默认路径，所以这次换 `-At` 路径跑，没有删任何东西，它按脚本文末的提示留着。
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

## Handoff notes

**关键决策**

1. **默认凭证的合法性挂在绑定地址，不挂 profile。** 回环监听时仓库默认值可用，非回环监听时三处默认值任一未覆盖就拒绝启动；安全性描述的是“谁能到达端口”，不是“用了哪个 Spring profile”。
2. **绑定回环只是必要条件，不是防线。** 反向代理从本机转发进来时源地址仍可能是 `127.0.0.1`；回环只限制直接暴露面，身份与凭证仍是另一层。
3. **“正在用哪套默认值”必须只有一个来源。** `DevDefaultsPolicy` 把回环判断、默认值兜底、mock 身份注册条件和观测面字段收在一起；两处各写一份迟早会出现“守护判 A、进程实际用 B”。
4. **非回环时 mock 身份端点不注册。** 这不是靠端点内部再判一次，而是让 bean 根本不存在；运维端点则要求显式关闭或替换默认令牌，并把失败拆成 `ops.disabled` 与 `ops.token_mismatch`。

**你需要能当场回答的三个追问**

- *Q：为什么触发器取绑定地址，而不是新增一个 `prod` profile？* A：要防的是端口是否对外可达，profile 名称本身不承载这个事实。回环上的本地演示必须保持一条命令可跑，非回环一旦穿着仓库默认凭证则必须 fail-fast；这正好由 `server.address` 描述，且不会被使用者忘切 profile 绕过。
- *Q：既然绑回环，为什么你还说它不是防线？* A：反向代理、端口转发或同机进程都能让请求以回环地址到达，绑定只减少直接攻击面。真正的边界仍是身份不可伪造、凭证非默认和运维端点显式授权，所以 README 明确写“必要条件，不是防线”。
- *Q：默认值为什么不能在后置处理器和配置类里各留一份？* A：守护规则必须判断进程真正会使用的值。两份默认值一旦漂移，就可能出现配置类放行而后置处理器认为未覆盖，或反过来把干净克隆起栈打死；单一来源让启动判断、观测字段和 mock 注册条件不可分家。

**验证记录**

票内双轴审账列出单测矩阵、17/17 门禁与 GitHub `origin` 干净克隆的复跑；关键落点为 `logs/acceptance-run-20260913-142932.log` 与 `logs/clean-clone-check-20260913-152311.log`。已知边界：运维令牌仍用普通 `String.equals`，这不属于本票防线，ADR 0029 已把回环作用域写清。
