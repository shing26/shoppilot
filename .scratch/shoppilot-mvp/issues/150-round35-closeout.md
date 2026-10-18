# 150 round35 收口

**Status:** ready-for-agent

## What to build

round35（agent 自主性升级）收口：把机制、观测、提示词、评测与证据四件收成可交付状态，并按本仓既有收尾流程
更新叙事与台账。

- **验证**：`.\mvnw.cmd -B -ntp verify` 全绿；收口审计实跑并把读数写进票面（与基线逐条对照，**不新增红**）；
  无 `logs/` 时的 SKIP 照登。
- **叙事换代**（本仓既有流程；map 135 明写叙事不属 map）：README 的 agent 段（4 轮 / 反思 / 观测口径）、
  `docs/architecture-faq.md`、`DELIVERY.md`、`CHANGELOG.md`；对外只说**已实测**的东西。
- **台账**：tracker 索引与 Round 表；`registered-debt.md` 把 **R11 / R12 挪到「已关闭」**；
  `docs/EVIDENCE.md` 核对 part10 行、指标名 56/58、`plan_steps_total` 值域说明。
- **未达成照登**：local 3B 的 `plansteps`（判据不动）、全量矩阵是否重跑按本机资源裁定、
  任何依赖活体的门禁未实跑的格。

## Blocked by

[143](143-max-tool-rounds-4.md)、[144](144-reflection-branch.md)、[145](145-reflection-observability-and-token-fields.md)、
[146](146-write-guard-domain-gate.md)、[147](147-prompt-v1-2-0.md)、[148](148-autonomy-eval-set-and-module.md)、
[149](149-autonomy-dev-live-run.md)（全部）。

## 口径

- 依据：[ADR 0069](../../../docs/adr/0069-round35-agent-autonomy-upgrade-reopen-basis.md) 的 Consequences 与
  [spec v2](../agent-autonomy-spec-v2.md) §5；收口流程照 `AGENTS.md`。
- **判据红线**：gold 180 / 阈值 / 分母 / `judge()` / 既有验收判据一字不动；未达成项必须保留实测值、归因与限制。
- `G6_EXPECT` 只在**本机真的重跑带 build/unit 的活体矩阵**时随新落点换代；没跑就不动（它会红，照登）。
- 提交信息沿用 `docs(round35): ...` / `feat(round35): ...` 风格。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
python .scratch/shoppilot-mvp/round3-closeout-audit.py
git diff --check; git status --short
```

## Handoff notes

（解决时补：关键决策、验证落点、三个现场追问；审计读数与未达成清单必须与票面一致。）
