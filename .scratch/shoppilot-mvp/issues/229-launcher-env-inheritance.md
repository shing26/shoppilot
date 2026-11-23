# 票 229：启动器环境继承修复（含 U2 判定）

Round: 59（缺陷修复；按 ADR 0031）　
ADR: 0031（回归修复）、0029（凭证与默认值纪律）　
Status: implemented（2026-11-24；首轮 Verifier P1 已整改，待独立 Verifier 复验）　Claimed by: root　Blocked by: 无

契约：[plans/229-launcher-env-inheritance.md](../../plans/229-launcher-env-inheritance.md)（2026-11-24 冻结）

## What to build

1. `scripts/lib-launch.ps1` 新增最终环境合并函数：显式覆盖 > 调用方已导出（非空）
   > `.env`；`Start-ShoppilotService` 把合并结果写进 `run-<name>.cmd`，
   不再依赖 WMI 子进程继承。
2. 只传递 `SHOPPILOT_*` 与显式白名单（`SPRING_PROFILES_ACTIVE`、`JAVA_HOME`、
   `MAVEN_OPTS`），不把整个进程环境落盘。
3. gateway / biz-mock / ticket 启动脚本统一走新口径；`up.ps1` 设置的
   `SHOPPILOT_EMBEDDING_SIDECAR_URL` 必须真正到达网关。
4. 新增 `scripts/verify-launcher-env.ps1`：确定性断言合并优先级、白名单、
   敏感变量不外泄、三服务 cmd 内容与 sidecar 键。
5. 对 `registered-debt` U2 给出可复现判定并记账（复现则修，不能复现则保留）。

## 边界

- 不改 Java、Vue、生产配置、数据库、API、gold、阈值或覆盖率门槛。
- 不改变 `.env` 格式与 `Get-ShoppilotDotEnv` 的既有调用方语义。
- 不把无关密钥写进 `run-*.cmd`；现状已含 `.env` 本仓密钥，本次不扩大落盘面。
- U2 不得无证据宣称已修；AC5 允许“保留未达成 + 写清触发条件”。

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

活体（AC7，三服务可复用已起栈；缺前置登记边界，不得声称 PASS）：

```powershell
$env:SHOPPILOT_INTERNAL_TOKEN = "<探针值>"
pwsh -NoProfile -File scripts/start-gateway.ps1
# 断言 /auth/login 200 + run-gateway-default.cmd 中令牌为探针值
# 再开干净 shell 重启，断言回落到 .env 值
```

## Handoff notes

**关键决策**

- `scripts/lib-launch.ps1` 新增两个纯函数：`New-ShoppilotRunnerScript`（生成
  `run-*.cmd` 内容）与 `Get-ShoppilotLaunchEnvironment`（合并最终环境）。
  优先级固定为 **显式覆盖 > 调用方已导出（非空）> `.env`**，空串仍视为未设置。
- 只收 `SHOPPILOT_*` 与白名单 `SPRING_PROFILES_ACTIVE` / `JAVA_HOME` /
  `MAVEN_OPTS` / `SHOPPILOT_PWSH`；不把整台机器的进程环境写进启动脚本。
- gateway / biz-mock / ticket 三个启动脚本统一改走合并函数；
  `mvn spring-boot:run` 回退路径也把 `JAVA_HOME`/`MAVEN_OPTS` 写进合并环境
  （原先 WMI 子进程同样拿不到，属于同源隐患）。
- `up.ps1` 不需要改：它设置的 `SHOPPILOT_EMBEDDING_SIDECAR_URL` 现在会被合并函数
  从调用方环境读走并写进 `run-gateway-default.cmd`。

**验证落点**

- 静态：`pwsh -NoProfile -File scripts/check-ps-syntax.ps1` →
  `files=38 errors=0`；新增 `scripts/verify-launcher-env.ps1` → **24/24 PASS**
  （合并优先级、空串回落、sidecar 键、敏感变量不外泄、三服务接入、mvn 回退
  统一函数与“回退分支不再手写下标覆盖”）。
- 首轮独立 Verifier 判 FAIL 的 P1：`start-ticket.ps1` 回退分支在 `$overrides`
  未初始化时写索引，缺 jar 时以 `Cannot index into a null array.` 退出。
  整改为 `Get-ShoppilotMvnFallbackEnvironment` 纯函数，三个启动脚本共用；
  按 Verifier 原始复现路径（临时目录、空 `shoppilot-ticket/target`）实跑
  `start-ticket.ps1` → **退出码 0**，生成 `run-ticket.cmd` 且含
  `JAVA_HOME` / `MAVEN_OPTS` / `spring-boot:run`。
- 全量：`.\mvnw.cmd -B -ntp verify` → **BUILD SUCCESS**（3:09），
  `878 tests / 144 suites` 未变（surefire 目录 145 XML / 879 条含非默认
  `LlmCassetteRecorder`，与票 227 同口径）；文档三检查与 `git diff --check` 全绿。
- 活体 AC7（三服务）：调用方导出探针 `SHOPPILOT_INTERNAL_TOKEN` 后重启
  ticket/biz-mock/gateway，三份 `logs/run-*.cmd` 均含探针值，`/auth/login` **200**
  （首次 502 是 biz-mock 19.6s 尚未就绪，重试即 200，不是缺陷）。
- 回落场景：干净 shell（未导出任何 `SHOPPILOT_*`）重启三服务，三份 launcher 均含
  `.env` 值，`/auth/login` **200**；`.env` SHA-256 前后一致
  `8E0AEB60…D233`，未改动。
- U2 判定：导出**过期** `SHOPPILOT_IDENTITY_DEMO_PASSWORD` 重启 biz-mock，
  直连 `/api/identity/authenticate`：过期口令 **200**、`.env` 口令 **401**。
  证明环境值已真实到达子进程（修复前会整键丢失 → 不播种 → 两者都 401）。
  完整 `up.ps1` 链路未复跑（本机 Docker 29.5.3 可用，但 Ollama 服务
  `127.0.0.1:11434` 未监听；且 `up.ps1` 会经 `bootstrap_env.py --force`
  改写 `.env`，与本次验证约束冲突），**U2 保留未达成**，证据与触发条件
  写入 `registered-debt`。

**三个现场追问**

1. 调用方旧值优先于 `.env`：同一 shell 残留过期口令时会以旧值播种且**不告警**
   （本次实测 stale 200 / .env 401）。是否要加“与 `.env` 不一致”的 WARN 或拒绝启动？
   建议另票，本轮只保证投放确定性。
2. `run-*.cmd` 落盘密钥是既有形态（本次未扩大命名空间）；若要“密钥不落盘”，
   需改成 stdin/pipe 注入，属独立安全票。
3. 同机并行多实例目前只按 profile 后缀区分日志与 launcher，不隔离密钥；
   要做实例级不同密钥，需要每实例一份 env 文件，另票。
