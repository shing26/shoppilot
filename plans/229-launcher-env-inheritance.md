# 票 229 契约：启动器环境继承修复（含 U2 判定）

- Round: 59
- 类型: 缺陷修复（回归 / 事实错误；ADR 0031 不受冻结限制）
- 开票依据: 所有者 2026-11-24 指令「先开 lib-launch 票（含 U2）」；
  票 228 独立 Verifier 确认的票外 P1；`registered-debt` U2 疑似同源
- ADR: 0031（回归修复不受冻结限制）、0029（凭证与默认值纪律）
- 冻结时间: 2026-11-24
- Implementer: 待领取（独立分支 `codex/229-launcher-env-inheritance`）
- Verifier: 独立 subagent；不得与实现方同会话；命令由 Verifier 亲跑

## 问题

1. `Start-ShoppilotService` 用 WMI `Win32_Process.Create` 脱离启动，子进程**不继承**
   调用方进程环境。
2. `Get-ShoppilotDotEnv` 只返回「调用方进程里不存在的键」：调用方已有同名变量时
   整键跳过（空串例外）。
3. 两者叠加的净效果：调用方已导出的 `SHOPPILOT_*` **既没进 `.env` 注入表，也没进
   WMI 子进程**，服务拿到代码默认值。

已知影响：

- **已实测（票 228）**：从已导出整份 `.env` 的进程重启网关，内部令牌退回
  `dev-internal-token-change-me`，登录 401、出站 allowlist 失效。
  证据：Verifier 报告 `verifier-228-report.md` 第 5 节；`logs/run-gateway-default.cmd`
  含 `.env` 键但不含调用方已导出的 `SHOPPILOT_IDENTITY_DEMO_PASSWORD`。
- **代码面第二处**：`up.ps1` [7/7] 给网关设置 `SHOPPILOT_EMBEDDING_SIDECAR_URL`，
  该值只存在于调用方进程，WMI 子进程收不到 → sidecar 形同未接，且日志不报错。
- **疑似第三处**：`registered-debt` U2（`up.ps1` 链路下 biz-mock 拿不到
  `SHOPPILOT_IDENTITY_DEMO_PASSWORD`）。本票必须给出可复现判定，不得直接宣称同源。

## 目标

- 启动器把**最终生效环境**写进 `run-<name>.cmd`，不再依赖子进程继承。
- 只传递服务需要的命名空间：`SHOPPILOT_*` 与显式白名单
  （`SPRING_PROFILES_ACTIVE`、`JAVA_HOME`、`MAVEN_OPTS`）；
  不把整个进程环境（含无关密钥）落盘。
- 覆盖优先级保持不变，并写进实现注释：
  **脚本参数 > 调用方已导出（非空）> `.env` > 代码默认**。
- 新增确定性回归脚本，不依赖网络、容器或模型。

## Allowed paths

- `plans/229-launcher-env-inheritance.md`
- `.scratch/shoppilot-mvp/issues/229-launcher-env-inheritance.md`
- `.scratch/shoppilot-mvp/README.md`
- `.scratch/shoppilot-mvp/registered-debt.md`
- `docs/EVIDENCE.md`
- `scripts/lib-launch.ps1`
- `scripts/start-gateway.ps1`
- `scripts/start-bizmock.ps1`
- `scripts/start-ticket.ps1`
- `scripts/up.ps1`
- `scripts/watchdog.ps1`（仅当它启动服务且需要同一合并口径）
- `scripts/verify-launcher-env.ps1`（新建）
- `docs/CODE_MAP.md`（仅当启动链路描述需要同步）

## Acceptance criteria

- **AC1 环境合并**：新增 `Get-ShoppilotLaunchEnvironment`（或等价函数），
  输入 `.env` + 调用方环境 + 显式覆盖，返回最终环境；优先级为
  显式覆盖 > 调用方已导出（非空）> `.env`。空串仍视为未设置。
- **AC2 只传白名单**：只有 `SHOPPILOT_*` 与白名单键进入 `run-*.cmd`。
  负例断言：`AWS_SECRET_ACCESS_KEY`、`GITHUB_TOKEN`、`OPENAI_API_KEY` 等
  非本仓命名空间变量**不得**出现在生成文件里。
- **AC3 三服务生效**：gateway / biz-mock / ticket 生成的 launcher 均包含合并结果；
  调用方导出 `SHOPPILOT_INTERNAL_TOKEN=<探针值>` 时，cmd 里必须是探针值；
  未导出时必须是 `.env` 值。
- **AC4 sidecar 接线**：调用方导出 `SHOPPILOT_EMBEDDING_SIDECAR_URL` 时，
  gateway launcher 必须含该键（确定性断言即可，不要求真 sidecar 在线）。
- **AC5 U2 判定**：用同一机制复现或排除 `up.ps1` 链路 biz-mock 丢演示口令。
  若复现，修复并关闭 U2；若不能复现，保留 U2 并在 `registered-debt` 写明
  已排除项、证据与下一次触发条件。**不得把“未复现”写成“已修”。**
- **AC6 不回归**：`pwsh -NoProfile -File scripts/check-ps-syntax.ps1`
  `files=37 errors=0`（新增脚本后文件数可增加）；`Get-ShoppilotDotEnv` 的既有
  调用方语义不变，`cleanout-live.ps1`、`run-dev-eval.ps1`、`verify-role-menu.ps1`
  等读取方不受影响。
- **AC7 活体证据**：在调用方导出与 `.env` **不同**的 `SHOPPILOT_INTERNAL_TOKEN`
  条件下重启网关，断言登录 200 且 `logs/run-gateway-default.cmd` 中该键为调用方值；
  再用未导出的干净 shell 重启，断言回落到 `.env` 值。两次都不得改动 `.env`。
- **AC8 文档与仓库卫生**：票面、tracker、EVIDENCE、
  `registered-debt`（U2/U4/U5 记账）同步；`gen_status --check`、
  `verify_doc_truth`、`check_doc_links`、`git diff --check` 全绿；
  `git status --short` 无本机日志或临时产物。

## Verification commands

静态：

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

活体（AC7；三服务可复用已起栈，缺前置则登记边界，不得声称 PASS）：

```powershell
# 1) 调用方导出探针令牌（不写 .env）
$env:SHOPPILOT_INTERNAL_TOKEN = "<探针值>"
pwsh -NoProfile -File scripts/start-gateway.ps1
# 2) 断言 /auth/login 200，且 logs/run-gateway-default.cmd 中令牌为探针值
# 3) 开干净 shell（不导出 SHOPPILOT_*）重启，断言回落到 .env 值
```

## Forbidden

- 不改 Java / Vue / 生产配置 / 数据库 / API / gold / 阈值 / 覆盖率门槛。
- 不把整个进程环境写进 `run-*.cmd`；不新增无关密钥落盘面。
- 不改变 `.env` 文件格式、键名或 `Get-ShoppilotDotEnv` 的既有调用方语义。
- 不删除、跳过或弱化既有断言；不跳过 PowerShell 语法检查。
- 不把 U2 写成“已修”而没有复现证据；不把“未复现”当成通过。

## 现场追问

1. `run-*.cmd` 已包含 `.env` 密钥（现状），本次只扩大命名空间范围；
   若要求密钥不落盘，需要改成管道/stdin 注入，属独立安全票。
2. 多实例/多 profile 同时启动时，`run-<name>.cmd` 按 profile 后缀区分；
   若要求同机并行多租户注入不同密钥，需要独立的实例级 env 文件，另票。
3. U2 若最终无法复现，保留“未达成”并把下一次触发条件写成
   「`up.ps1` 链路再次出现演示账号未播种」；不因此关闭台账。
