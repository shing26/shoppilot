# 133 全量矩阵首跑 + 三处工装漂移修复 + 两店交替压测参数组

**Status:** implemented

## What to build

所有者指令「把三个本地提交推上去，再做一次全量 29 步矩阵和一个『两店交替』的压测参数组来量多店命中率代价」。交付四件：

1. **推送**：`a577217..4b6af15`（round34 三个提交）推上 origin/main。
2. **全量 29 步矩阵首跑**（tier=full / profile=local）：23 绿 / 6 红，逐条归因并处置。
3. **三处工装漂移修复**（矩阵逐步露出的）：`verify-refund-approval.ps1`
   （默认口令 `agent123` → `SHOPPILOT_IDENTITY_DEMO_PASSWORD`；审计读端点 `/ops/audit`（全仓不存在）
   → biz-mock `/api/audit` + 内部令牌；PS7 `Invoke-RestMethod` 数组形状 → `ConvertFrom-Json`；
   action 名 `REFUND_REVIEW` 子串 → `REFUND_APPROVED|REJECTED`）、
   `verify-workspace.mjs`（未登录提示的 `.err` → 页面实际的 `.notice`，与买家端同款写法）。
4. **两店交替压测参数组**：`loadtest/locustfile.py` 加 `SHOPPILOT_TENANT_MODE=mixed|alternate`
   与 `SHOPPILOT_TENANTS`；`scripts/run_loadtest.py` 加 `--tenants/--tenant-mode` 并把两格写进
   CSV/环境记录；`run_experiment_suite.ps1` 新组 `storemix`（alt2 两店交替 + one 单店对照）。

## Blocked by

无。

## 口径

- **一条判据都没改**：矩阵 29 步的断言、阈值、gold 全部原样；修的是"判据打在不存在的端点/旧选择器上"。
- 矩阵步骤数不变（29），新增的是压测参数组（不进矩阵）。
- 两店交替的读数口径边界（绝对值不可比、代价在足迹不在稳态）见 `docs/EVIDENCE.md` 对应行。

## Verify

```powershell
pwsh -NoProfile -File scripts/run-acceptance.ps1                      # 全量矩阵（29 步）
pwsh -NoProfile -File scripts/run-acceptance.ps1 -Tier full -Only refund,workspace,buyer -SkipBuild
pwsh -NoProfile -File scripts/run_experiment_suite.ps1 -Only storemix -Steps 100,200,400
git diff --check; git status --short
```

## Handoff notes

- **矩阵归因（6 红）**：`workspace`/`buyer` = 调用方未导出演示口令（harness 不注入 .env，U2 同族）
  → 导出后 `buyer 9/9`、`workspace 12/12` 转绿（workspace 另修一处选择器漂移）；
  `refund` = 四处工装漂移（修完后闸门 1-7 全过、第 8 步首次真正执行并抓到票 132 的审计缺陷），
  **完整绿读数被显存阻塞**（Ollama 无法加载模型 → 登记 U9）；
  `console` = round30 的 staff-only 读面 vs 调试台无坐席登录（**产品缺口**，登记 R9 待裁定）；
  `feedback`（幂等重放计数间歇）与 `plansteps`（local 3B 不产生两步链）是已登记项，判据不动。
- **两店交替读数**：稳态两臂无差异（准入内 ≈100%）；代价在冷启动与足迹——
  **两店 26 vs 21 个 L1 key（+24%）**，机制与意图分布逐条对齐（5 条此前被平台桶共享的文本各店各存）。
- **未达成照登**：① refund 步骤受显存阻塞（U9）；② mix80 绝对值与 09-08 梯子不可比（U10：
  会话号秒级碰撞 + 三条文本被 T1 误判为动作意图，~31% 流量不进准入——与 ADR 0068 无关，A/B 差值可比）；
  ③ 压测只跑 100/200/400 三档（未跑满六档）。
- **三个现场追问**：
  1. 工装口令/端点这类"判据指着不存在的东西"，能不能在 syntax 步加一条静态检查
     （脚本里引用的 `$BaseUrl/...` 路径对网关路由表核一遍）？这轮是**跑到第 8 步**才现形的。
  2. `storemix` 组要不要并进 CI？（locust 与 4 进程发压进 CI 代价大，建议保持本机证据。）
  3. `alternate` 逐请求轮转是最坏形状；真实多店流量更像 `mixed`——要不要再出一组
     `mixed` 两店（T001,T002 随机）作中间档？
