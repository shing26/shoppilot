# 91 决策票：知识服务化做不做

**Status:** implemented（裁定：**不做，登记偏离**——产出是裁定与登记，零代码改动）

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

- **裁定（2026-10-18）：不做，登记偏离。** 三个「全拆」触发条件逐条核对，**全不成立**：
  1. 「ES/Qdrant 只能被一个服务访问」成硬要求——不成立（单机自用演示，无合规/团队边界）；
  2. 检索策略要由网关之外的团队改——不成立（单所有者项目）；
  3. **全栈档资源账改善到能日常跑——不成立，且当晚有反向实测证据**：本机全量矩阵跑一次 1153s、
     工单服务在内存压力下 `OutOfMemoryError`（工单查询 3s 超时 → 落单静默失败）、Ollama 因显存被占
     连模型都加载不了——资源账没有改善，是紧的。
  「只拆 ingest」的唯一条件是「网关 jar 不该背着入库职责」这条洁癖且不接受上述任一触发——所有者从未提出该洁癖，
  且按本仓一贯的资源优先立场不选。
- **偏离的是 ADR 0053 的哪一条**：它的「知识服务独占 ES/Qdrant」边界（网关/ingest 直连两引擎）保持**未兑现**；
  这不等于删 ADR——**ADR 0053 原文一个字不动**（本仓纪律：偏离登记在 tracker 与本票，见 B7 对既有 ADR 的窗口红线）。
  现状的准确描述：5 处直连（`HybridRetriever` / `L2SemanticCache` / `StartupInitializer` / 两个 HealthIndicator / `T1CentroidLayer`），
  ingest 仍是网关同一个 jar 的另一个 profile。
- **触发条件（与 `registered-debt.md` R1 同源，三者任一成立即重开）**：① 「ES/Qdrant 只能被一个服务访问」成为硬要求；
  ② 检索策略要由网关之外的团队维护；③ 全栈档资源账改善到能日常跑（例如本机可支配内存 ≥ 8 GB 且能连跑数日）。
- **将来重开时要先做的第一件事**：把 `HybridRetriever.Result` / `Retrieved` / `Recalls` / `Scored` 四个**网关内部 record**
  下沉到 `shoppilot-tool-api` 成契约（票面「现状」节指名的迁移里最容易漏的一层），再动 `EmbeddingClient` 的分家——
  它被检索、缓存、**分诊**三条路共用，搬它等于把毫秒级实时链路拖进跨进程调用，必须先量延迟账。
- **验证落点**：本票零代码改动；裁定写进本票 Handoff + tracker 的 plan 行与 frontier 行 + `program-remaining-blocks.md` §2 块三 +
  `registered-debt.md` R1。`git diff --check` 干净。
- **三个现场追问**：
  1. 触发条件③写的是「资源账改善到能日常跑」——要不要把它量化成具体阈值（如可用内存 ≥8 GB）以便机器判定？
  2. 若将来真的重开，「全拆」与「只拆 ingest」之间要不要再加一档「只把 `EmbeddingClient` 拆出去」的中间选项？
  3. 偏离登记挂在 tracker + R1 + 本票三处——要不要在 ADR 0053 顶部加一行换代指针（会触发 B7 的窗口红线，需单独走一次流程）？
