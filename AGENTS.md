# ShopPilot Agent Guide

这个仓库由多轮 Agent 交接开发。开始改代码前，先按下面顺序恢复上下文，不要把某个 ticket 的局部描述当成全仓现状。

## 必读顺序

1. [`.scratch/shoppilot-mvp/README.md`](.scratch/shoppilot-mvp/README.md)：当前 round、全部 ticket 索引和真实收口状态。
2. [`CONTEXT.md`](CONTEXT.md)：项目术语。术语或 ADR 结论有冲突时停下来问，不许自行改写。
3. [`docs/CODE_MAP.md`](docs/CODE_MAP.md)：模块边界、请求链路落点和首改文件。
4. [`docs/EVIDENCE.md`](docs/EVIDENCE.md)：README 数字对应的报告、原始产物、复现命令和证据边界。
5. 当前 ticket 依赖的 ADR：`docs/adr/`。
6. 当前 ticket 与其所属 round spec，然后才读相关源码。

`README.md` 是产品与交付说明，`PLAN.md` 是 2026-09-08 的原始实施计划。二者都不是当前 frontier 的 source of truth。

## Source of Truth

| 信息 | 唯一入口 |
| --- | --- |
| 当前可领取/已完成工作 | `.scratch/shoppilot-mvp/README.md` 与 `issues/NN-slug.md` |
| 本文档目录并非临时草稿 | `.scratch/shoppilot-mvp/` 是正式 tracker，不按目录名做清理 |
| 已锁定架构与产品决策 | `docs/adr/` |
| 领域术语和边界 | `CONTEXT.md` |
| 公开指标与未达成项 | `README.md`，详细口径与产物见 `docs/EVIDENCE.md` |
| 代码所有权与首改位置 | `docs/CODE_MAP.md` |
| 当前交付方向、冻结线与执行顺序 | `docs/PROJECT_PLAN.md` |
| 干净 runner 的构建/单测结果 | `.github/workflows/ci-subset.yml` 与对应 GitHub Actions run |

## 必须遵守

- 一次只处理 frontier 上的一个 ticket。`done` / `implemented` 都是已收口；`ready-for-agent` 才表示可领取。
- `v1.0.0` 冻结后，新功能票必须引用 ADR 0030 或 ADR 0031 的触发条件；没有触发就登记，不执行。round17 范围内的票（34-41）按 ADR 0033 记录的项目所有者政策覆盖执行，不适用本条。
- ticket 收尾必须补 `Status` 和 `## Handoff notes`，至少写清关键决策、验证落点、三个现场追问。
- 不得自行修改 `CONTEXT.md` 术语或 ADR 结论。发现冲突先停下来说明矛盾。
- 不得移动、重命名或删除历史证据目录。审计脚本、ticket 和 README 依赖既有路径。
- 不为“整齐”合并 `cache`、`triage`、`knowledge`、`llm`、`identity`、`ratelimit` 等能力包；先读 `docs/CODE_MAP.md` 的判断。
- 不改验收判据、阈值、gold 或指标分母来让结果变绿。未达成项必须保留实测值、归因和限制。
- `logs/`、根目录 `hs_err_pid*.log`、`replay_pid*.log` 是本机证据，干净克隆中不存在。需要长期证据时，把汇总或机器可复跑产物写入 `docs/`、`eval/results/` 或 `loadtest/results/`，并登记到 `docs/EVIDENCE.md`。
- 根目录 `ShopPilot-项目梳理-20260913.md` 是未跟踪的过期快照。不要把它当现状，不要提交，也不要替用户删除。
- 不在无关任务里清理 `target/`、`.tools/`、`.venv-loadtest/`、`.workbuddy/`、`__pycache__/` 等本机目录。

## 常改入口

| 要改什么 | 先看哪里 |
| --- | --- |
| SSE/同步聊天入口、准入 | `shoppilot-gateway/src/main/java/com/shoppilot/gateway/web/ChatController.java` |
| Agent 状态流转、工具循环、缓存写回 | `shoppilot-gateway/src/main/java/com/shoppilot/gateway/agent/AgentStateMachine.java` |
| 工具参数策略与 biz-mock 调用 | `agent/ToolDispatcher.java`、`agent/BizMockClient.java` |
| 意图判定 | `gateway/triage/` |
| 缓存与写回 | `gateway/cache/` |
| 检索与入库 | `gateway/knowledge/`、`gateway/ingest/` |
| 鉴权、上下文与 token | `gateway/identity/` |
| 业务数据、隔离、工单 | `shoppilot-biz-mock/src/main/java/com/shoppilot/bizmock/` |
| 跨模块工具 DTO/schema | `shoppilot-tool-api/src/main/java/com/shoppilot/tool/` |
| 配置、健康与运行时指标 | `gateway/config/` |
| 调试台 | `gateway/src/main/resources/static/index.html`、`scripts/verify-console.mjs` |

更完整的请求链路和测试映射见 [`docs/CODE_MAP.md`](docs/CODE_MAP.md)。

## 验证

文档或 tracker 改动至少执行：

```powershell
git diff --check
git status --short
```

代码改动在本机执行：

```powershell
.\mvnw.cmd -B -ntp verify
```

Linux/CI 等价命令：

```bash
bash ./mvnw -B -ntp verify
```

PowerShell 脚本改动另跑：

```powershell
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
```

全量 `run-acceptance.ps1` 需要本机 Ollama、容器、浏览器和 `logs/`，只在本轮验收明确要求活体证据时跑。CI 子集只覆盖构建与 JVM 测试，不替代全量活体验收。

## 收尾流程

1. 核对 ticket 的 `Blocked by`、`Verify` 和验收项，不做票外顺带重构。
2. 运行与风险匹配的验证，记录命令、结果和有意的缺口。
3. 更新 ticket 的 `Status` 与 `## Handoff notes`。
4. 若数字或证据来源变了，同步更新 `docs/EVIDENCE.md`；若依赖边界变了，同步更新 `docs/CODE_MAP.md` 或 ADR。
5. 检查 `git diff --check` 与 `git status --short`，确认没有混入本机日志、崩溃日志或过期快照。
6. 提交信息沿用仓库风格：`docs(roundNN): ...`、`fix(roundNN): ...`、`test(roundNN): ...`。
