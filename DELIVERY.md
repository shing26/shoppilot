# 交付契约

本仓库的**入口层**。一屏读完，用途只有一个：让任何人在 60 秒内知道「这个项目有什么、在哪、到哪一步了」。

本文件**不复述内容**，只做索引。数字与判据的唯一入口是 [`docs/EVIDENCE.md`](docs/EVIDENCE.md)。

---

## 五件索引

| # | 回答的问题 | 本仓落在哪 | 状态 |
| --- | --- | --- | --- |
| ① | 为什么做它？解决什么？放弃过什么？ | [`CHARTER.md`](CHARTER.md)（业务背景与痛点、架构指标、里程碑）<br>[`PLAN.md`](PLAN.md)（架构形态、主链路、排期）<br>[`docs/adr/`](docs/adr/)（**51 编号 / 50 篇**，0022 有意预留） | 具备 |
| ② | 凭什么说它 work？ | [`docs/EVIDENCE.md`](docs/EVIDENCE.md)（证据分层 + 指标入账规则）<br>[`docs/loadtest-report.md`](docs/loadtest-report.md)<br>[`docs/threshold-calibration.md`](docs/threshold-calibration.md) | 具备 |
| ③ | 怎么一步步变成现在这样？ | [`CHANGELOG.md`](CHANGELOG.md)（按**轮**而非 semver 组织） | 具备 |
| ④ | 什么算完成？现在到哪？什么不做？ | [`RELEASE.md`](RELEASE.md)（发布声明、达标项、**三条未达标红线**、Freeze policy） | 具备 |
| ⑤ | 别人怎么在 N 分钟内跑起来？ | 下方[一条命令](#一条命令)；脚本 [`scripts/up.ps1`](scripts/up.ps1) / [`scripts/demo.ps1`](scripts/demo.ps1) | 具备 |

**术语去哪查**：`CONTEXT.md` 是**词汇表**（意图、缓存准入、串号、可查工单……），不是背景文档。背景在 `CHARTER.md`。

---

## 一条命令

前置：Docker Desktop、JDK 21、Ollama、PowerShell 7。

```powershell
git clone https://github.com/shing26/shoppilot.git ShopPilot; cd ShopPilot
pwsh -NoProfile -File scripts/up.ps1
start http://127.0.0.1:8082
```

`up.ps1` 的每一步都可重入（容器已在跑则跳过、seed 非空则跳过、入库为幂等 upsert）。
就绪判定用 Spring Boot readiness 健康组，不用端口——`ApplicationRunner` 跑完前 `/actuator/health` 返回 503，脚本因此不会在预热期放流量进来。完整说明见 [`README.md`](README.md) 的快速开始段。

---

## 当前状态（2026-09-28）

- **发布**：`v1.0.0` 已冻结（tag → `7f4334c`，CI run `35104751284` 验证）
- **未发布**：`v1.0.0` 之后 **86 个提交**，属 round15 – round22 八轮
- **功能面：没有待办**。票 01-39 / 41 / 风格票 / 有意不做成文票 / 42-68 全部 `implemented`，tracker 里 `ready-for-agent` 开放票为**零**；round17 – round22 六轮均已收口，一个进行中的轮次都没有
- **测试与门禁在干净 runner 上正常**：GitHub Actions `ci-subset` 在推上去的 HEAD 上 **success**，八个门禁步全绿（构建与 **342 条** JVM 测试、判据自检 40/40、离线 rescore 差异恰 4 条、套件夹具 24 条、task 判据 14 条、检索录放门、覆盖率棘轮、告警规则 `promtool test rules`）。本机 25 步全量矩阵最近一次 **839 s、22 绿 / 3 红**（`feedback`/`plansteps` 登记项 + `console` 的模型方差），调试台单独跑 **43/43**
- **两条活体红是登记项、不是产品缺陷**：`feedback` 的 `implied_retry` 间歇（local 3B 在含上一轮成功答复的会话里不再发工具调用）、`plansteps`（local 3B 不产生两步链）。**第三条 `console` 是模型方差**：`miss path renders as typewriter` 含「正文 > 60 字」的下界，本地 3B 对同一问句的输出在 60–156 字之间波动（同脚本单独跑两次均 **43/43**）。判定口径是「**不改判据去适配实现**」（ADR 0043），三条各自留归因与放开条件
- **round17 起的六轮重开都是[显式的政策覆盖](docs/adr/0046-round21-reopen-for-the-last-mile.md)**（依次为 [0033](docs/adr/0033-round17-reopen-for-architecture-completeness.md) / [0041](docs/adr/0041-round18-reopen-for-scoring-dimension-completeness.md) / 0044 / 0045 / 0046 / 0048），**不满足 ADR 0031 的触发条件**，均已诚实记录为覆盖、**不自动续期**；其中 round20 分两种依据（三条事实性修正 + 一条政策覆盖）、round22 分两类（65/67 记为「触发已到」、64/66 记为「政策越过」）
- **三条未达标红线**（缓存拦截率、吞吐峰值、未命中 TTFT）**照挂不摘**，判据与归因见 `RELEASE.md`。**不通过改口径、阈值或 Mock 参数刷绿**
- **未覆盖项同样照登**：**gold 180 条活体重跑仍未做**，且真因是**条件不成立**——`.env` 有云端 key 但日预算 `260000` < 180 条所需约 40-60 万，不越 ADR 0012 的预算闸门（放开 = 抬到 ≥60 万或分两天跑）；**round22 票 64 不闭合 round19 登记第 5 项**（录放门只守排序确定性与四个常数，不覆盖活体 hit@5）；收口审计唯一一红 `F1c` 是 round20 那次被误删的本机日志（不可逆，判据未动未豁免）

---

## 读法建议

| 你有多少时间 | 读什么 |
| --- | --- |
| 3 分钟 | [`docs/portfolio-interview.md`](docs/portfolio-interview.md)：架构、四个工程问题、三个数字和一个踩坑 |
| 10 分钟 | 本文件的[五件索引](#五件索引) → [`RELEASE.md`](RELEASE.md) → `README.md` 的指标表 |
| 1 小时 | 再进 [`docs/CODE_MAP.md`](docs/CODE_MAP.md)（模块所有权 + 请求链路源码落点） |
| 要动手改 | [`AGENTS.md`](AGENTS.md)：接手顺序、source of truth、修改禁令、验证与 Handoff 流程 |

---

## 待办与决策项（登记在此，不隐藏）

### 待裁决（两条路都成立，取决于你的意图）

| 项 | 现状 | 选项 |
| --- | --- | --- |
| `pom.xml` 版本 | `0.1.0-SNAPSHOT` | **A** 改 `1.0.0` —— 与 tag 对齐，声明"仓内即已发布版本"<br>**B** 改 `1.1.0-SNAPSHOT` —— 承认 `v1.0.0` 之后有 86 个未发布提交，正在走向下一个版本<br><span class="st">脚本已全部用通配符（`shoppilot-gateway-*.jar`），两条路都不会打断启动链</span> |

### 已登记的口径说明（非缺陷）

| 项 | 说明 |
| --- | --- |
| **黑盒 QA 的两项登记（2026-09-28）** | ① **「退款到哪了」这类措辞的读回**：放行态会给出与事实相反的结论（登记级别已上调，附证据）；② **追问中途换话题/取消没有出口**。两者都要动 triage/prompt，会碰 180 条 gold 的面，故登记不执行——触发条件见 `.scratch/shoppilot-mvp/README.md` 的 2026-09-28 登记段 |
| 正式 tracker 位于 `.scratch/shoppilot-mvp/` | README 第 839 行明确了这是**有意为之**（「不是临时草稿目录」），`.gitignore` 也已为 `.scratch/**/*.md` 开白名单。<br>保留记录的原因：任何**按目录名扫仓库**的自动工具都会漏掉它——`sk` 类工具与外部审计脚本尤甚。若要保持，建议在 tracker 自身 README 顶部再加一行说明。 |

### 已修正（2026-09-20）

| 项 | 原值 | 现值 |
| --- | --- | --- |
| README 的 ADR 范围陈述（第 835 行） | 「ADR 0001-0030」 | 「ADR 0001-0040（0022 未占用）」 |
| README 的票号范围（第 38、839 行） | 「票 01-32」 | 「票 01-41」 |
| 入口层 | 无 | 本文件 |
