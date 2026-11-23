# 票 229：启动器环境继承修复（含 U2 判定）

Round: 59（缺陷修复；按 ADR 0031）　
ADR: 0031（回归修复）、0029（凭证与默认值纪律）　
Status: ready-for-agent（2026-11-24 所有者指令开票；契约已冻结）　Claimed by: 待领取　Blocked by: 无

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

（实现与独立 Verifier 完成后补：关键决策、验证落点、三个现场追问）
