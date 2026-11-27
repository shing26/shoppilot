# 票 231 契约：验收工装收口（R16–R20）

- Round: 61
- 类型: 验收工装缺陷修复（回归 / 事实错误；ADR 0031 不受 v1.0.0 冻结限制）
- 开票依据: 所有者 2026-11-26 指令「验收工装收口（R16–R20）」
- ADR: 0031（回归修复）、0029（凭证默认值纪律）、0042（显式转人工优先）、
  0087（出站 SSRF 防护）；不新增 ADR
- 冻结时间: 2026-11-26
- Implementer: 独立分支 `codex/231-acceptance-tooling-closeout`
- Verifier: 独立 subagent；不得与实现方同会话；命令由 Verifier 亲跑

## 问题（开票时事实）

round60 的 29 步 full 档留下五条**工装漂移**，全部已登记在
`.scratch/shoppilot-mvp/registered-debt.md` 的 R16–R20：

1. **R20（P1）多版本 jar 选旧包**：`start-*.ps1` 与 `up.ps1::Find-FatJars`
   用未排序的 `Get-ChildItem | Select-Object -First 1`；`target/` 同时存在
   `0.1.0-SNAPSHOT` 与 `1.1.0-SNAPSHOT` 时活体栈加载 **0.1.0**。
2. **R16 凭证双源**：`verify-*` 内置 dev 默认值，`.env` 由
   `bootstrap_env.py --force` 生成 32 字符随机值；启动器只把 `.env` 注入服务，
   不注入验收脚本子进程。fresh clone 按 README 跑 full 会在鉴权步骤整片红。
3. **R19 出站回环被 SSRF 策略拒绝**：`verify-outbound.mjs` 自起
   `127.0.0.1` 回声端点，但验收栈未声明
   `SHOPPILOT_OUTBOUND_ALLOWLIST=127.0.0.1`；门禁 5/7，手动导出后 10/10。
4. **R17 circuit 期望过期**：票 221 后 `/ops/**` 统一要求坐席/管理员或运维凭证；
   调试台 `refreshOps()` 用买家令牌读 circuit，坐席登录后仍读不到；
   `verify-console.mjs` 仍断言 `circuitState=live`，console 42/45。
5. **R18 EMO-ESC-07 期望过期**：票 163 把「找真人」纳入 T0 升级词；
   用例「我要找真人领导谈」按 ADR 0042 合法返回 `USER_REQUESTED`，
   脚本与 `eval/cases-part4-emotion.jsonl` 仍期望 `EMOTION_ESCALATION`，
   emotion 28/29。

## 目标

让 fresh clone 只靠 `.env` 与 `run-acceptance.ps1` 就能复跑 29 步 full 档，
且五条红不再由工装自身制造。**不新增功能、不放宽任何安全闸**。

## Owner rulings（2026-11-26，随本票签收追认）

- **R17**：调试台在坐席登录后，circuit 用**坐席令牌**读取；
  `/ops/**` 的角色闸**不放开**，买家仍返回 `role.denied`。
  理由：circuit 是运维读数，页面已有坐席登录入口；把 circuit 放开给任意登录用户
  会削弱票 221 的默认拒绝面。
- **R18**：显式转人工优先（ADR 0042 + 票 163）。EMO-ESC-07 的查询文本含
  「找真人」，按既有契约就是 `USER_REQUESTED`；修正脚本与 eval 期望，
  **不新增、不删除用例，不改分母**。
- **R19**：只在验收 harness 启动栈时声明
  `SHOPPILOT_OUTBOUND_ALLOWLIST=127.0.0.1`；生产/默认启动仍拒绝回环。
- **R16**：`.env` 是验收脚本凭证的唯一来源；进程环境非空值覆盖 `.env`；
  脚本不再内置 `dev-ops-token` / `dev-internal-token-change-me` 等默认值。
- **R20**：启动器从根 `pom.xml` 读项目版本，精确匹配
  `<module>-<version>.jar`；当前版本 jar 缺失时走既有 `mvn spring-boot:run`
  回退，**绝不回退到其它版本 jar**。

## Allowed paths

- `plans/231-acceptance-tooling-closeout.md`
- `.scratch/shoppilot-mvp/issues/231-acceptance-tooling-closeout.md`
- `.scratch/shoppilot-mvp/README.md`
- `.scratch/shoppilot-mvp/registered-debt.md`
- `docs/EVIDENCE.md`
- `scripts/lib-launch.ps1`
- `scripts/start-gateway.ps1`
- `scripts/start-bizmock.ps1`
- `scripts/start-ticket.ps1`
- `scripts/up.ps1`
- `scripts/run-acceptance.ps1`
- `scripts/verify-launcher-env.ps1`
- `scripts/ingest.ps1`（契约修订 6：Verifier P1，入库 JVM 同样按版本取包）
- `scripts/demo.ps1`
- `scripts/verify-plan-actions.ps1`
- `scripts/verify-action-loop.ps1`
- `scripts/verify-fallback.ps1`
- `scripts/verify-polarity.ps1`
- `scripts/verify-idempotency.ps1`
- `scripts/verify-refund-approval.ps1`
- `scripts/verify-emotion.ps1`
- `scripts/verify-feedback.ps1`
- `scripts/verify-channel.ps1`
- `scripts/verify-hit-zero-llm.ps1`
- `scripts/verify-console.mjs`
- `scripts/verify-workspace.mjs`
- `scripts/verify-outbound.mjs`
- `scripts/verify-buyer.mjs`
- `scripts/verify_l2_filters.py`
- `scripts/run_tool_eval.py`
- `scripts/envfile.py`（新建，Python 侧 `.env` 读取）
- `scripts/lib-dotenv.mjs`（新建，Node 侧 `.env` 读取）
- `scripts/verify_eval_judge.py`（契约修订 1：仅把 `envfile.py` 加进干净副本清单；
  判据与断言一字不改）
- `shoppilot-gateway/src/main/resources/static/console.html`
  （仅 R17 的身份选择与坐席登录后刷新，不改页面结构/样式）
- `eval/cases-part4-emotion.jsonl`（仅 EMO-ESC-07 的 `expect` 与 `intent`，
  条目数不变）

## Acceptance criteria

- **AC1（R20）**：三个 `start-*.ps1` 与 `up.ps1::Find-FatJars` 不再按通配取第一个
  jar；均按根 POM 版本精确解析。受控回归：同一临时目录同时放
  `0.1.0-SNAPSHOT` 与当前版本 jar 时，只返回当前版本；只有旧 jar 时返回
  `$null`，启动器走 `mvn spring-boot:run` 回退，不启动旧包。
  （契约修订 6：Verifier P1 发现 `scripts/ingest.ps1` 仍按通配选包，full 档入库
  会加载旧 jar；同一 R20 判据补到该脚本，静态检查 +2，判据不放宽。）
- **AC2（R16）**：29 步矩阵用到的 `verify-*` / `demo` 脚本从进程环境（非空）
  或仓库 `.env` 取 `SHOPPILOT_OPS_TOKEN` / `SHOPPILOT_INTERNAL_TOKEN` /
  `SHOPPILOT_IDENTITY_DEMO_PASSWORD`；源码中不再有这三个键的 dev 默认值。
  受控回归：临时 `.env` 有值且进程环境为空 → 返回文件值；进程环境有值 →
  覆盖文件值；两边都缺 → 脚本明确报缺并 exit 2，不发请求。
- **AC3（R19）**：`run-acceptance.ps1 -Tier full` 启动栈时合并
  `SHOPPILOT_OUTBOUND_ALLOWLIST=127.0.0.1`（保留 `.env` 已有条目，去重）；
  该值只作用于本次验收栈，不写进 `application.yml` 或默认 `.env.example`。
  活体：fresh 栈上 `node scripts/verify-outbound.mjs` **10/10**。
- **AC4（R17）**：调试台 `refreshOps()` 在 `state.staffToken` 存在时用它读
  circuit，否则用买家令牌；坐席登录成功后触发一次刷新。`/ops/**` 403 契约不变。
  活体：`node scripts/verify-console.mjs` **45/45**，含空/错 ops token 三条负向探针。
- **AC5（R18）**：`verify-emotion.ps1` 把 EMO-ESC-07 归入显式转人工组
  （期望 `USER_REQUESTED`、无 `high`）；`eval/cases-part4-emotion.jsonl`
  同步为 `escalate:false, reason:USER_REQUESTED`。活体：`verify-emotion.ps1`
  **29/29**。
- **AC6（全量）**：最终 HEAD 跑 `run-acceptance.ps1 -Tier full`，29 行逐条留痕；
  目标 **27 绿 / 2 红**（剩余红只能是既有的 `feedback` 与 `plansteps`）；
  若 `polarity` 仍按 U11 间歇红，照登并给出日志，不写成 PASS。
- **AC7（静态/构建）**：
  `pwsh -NoProfile -File scripts/check-ps-syntax.ps1`、
  `pwsh -NoProfile -File scripts/verify-launcher-env.ps1`、
  `.\mvnw.cmd -B -ntp verify`、
  `python scripts/gen_status.py --check`、
  `python scripts/verify_doc_truth.py`、
  `python scripts/check_doc_links.py`、
  `git diff --check`、`git status --short` 全绿。
- **AC8（流程）**：独立 Verifier 亲跑 AC1–AC7，P0/P1 清零；所有者书面签收后
  合并 `main`，`ci-subset` 对合并后 HEAD success，再删分支。

## Forbidden

- 不改 Java 业务代码、Vue 源码、`application.yml`、数据库 schema、API 契约、
  gold 用例数目、阈值、覆盖率门槛。
- 不放宽 `/ops/**` 的 403、SSRF 回环拒绝、或任何认证/授权判据。
- 不删除、跳过、弱化测试或断言；不把 SKIP/环境红写成 PASS。
- 不提交 `.env`、`logs/`、截图或临时产物。
- 不在本票处理 `feedback` / `plansteps` 两条既有红，也不处理 nightly
  `Testcontainers integration` 的独立问题。

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

受控回归（0 token，Verifier 亲跑）：

1. 临时目录模拟 `target/` 双版本 jar，断言只选当前版本、只有旧版返回 `$null`。
2. 临时 `.env` + 空进程环境 / 非空进程环境，断言凭证优先级。
3. 把 `SHOPPILOT_OUTBOUND_ALLOWLIST` 从验收环境移除，确认默认网关仍拒绝回环
   （静态断言 + 既有 `EgressPolicy` 测试）。

## 契约修订

1. **2026-11-26（实现期）**：`scripts/verify_eval_judge.py` 加入 Allowed paths。
   原因：`run_tool_eval.py` 的 `.env` 读取下沉到新模块 `scripts/envfile.py` 后，
   该判据脚本的「干净副本」对照组只复制 `run_tool_eval.py`，会在 import 阶段
   报 `ModuleNotFoundError: envfile`，随后四条变异对照全部失效。修法只把
   `envfile.py` 加进副本清单；判据、断言与阈值零改动。

2. **2026-11-26（全量矩阵现场，R19 同族）**：首轮 29 步矩阵（`25 绿 / 8 红`）
   暴露三处**工装自身制造的红**，均只改 harness / 调试台读取方式，不碰判据：
   - `run-acceptance.ps1` 在跑步骤前固定 `SHOPPILOT_EMBEDDING_SIDECAR_URL`
     （`.env` 非空值或 `:8110/health` 探测）。原因：`up.ps1` 推导出的值只活在
     `up.ps1` 进程里；`plan` / `console` 步骤中途用 `start-gateway.ps1` 重启网关时
     丢失该值，网关退回 Ollama，单条向量化实测 9.5 s 超时 → `degraded=true`
     → `WriteBackPolicy` 拒绝写回 → 同步端点永不 L1 命中。P1 现象：
     `plan` 尾部三条断言、`console` 的 cache-hit 格、`polarity`、`isolation`。
   - `up.ps1` 在 `[5/7]` 入库前等 `http://127.0.0.1:8091/actuator/health/readiness`
     （300 s）。原因：`start-bizmock.ps1` 只拉起进程不等 seed；入库经 8091 拉
     人工核准的知识修订（ADR 0078），并行起跑会在 seed 窗口直接 exit 1。
   - `console.html::loadTenants()` 在既无坐席令牌也无运维令牌时直接渲染内置
     店铺清单，坐席登录成功后补拉 `/ops/tenants`。原因：票 221 后该端点对买家
     令牌稳定 403，开页即留下一条浏览器红字，污染「页面无失败请求」；`/ops/**`
     的 403 契约与判据未动。

3. **2026-11-26（AC6 读数）**：最终 HEAD `-SkipBuild` 全量矩阵
   `logs/acceptance-run-20261126-043649.log` = **25 绿 / 4 红 / 3 SKIP**
   （`stack` 207 s、`plan` 163 s 均绿）。四条红：
   `refund`、`console`（同一退款模型路径）、`feedback`（隐式信号③，契约内既有红）、
   `plansteps`（local 3B，契约内既有红）。`refund` / `console` 的归因见票面
   Handoff notes「现场追问」，属 local 3B 动作工具调用稳定性，不是本轮工装回归。
   本修订只登记读数与归因，**不把红写成 PASS，也不降低任何判据**。

4. **2026-11-27（Owner ruling，AC6 双读数）**：
   - **local 档**：所有者接受 **25 绿 / 4 红**，`refund` / `console` 归入
     `registered-debt.md` **U12**（本地 3B 动作工具调用 1/5 + 回读措辞脆性），
     `feedback` / `plansteps` 仍为契约内既有红；不写成 PASS。
   - **dev 档补充**：追加一次 `-Profile dev`（qwen-plus）的 29 步全量矩阵，
     作为 AC6 的第二读数。云 token 由所有者授权；本机 `SHOPPILOT_LLM_DAILY_TOKEN_BUDGET`
     只在**本次验收进程环境**临时提到 600000，不改 `.env`、不改
     `application.yml` 默认值。该读数的判据与 local 档逐字相同。
   - **后续票**：另开票处理「动作意图未派发写工具却声称已办理」的收尾检查
     （ADR 0008 忠实性；触及 `AgentStateMachine` / 编排层，属产品行为改动，
     不在本票实现）。

5. **2026-11-27（dev 矩阵现场：一个工装缺陷 + 一条不可达结论）**：
   - **缺陷（已修）**：`verify-plan-actions.ps1` 的两处网关重启硬编码
     `-Profile local`，`run-acceptance.ps1` 也没把 `-Profile` 传进去。结果
     `-Tier full -Profile dev` 在 plan 步之后整轮悄悄退回 local（3B）：
     首轮 dev 矩阵 `logs/acceptance-run-20261127-034838.log` 的后半段
     `console` / `eval` 读数实际是 local，**不能当 dev 证据**。修法：
     `verify-plan-actions.ps1` 新增 `-Profile` 参数（默认 local，保持既有调用），
     `run-acceptance.ps1` 的 plan 步透传 `$Profile`。判据零改动。
   - **结论（27/2 在现有判据下不可达）**：plan 步的 ticket-13 连打断言依赖
     **local 档的请求延迟**。买家桶是 5 QPS；dev（qwen-plus）串行 14 发约
     1 s/发，永远打不出 429；local（3B + L1 命中）约 0.2 s/发才会超桶。
     同一块还有第二条：dev 的情绪门第二层每个请求多一次 LLM 调用（无独立计数），
     使「准入请求模型调用为 0」也无法成立。两条都在同一个断言块里，
     改它等于改判据方法，本票不做；登记为 **R21**。
   - **环境边界**：修完 profile 透传后两次重跑 dev 矩阵均在 `stack` 步失败
     （biz-mock 进程被主机内存压力杀死 / ingest 原生 OOM），未产生可判读数。
     所有者已裁定 AC6 以 local 的 **25/4** 为准；dev 矩阵不作为 AC6 的替代读数。

6. **2026-11-27（Verifier P1 整改：入库 launcher 也按版本取包）**：
   - 独立 Verifier 受控复现：`scripts/ingest.ps1` 仍用
     `Get-ChildItem ... -Filter "shoppilot-gateway-*.jar" | Select-Object -First 1`，
     双版本并存时返回 `0.1.0-SNAPSHOT`；而 `up.ps1` 的 full 档 stack 步会调用它。
     R20 在三个 `start-*.ps1` 与 `Find-FatJars` 已关闭，入库 JVM 仍可加载旧包，
     属同族契约缺口。
   - 整改：`ingest.ps1` 改用共享 `Get-ShoppilotFatJar`；`verify-launcher-env.ps1`
     增加「按根 POM 版本精确取 jar」「不再按通配符取 jar」两条静态断言
     （70 → 72）。不删除、不跳过、不弱化任何既有断言。
   - 该文件加入 Allowed paths；AC1 原文不改，只补注覆盖范围。整改后交回同一
     Verifier 复验 AC1–AC7。
