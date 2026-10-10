# 交付契约

本仓库的**入口层**。一屏读完，用途只有一个：让任何人在 60 秒内知道「这个项目有什么、在哪、到哪一步了」。

本文件**不复述内容**，只做索引。数字与判据的唯一入口是 [`docs/EVIDENCE.md`](docs/EVIDENCE.md)。

---

## 五件索引

| # | 回答的问题 | 本仓落在哪 | 状态 |
| --- | --- | --- | --- |
| ① | 为什么做它？解决什么？放弃过什么？ | [`CHARTER.md`](CHARTER.md)（业务背景与痛点、架构指标、里程碑）<br>[`PLAN.md`](PLAN.md)（架构形态、主链路、排期）<br>[`docs/adr/`](docs/adr/)（**65 编号 / 64 篇**，0022 有意预留） | 具备 |
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

## 当前状态（2026-10-10）

- **发布**：`v1.0.0` 已冻结（tag → `7f4334c`，CI run `35104751284` 验证）
- **未发布**：`v1.0.0` 之后 **158 个提交**，属 round15 – round31
- **功能面：没有待办**。票 01-116 里 **01-95、96-97、113-116 全部 `implemented`**（含 B1/B2/B3 的 100-112 与 B4 的 96/97/113-116）；round31 于 2026-10-09 收口，tracker 索引与票面状态位已同步校正（2026-10-10）。`ready-for-agent` 只剩**两张有意留在台面上的**：票 99（IM 主线收口，只定对外措辞）、91（知识服务化裁定）——触发条件写在 [`.scratch/shoppilot-mvp/registered-debt.md`](.scratch/shoppilot-mvp/registered-debt.md)，不是遗漏。**对外措辞未定稿**：票 99 还没收口，所以「飞书真连已验证」这句现在还不能当对外定稿引用
- **测试与门禁**：干净 runner 上 `ci-subset` 共**九个**门禁步。**最近一次绿是 2026-10-05 的 run `37258754305`；此后 main 持续红到 2026-10-10，根因是 round30 改了 workspace 前端源码却没有把新构建产物提交进仓**（CI 第九步「产物与仓内逐字节一致」判红，故障原文 `::error::前端构建产物与仓内不一致——改了源码请重新构建并提交产物`），2026-10-10 已重建产物并提交、新哈希与 CI 自己产出的一致，**推上去即恢复绿**（本轮 diff 尚未推送时的实读：本机 verify 四模块 **505** 条 = tool-api 10 + biz-mock 67 + gateway 383 + ticket 45，全绿）。九步是：构建与 JVM 测试、判据自检 40/40、离线 rescore 差异恰 4 条、套件夹具 24 条、task 判据 14 条、检索录放门、覆盖率棘轮、告警规则 `promtool test rules`、**两个前端产物的逐字节校验**。本机活体矩阵分三档，**最近落点各不相同，逐档照登**：
  - **full 档（local 真模型，≈6.6 GB）最近一次是 2026-10-02 的清场日**（`logs/acceptance-run-20261002-144837.log`，**930 s、24 绿 / 3 红**，26 步）。**矩阵此后从 26 步加到 28 步**（round26 加 `outbound`、round27 加 `buyer`），**这两步至今没有在 full 档实跑过**——本机可用内存不够（R4 已登记）。
  - **live 档（perf / MockLLM，≈2.6 GB，`scripts/cleanout-live.ps1`）2026-10-05 最近一次**：三条浏览器/出站门禁里 **`outbound` 绿**，`buyer` 与 `workspace` **各红**（U1/U2：live 档的 `up.ps1` 链路拿不到演示口令，故登录后的两格跑不到）。
  - **两档都跑不到真实模型**：`live` 档用 MockLLM，只量编排层；`full` 档要 local 真模型（≈6.6 GB）而本机跑不起。**所以「真实模型下的三个浏览器门禁」这一格至今空着**，不假装有读数（R4 已登记）。
- **两条活体红是登记项、不是产品缺陷**：`feedback` 的 `implied_retry` 间歇（local 3B 在含上一轮成功答复的会话里不再发工具调用）、`plansteps`（local 3B 不产生两步链）。**第三条 `console` 是模型方差**：`miss path renders as typewriter` 含「正文 > 60 字」的下界，本地 3B 对同一问句的输出在 60–156 字之间波动（同脚本单独跑两次均 **43/43**）。判定口径是「**不改判据去适配实现**」（ADR 0043），三条各自留归因与放开条件
- **重开依据分两段，别混着说**：round17 – round22 六轮是[显式的政策覆盖](docs/adr/0046-round21-reopen-for-the-last-mile.md)（依次为 [0033](docs/adr/0033-round17-reopen-for-architecture-completeness.md) / [0041](docs/adr/0041-round18-reopen-for-scoring-dimension-completeness.md) / 0044 / 0045 / 0046 / 0048），**不满足 ADR 0031 的触发条件**，均已诚实记录为覆盖、**不自动续期**；round23 – round27 五轮的依据换成 **[ADR 0052](docs/adr/0052-reposition-as-runnable-service-system-prototype.md) 的定位改写**——所有者授权的 program（"客服系统化演进"，七决策 2026-10-01 确认），所以那一段不是政策越过而是**换了一个被授权的 source of truth**
- **三条未达标红线**（缓存拦截率、吞吐峰值、未命中 TTFT）**照挂不摘**，判据与归因见 `RELEASE.md`。**不通过改口径、阈值或 Mock 参数刷绿**
- **未覆盖项同样照登**：**gold 180 条活体重跑仍未做**，且真因是**条件不成立**——`.env` 有云端 key 但日预算 `260000` < 180 条所需约 40-60 万，不越 ADR 0012 的预算闸门（放开 = 抬到 ≥60 万或分两天跑）；**round22 票 64 不闭合 round19 登记第 5 项**（录放门只守排序确定性与四个常数，不覆盖活体 hit@5——`docs/retrieval-quality-metrics.md` 那张表走的是录制夹具，仍不是活体）；收口审计唯一一红 `F1c` 是 round20 那次被误删的本机日志（不可逆，判据未动未豁免）
- **欠账有一张总账**：`.scratch/shoppilot-mvp/registered-debt.md`（已关闭 3 / 登记项 R1-R4 / 未达成 U1-U5），每笔要么有触发条件、要么有「永不做」的裁决——**没有第三种状态**

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
| `pom.xml` 版本 | `0.1.0-SNAPSHOT` | **A** 改 `1.0.0` —— 与 tag 对齐，声明"仓内即已发布版本"<br>**B** 改 `1.1.0-SNAPSHOT` —— 承认 `v1.0.0` 之后有 **141** 个未发布提交，正在走向下一个版本<br><span class="st">脚本已全部用通配符（`shoppilot-gateway-*.jar`），两条路都不会打断启动链</span> |

### 已登记的口径说明（非缺陷）

| 项 | 说明 |
| --- | --- |
| **黑盒 QA 的两项登记（2026-09-28）** | ① **「退款到哪了」这类措辞的读回**：放行态会给出与事实相反的结论（登记级别已上调，附证据）；② **追问中途换话题/取消没有出口**。两者都要动 triage/prompt，会碰 180 条 gold 的面，故登记不执行——触发条件见 `.scratch/shoppilot-mvp/README.md` 的 2026-09-28 登记段 |
| 正式 tracker 位于 `.scratch/shoppilot-mvp/` | README 第 839 行明确了这是**有意为之**（「不是临时草稿目录」），`.gitignore` 也已为 `.scratch/**/*.md` 开白名单。<br>保留记录的原因：任何**按目录名扫仓库**的自动工具都会漏掉它——`sk` 类工具与外部审计脚本尤甚。若要保持，建议在 tracker 自身 README 顶部再加一行说明。 |

### 已修正

| 日期 | 项 | 原值 | 现值 |
| --- | --- | --- | --- |
| 2026-09-20 | README 的 ADR 范围陈述 | 「ADR 0001-0030」 | 「ADR 0001-0040（0022 未占用）」 |
| 2026-09-20 | README 的票号范围 | 「票 01-32」 | 「票 01-41」 |
| 2026-09-20 | 入口层 | 无 | 本文件 |
| **2026-10-05** | **本文件的状态段（停在 2026-09-28，round23-28 的七轮没进来）** | ADR 51 篇 / 86 个提交 / 342 条测试 / 八个门禁步 / 「开放票为零」 | **ADR 59 篇（60 编号，0022 预留）/ 141 个提交 round15-27 / 451 条测试（10+57+39+345）/ 九个门禁步**；活体矩阵按三档分列各自落点；`ready-for-agent` 从「零」改为**「五张，全部有触发条件」** |
| **2026-10-05** | README 的票号范围（第 41、960 行） | 「票 01-56」 | 「票 01-99（01-95 implemented；96-99 与 91 有意留在台面上）」 |
| **2026-10-05** | README 复现段的 JVM 测试数（第 573、625 行） | 「3 + 21 + 276 = 300」「338 条」 | **「10 + 57 + 39 + 345 = 451」**（四模块，surefire 汇总） |
| **2026-10-10** | **本文件的状态段（停在 2026-10-05，round28-31 的四轮没进来）** | ADR 59 篇 / 141 个提交 / 451 条测试 / 「干净 runner 上 success」 | **ADR 64 篇（65 编号）/ 158 个提交 round15-31 / 505 条测试（10+67+383+45）/ CI 自 round30 起持续红（前端产物漂移）——10-10 已修待推**；票 113/114/115 状态位不一致照登 |
| **2026-10-10** | 票 116 手上的 JVM 读数 | `5+10+57+342+39=453` | **`10 + 67 + 383 + 45 = 505`**。原写法两处错：前面多写一个 `5`（四模块是四个数，2026-10-05 收口日就记下过这个毛病），数字本身是 10-09 13:24 的旧落点、没跟上票 113/114 与收口期补测 |
| **2026-10-10** | tracker 索引的票 113-116 状态位 | `ready-for-agent` | **全部 `implemented`**（票面早已是 implemented，本行同步 tracker 索引、Round 表、round31 spec 状态与 program 的 B4 换代指针）。**这与票 72 那次是同一种毛病**：票面改了、索引没改，frontier 就一直把已收口的票摆回可领取 |
| **2026-10-10** | round30 记账的 biz-mock 测试数 | `10 + 66 + 345 + 45 = 466` | **实读 67**（源码未动、重跑仍是 67，那行的 66 是按 `64+2` 推的）；历史读数不动，更正登记在 `docs/EVIDENCE.md` |
