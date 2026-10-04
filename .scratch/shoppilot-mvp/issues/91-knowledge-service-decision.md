# 91 决策票：知识服务化做不做

**Status:** ready-for-agent（**这张票的产出是裁定与登记，不是代码**）

## What to build

program 剩下的第三块。这张票只回答一个问题：**ADR 0053 要求的「知识服务独占 ES/Qdrant」，在本机的资源账下值不值。**

**不是实现票**。三个选项各自的代价与收益已量化在
[`program-remaining-blocks.md`](../program-remaining-blocks.md) §2 块三，这里不重复。

## 现状（已查证，可直接引用）

- 网关**直连** ES/Qdrant：`HybridRetriever`、`L2SemanticCache`、`StartupInitializer`、
  两个 HealthIndicator、`T1CentroidLayer` 共 5 处；compose 的 `x-service-env` 把两个 URL 注给了全部四个服务。
- **ingest 不是独立服务**，是网关同一个 jar 的另一个 profile（`--spring.profiles.active=ingest`）。
- 要搬的类 8 个；`KbEpoch` 变跨进程（还含一个写操作 `POST /ops/epoch/bump`）；
  `HybridRetriever.Result` / `Retrieved` / `Recalls` / `Scored` 全是**网关内部 record**，跨进程必须下沉到契约库——
  **这一层是迁移里最容易漏的**。
- `EmbeddingClient` 被检索、缓存、**分诊**三条路共用；搬它等于把毫秒级实时链路拖进跨进程调用。
- 代价：第五个 JVM 约 500 MB（ADR 0053 自己据此否决过第五服务，0058 又据此否决了独立身份服务）。

## 三个选项

| 选项 | 何时该选它 |
|---|---|
| **全拆**：检索 + 入库都搬进 `shoppilot-knowledge` | 「ES/Qdrant 只能被一个服务访问」成为硬要求；或检索策略要由网关之外的团队改；或全栈档资源账改善到能日常跑 |
| **只拆 ingest**：把一次性作业从网关 profile 变成真服务 | 只在「网关 jar 不该背着入库职责」这条洁癖成立、且不接受上面任何一条触发时 |
| **不做，登记偏离** | 其余全部情况 |

## 口径

- **不做不等于删 ADR**：ADR 0053 原文一个字不动，偏离按本仓既有做法**在 tracker 与本票里登记**（同 ADR 0024 加换代指针时的纪律：不动既有 ADR）。
- 选了「不做」也**要把触发条件写死**，否则这条登记会烂在 tracker 里没人再看（同 ticket 20 的欠账纪律）。
- 这张票**不做任何代码改动**。

## 验收

- 裁定写进本票的 Handoff + tracker 的 Round 表；
- 若裁定为「不做」：写明偏离 ADR 0053 的哪一条、触发条件、以及将来重开时要先做的第一件事；
- 若裁定为「做」：**另开 round spec 与票**，本票只留下决定本身。

## Verify

```powershell
git diff --check && git status --short
```

## Handoff notes

（收口时补）