# 票 231：验收工装收口（R16–R20）

Round: 61（验收工装缺陷修复；无新功能面）
ADR: 0031（回归修复）、0029（凭证默认值纪律）、0042（显式转人工优先）、
0087（出站 SSRF 防护）
Status: implemented（2026-11-26，AC1–AC5 + AC7 通过；AC6 读数 25 绿 / 4 红，
偏差待所有者裁定；AC8 未执行）　Claimed by: root　Blocked by: 无

契约：[plans/231-acceptance-tooling-closeout.md](../../plans/231-acceptance-tooling-closeout.md)（2026-11-26 冻结）

## What to build

1. **R20 启动器版本选择**：`start-gateway.ps1` / `start-bizmock.ps1` /
   `start-ticket.ps1` / `up.ps1::Find-FatJars` 从根 `pom.xml` 读版本，
   精确匹配 `shoppilot-<module>-<version>.jar`；当前版本缺失时走既有
   `mvn spring-boot:run` 回退，不回退到其它版本。
2. **R16 凭证单一来源**：验收脚本从进程环境（非空）或仓库 `.env` 读取
   `SHOPPILOT_OPS_TOKEN` / `SHOPPILOT_INTERNAL_TOKEN` /
   `SHOPPILOT_IDENTITY_DEMO_PASSWORD`；删除脚本内置 dev 默认值。
3. **R19 出站回环**：`run-acceptance.ps1` 启动验收栈时合并
   `SHOPPILOT_OUTBOUND_ALLOWLIST=127.0.0.1`；生产默认不变。
4. **R17 调试台 circuit**：坐席登录后 circuit 用坐席令牌读取；
   `/ops/**` 403 契约不变；`verify-console.mjs` 恢复 45/45。
5. **R18 EMO-ESC-07**：按 ADR 0042 + 票 163 归入显式转人工
   （`USER_REQUESTED`、无 high）；同步脚本与 eval 期望；emotion 29/29。
6. 全量 29 步矩阵复跑、证据登记、独立 Verifier、所有者签收。

## 边界

- 不改 Java 业务代码 / Vue / `application.yml` / DB / API / gold 条目数 /
  阈值 / 覆盖率门槛。
- 不放宽 `/ops/**` 403、SSRF 回环拒绝或任何认证授权判据。
- 不删除、跳过、弱化测试；不把 SKIP / 环境红写成 PASS。
- 不提交 `.env`、`logs/`、截图或临时产物。
- `console.html` 只改 R17 的令牌选择与刷新；`eval/cases-part4-emotion.jsonl`
  只改 EMO-ESC-07 的期望，不改条目数。

## Verify

```powershell
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
pwsh -NoProfile -File scripts/verify-launcher-env.ps1
.\mvnw.cmd -B -ntp verify
python scripts/gen_status.py --check
python scripts/verify_doc_truth.py
python scripts/check_doc_links.py
git diff --check
git status --short
```

活体：

```powershell
pwsh -NoProfile -File scripts/run-acceptance.ps1 -Tier full
```

## Handoff notes

**开票时事实（2026-11-26）**

- `main` / HEAD `4a90f43`；工作树干净；票 01-230 全部收口，frontier 为空。
- 最近全量矩阵（1.1.0 栈，HEAD `888d50e`）：
  `logs/acceptance-run-20261125-045314.log`，29 行 =
  **22 PASS + 4 FAIL + 3 SKIP**，合并口径 **25 绿 / 4 红**。
- 四条红：`console` 42/45（R17）、`emotion` 28/29（R18）、
  `feedback` 隐式信号③（既有登记）、`plansteps` `PASS 1 / FAIL 6`
  （既有登记，ADR 0043 不改判据）。
- 五条工装债已登记在 `registered-debt.md` R16–R20；本票只收这五条，
  不扩到 `feedback` / `plansteps` / nightly Testcontainers。

**关键判定**

- R17 选「调试台坐席令牌」而不是「circuit 放开给任意登录用户」：
  后者会削弱票 221 的默认拒绝面。页面已有坐席登录，读运维读数用它即可。
- R18 是判据与既有契约对齐，不是放宽：显式转人工优先由 ADR 0042 定义，
  票 163 只是把「找真人」补进词表。用例数与分母不变。
- R19 的 allowlist 只进验收 harness 的启动环境；不写进
  `application.yml` / `.env.example` 默认值。
- R16 不把 `.env` 全局导入 `run-acceptance.ps1` 进程后跑 Maven：
  演示口令会让 `IdentityDomainTest.noDemoAccountsWithoutExplicitPassword`
  变红（票 230 实测）。凭证只在脚本/验收步骤内部分辨率。

**本轮实测（2026-11-26）**

- **首轮矩阵**（`-Tier full -SkipBuild`，`logs/acceptance-run-20261126-035450.log`，
  1775 s）：29 行 = **18 绿 / 8 红 / 3 SKIP**。红：`stack`、`plan`、`refund`、
  `polarity`、`console`、`feedback`、`plansteps`、`isolation`。其中 `stack` 是
  biz-mock seed 与入库并行的老竞态；`plan`/`console`/`polarity`/`isolation`
  是网关重启后丢 `SHOPPILOT_EMBEDDING_SIDECAR_URL` 的连锁（见契约修订 2）。
- **定向复验**（修复后，同一栈、同一 HEAD）：`plan -WithRestarts` 全部通过
  （含尾部流式逐字 + 同步 L1 三条）；`console` **45/45**；`polarity` 通过；
  `isolation` **5/5**；冷启动 `up.ps1 -SkipBuild` 全栈 **158 s** 就绪
  （biz-mock readiness → 入库顺序生效，入库 111 块成功、纪元 14）。
- **最终矩阵**（`logs/acceptance-run-20261126-043649.log`，636 s，`-SkipBuild`）：
  29 行 = **22 绿 / 4 红 / 3 SKIP**，合并口径 **25 绿 / 4 红**
  （3 个 SKIP 是 stop/build/unit，其中 build/unit 由本轮 `mvnw verify` 覆盖，
  stop 由 `up.ps1` 冷启动实测覆盖）。`stack` 207 s、`plan` 163 s 均绿。
  红：`refund`、`console`、`feedback`、`plansteps`。
- **Verdict 证据链**：`refund` 与 `console` 的红是**同一条退款模型路径**。
  本地 3B（`qwen2.5:3b`，4096 ctx，prompt ≈ 3.3k tokens）对
  「订单 90001 我要申请退款，商品有质量问题」的 `applyRefund` 工具调用
  **5 次采样仅 1 次成功**（其余为散文式「我已为您发起退款申请」，无 tool 事件）；
  同一问句改用 dev（`qwen-plus`）时 `applyRefund → PENDING_APPROVAL` 一次通过，
  但回读断言要求字面含「放行 + 支付渠道」，该次答复为「退款处理中（REFUNDING）」
  而判红——**工具链正确，两处红分别是本地模型能力与回读措辞脆性**。
  `feedback` 隐式信号③与 `plansteps` 为契约内既有红，未处理。
- **未改面**：Java 业务代码 / Vue / `application.yml` / DB / API 契约 / gold
  条目数 / 阈值 / 覆盖率门槛零改动；`/ops/**` 403 与 SSRF 回环拒绝未放宽；
  没有删除、跳过或弱化任何断言。
- **工作树清洁**：矩阵/浏览器步骤会重写 `docs/console.png` 与
  `docs/loadtest-report.md`（后者是既有证据换代漂移，与本票无关），提交前已
  `git restore` 还原，未混入。`drafts/` 与
  `docs/shopify-refund-contract-mapping.md` 是并发任务的未跟踪产物，不属本票。

**现场追问（三条）**

1. **AC6 判定**：契约写死「27 绿 / 2 红」。实际 25/4，多出的两条是本地 3B
   动作工具调用不稳定 + 回读措辞脆性。请裁定：**(A)** 接受 25/4 并把这两条
   登记为既有模型方差（推荐，产品链路由 dev 档一次通过佐证）；**(B)** 用
   `-Profile dev`（qwen-plus）重跑 29 步换取 27/2，代价是云 token；**(C)**
   要求本地 3B 门禁稳定，需换更大本地模型或给 3B 加确定性动作路径（新功能面）。
2. **讲清式动作谎报**：本地 3B 会不调工具却回答「已为您发起退款申请」。
   R14 已守住「读工具失败」，但**写动作未调用**仍可被模型正文冒充完成。
   是否开回归票加一道「动作意图未派发写工具 → 不得声称已办理」的收尾检查？
3. **回读断言形态**：`refund` 第 4 步按自由文本匹配「放行 / 支付渠道」，
   模型换一种正确措辞就红。是否改为断订单视图的 `refundStatus` + 到账边界
   字段（需要 add-only 响应面），还是保留文本匹配并接受模型方差？

**AC 逐条**

- AC1（R20）PASS：`verify-launcher-env.ps1` 70/70，含双版本 jar 反证与旧包不选。
- AC2（R16）PASS：70/70 内含凭证优先级与源码无 dev 默认值断言。
- AC3（R19）PASS：`verify-outbound.mjs` 10/10。
- AC4（R17）PASS：`verify-console.mjs` 45/45。
- AC5（R18）PASS：`verify-emotion.ps1` 30/30（29 条 + 汇总）。
- AC6 **偏差**：25 绿 / 4 红，读数与归因见上；待所有者裁定。
- AC7 PASS：`check-ps-syntax` 38/0、`verify-launcher-env` 70/70、
  `mvnw verify` BUILD SUCCESS、`gen_status --check` / `verify_doc_truth` /
  `check_doc_links`、`git diff --check` / `git status --short`。
- AC8 未执行：独立 Verifier + 所有者书面签收 + 合并/CI/删分支。
