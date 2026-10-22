# 交付契约

本仓库的**入口层**。一屏读完，用途只有一个：让任何人在 60 秒内知道「这个项目有什么、在哪、到哪一步了」。

本文件**不复述内容**，只做索引。数字与判据的唯一入口是 [`docs/EVIDENCE.md`](docs/EVIDENCE.md)。

---

## 五件索引

| # | 回答的问题 | 本仓落在哪 | 状态 |
| --- | --- | --- | --- |
| ① | 为什么做它？解决什么？放弃过什么？ | [`CHARTER.md`](CHARTER.md)（业务背景与痛点、架构指标、里程碑）<br>[`PLAN.md`](PLAN.md)（架构形态、主链路、排期）<br>[`docs/adr/`](docs/adr/)（**70 编号 / 69 篇**，0022 有意预留） | 具备 |
| ② | 凭什么说它 work？ | [`docs/EVIDENCE.md`](docs/EVIDENCE.md)（证据分层 + 指标入账规则）<br>[`docs/loadtest-report.md`](docs/loadtest-report.md)<br>[`docs/threshold-calibration.md`](docs/threshold-calibration.md) | 具备 |
| ③ | 怎么一步步变成现在这样？ | [`CHANGELOG.md`](CHANGELOG.md)（按**轮**而非 semver 组织） | 具备 |
| ④ | 什么算完成？现在到哪？什么不做？ | [`RELEASE.md`](RELEASE.md)（发布声明、达标项、**三条未达标红线**、Freeze policy） | 具备 |
| ⑤ | 别人怎么在 N 分钟内跑起来？ | 下方[一条命令](#一条命令)；脚本 [`scripts/up.ps1`](scripts/up.ps1) / [`scripts/demo.ps1`](scripts/demo.ps1) | 具备 |

**术语去哪查**：`CONTEXT.md` 是**词汇表**（意图、缓存准入、串号、可查工单……），不是背景文档。背景在 `CHARTER.md`。

---

## 一条命令

前置：Docker（Compose v2）、Python 3.11+、Ollama + `bge-m3`（约 1.2GB）。

```bash
git clone https://github.com/shing26/shoppilot.git ShopPilot; cd ShopPilot
python scripts/demo.py --up
```

它做四件事：生成/修复 `.env`（占位符换随机值）→ `docker compose --profile full up -d --build --wait` → 等网关 readiness → 跑缓存 / 串号 / 降级三个场景。容器档默认 `SHOPPILOT_LLM_MODE=perf`（MockLLM），只要求宿主 Ollama 提供 `bge-m3`；真模型用 `.env` 覆盖。

Docker Hub 401/Maven 网络不可用时：用 `SHOPPILOT_MAVEN_IMAGE` / `SHOPPILOT_JRE_IMAGE` 指到镜像站，或先 `mvnw -DskipTests package` 再 `python scripts/demo.py --prebuilt`（宿主 jar + `Dockerfile.runtime`，不走 Dockerfile 的 Maven stage）。

Windows 本机 JVM 开发路径仍可用 `pwsh -NoProfile -File scripts/up.ps1`（需要 JDK 21 + Maven wrapper）。
当前状态/测试数/覆盖率只看 [`docs/STATUS.md`](docs/STATUS.md)；历史读数见 `docs/EVIDENCE.md`。
就绪判定用 Spring Boot readiness 健康组，不用端口——`ApplicationRunner` 跑完前 `/actuator/health` 返回 503，脚本因此不会在预热期放流量进来。完整说明见 [`README.md`](README.md) 的快速开始段。

---

## 当前状态（2026-10-19）

- **发布**：`v1.0.0` 已冻结（tag → `7f4334c`，CI run `35104751284` 验证）
- **未发布**：`v1.0.0` 之后 **200 个提交**，属 round15 – round35
- **功能面：没有待办**。票 **01-150 全部收口**（含 B1-B4 的 100-116、IM 主线 96-99 与知识服务化裁定 91——后两张于 2026-10-18 收口；round32-34 的 117-134、wayfinder 调查票 135-142、**round35 的 143-150** 于 2026-10-17~10-19 收口），`ready-for-agent` **为空**。**round35（agent 自主性升级）依据 [ADR 0069](docs/adr/0069-round35-agent-autonomy-upgrade-reopen-basis.md)**：工具循环 2 → **4 轮**（计划步数同源、≤4）、读工具的③操作性失败由系统发起**一次**反思重试（占轮、按工具 1 次、写工具不反思、不绕闸），三态可枚举（`plan[].attempt`/`reflection` + `shoppilot_reflection_total` + trace 行）；dev 档 part10 实测 30 条 / 22.2 万 token / 触发 7-不触发 23-用尽 0（[`eval/results/autonomy-part10-dev-20261019.md`](eval/results/autonomy-part10-dev-20261019.md)）。**对外措辞按票 99 定死**：B 段只说「飞书真连已验证」+ 证据指针，不说「已接入各 IM 平台」
- **测试与门禁**：干净 runner 上 `ci-subset` 共**九个**门禁步。**最近一次绿是 2026-10-05 的 run `37258754305`；此后 main 持续红到 2026-10-10，根因是 round30 改了 workspace 前端源码却没有把新构建产物提交进仓**（CI 第九步「产物与仓内逐字节一致」判红，故障原文 `::error::前端构建产物与仓内不一致——改了源码请重新构建并提交产物`），2026-10-10 已重建产物并提交、新哈希与 CI 自己产出的一致，**推上去即恢复绿**（round35 收口实读 577；**2026-10-22 可信度收口后本机 verify 四模块 618 条 = tool-api 10 + biz-mock 75 + gateway 485 + ticket 48，全绿**；当前值以 `docs/STATUS.md` 为准）；干净 runner 上最近一次绿是 run `39247758811`（`0bfb400`，定位清理））。九步是：构建与 JVM 测试、判据自检 40/40、离线 rescore 差异恰 4 条、套件夹具 24 条、task 判据 14 条、检索录放门、覆盖率棘轮、告警规则 `promtool test rules`、**两个前端产物的逐字节校验**。本机活体矩阵分三档，**最近落点各不相同，逐档照登**：
  - **full 档（local 真模型，≈6.6 GB）最近一次是 2026-10-02 的清场日**（`logs/acceptance-run-20261002-144837.log`，**930 s、24 绿 / 3 红**，26 步）。**矩阵此后从 26 步加到 29 步**（round26 加 `outbound`、round27 加 `buyer`、round34 加 `store-isolation`），**全量 29 步在 full 档跑过两次**：2026-10-18 首跑（23 绿 / 6 红 / 1153 s，六条红逐条归因——三条工装漂移已修、`console` 与 R9 已由票 134 关闭、`feedback`/`plansteps` 为登记项；见 `docs/EVIDENCE.md` 的 round34 行）；**2026-10-19 补跑**（HEAD `0bfb400`，首跑 **20 绿 / 9 红 / 1242 s**——`build` 一次 surefire fork 崩溃后 `-Only build,unit` 重跑双绿，`refund`/`workspace`/`buyer` 补环境变量后转绿，`console` 43/44 为已登记方差；`plan` 的「biz-mock 停机无 fallback」新发现登记 **R14**，`polarity` 前置不稳定登记 **U11**；G6 常数换代 **577**）。
  - **live 档（perf / MockLLM，≈2.6 GB，`scripts/cleanout-live.ps1`）2026-10-05 最近一次**：三条浏览器/出站门禁里 **`outbound` 绿**，`buyer` 与 `workspace` **各红**（U1/U2：live 档的 `up.ps1` 链路拿不到演示口令，故登录后的两格跑不到）。
  - **两档都跑不到真实模型**：`live` 档用 MockLLM，只量编排层；`full` 档要 local 真模型（≈6.6 GB）而本机跑不起。**所以「真实模型下的三个浏览器门禁」这一格至今空着**，不假装有读数（R4 已登记）。
- **两条活体红是登记项、不是产品缺陷**：`feedback` 的 `implied_retry` 间歇（local 3B 在含上一轮成功答复的会话里不再发工具调用）、`plansteps`（local 3B 不产生两步链）。**第三条 `console` 是模型方差**：`miss path renders as typewriter` 含「正文 > 60 字」的下界，本地 3B 对同一问句的输出在 60–156 字之间波动（同脚本单独跑两次均 **43/43**）。判定口径是「**不改判据去适配实现**」（ADR 0043），三条各自留归因与放开条件。**round35 起工具上限为 4 轮**：`plansteps` 那格（local 3B 不产生多步链）只会更红，**判据一字未动、照登**
- **重开依据分两段，别混着说**：round17 – round22 六轮是[显式的政策覆盖](docs/adr/0046-round21-reopen-for-the-last-mile.md)（依次为 [0033](docs/adr/0033-round17-reopen-for-architecture-completeness.md) / [0041](docs/adr/0041-round18-reopen-for-scoring-dimension-completeness.md) / 0044 / 0045 / 0046 / 0048），**不满足 ADR 0031 的触发条件**，均已诚实记录为覆盖、**不自动续期**；round23 – round27 五轮的依据换成 **[ADR 0052](docs/adr/0052-reposition-as-runnable-service-system-prototype.md) 的定位改写**——所有者授权的 program（"客服系统化演进"，七决策 2026-10-01 确认），所以那一段不是政策越过而是**换了一个被授权的 source of truth**
- **三条未达标红线**（缓存拦截率、吞吐峰值、未命中 TTFT）**照挂不摘**，判据与归因见 `RELEASE.md`。**不通过改口径、阈值或 Mock 参数刷绿**
- **未覆盖项同样照登**：**gold 180 条活体重跑仍未做**——注意**预算闸门已不是障碍**（round35 票 149 把日预算抬到 `600000`，够 180 条所需的 40-60 万），未做的原因是「重跑 gold」是**独立裁定**（U7，ADR 0069 明写不并入 round35），不是预算不成立；**round22 票 64 不闭合 round19 登记第 5 项**（录放门只守排序确定性与四个常数，不覆盖活体 hit@5——`docs/retrieval-quality-metrics.md` 那张表走的是录制夹具，仍不是活体）；收口审计唯一一红 `F1c` 是 round20 那次被误删的本机日志（不可逆，判据未动未豁免）
- **欠账有一张总账**：`.scratch/shoppilot-mvp/registered-debt.md`（已关闭 **14** / 登记项 R1、R3、R5、R6、R8、R10、**R15** / 未达成 U1-U5、U8、U10、U11；round35 把 R11（写守卫行业门）、R12（响应 token 字段）双双挪进已关闭，R13（README 门面化后的 8 条审计红）经所有者裁定「重锚检查为主」后同批关闭，**U6（全量矩阵未跑）由 2026-10-19 清场日补跑关闭**，**R2（IM 真连）与 R4（local 全栈活体验收）随 round31 / 2026-10-19 矩阵收口关闭**，**R7（T002 定责转人）于 2026-10-20 按所有者授权执行并关闭**，**R14（反思吞掉读工具失败）于 2026-10-20 按所有者裁定 A 修复并关闭**，**U7（180 条 gold 活体重跑）于 2026-10-20 在 qwen-plus 上完成并关闭**；**R15**（gold 的 4 个意图低于 95%）与 **U11**（极性守卫 L2 前置不稳定）已登记——审计项数 97），每笔要么有触发条件、要么有「永不做」的裁决——**没有第三种状态**

---

## 读法建议

| 你有多少时间 | 读什么 |
| --- | --- |
| 3 分钟 | [`docs/design-walkthrough.md`](docs/design-walkthrough.md)：架构走读、四个工程问题、三个数字和一个踩坑 |
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
| **2026-10-19** | **本文件的状态段（停在 2026-10-10，round32-35 的六轮没进来）** | ADR 64 篇 / 158 个提交 / 505 条测试 / 「开放票只剩两张（91、99）」 | **ADR 68 篇（69 编号）/ 200 个提交 round15-35 / 577 条测试（10+73+449+45）/ `ready-for-agent` 为空**；round35（143-150）按 ADR 0069 把工具循环抬到 4 轮 + 读工具一次反思重试，dev 档 part10 实测 22.2 万 token；日预算 260000 → 600000；R11/R12 关账 |
| **2026-10-20** | **本文件的状态段（round35 收口后、round36 未开）** | ADR 68 篇（69 编号）/ 591 条测试（10+73+463+45）/ `ready-for-agent` 为空 | **ADR 69 篇（70 编号）/ round36 已收口：票 151（A+B+C，ADR 0070）**；JVM 仍 **591**、两工程 typecheck/build 绿；R7/R14-A 已收口、R15 新登记；**三个活体浏览器门禁 + console.png 重拍未实跑照登** |
| **2026-10-10** | 票 116 手上的 JVM 读数 | `5+10+57+342+39=453` | **`10 + 67 + 383 + 45 = 505`**。原写法两处错：前面多写一个 `5`（四模块是四个数，2026-10-05 收口日就记下过这个毛病），数字本身是 10-09 13:24 的旧落点、没跟上票 113/114 与收口期补测 |
| **2026-10-10** | tracker 索引的票 113-116 状态位 | `ready-for-agent` | **全部 `implemented`**（票面早已是 implemented，本行同步 tracker 索引、Round 表、round31 spec 状态与 program 的 B4 换代指针）。**这与票 72 那次是同一种毛病**：票面改了、索引没改，frontier 就一直把已收口的票摆回可领取 |
| **2026-10-11** | `docs/refund-hitl-demo.gif`（首版，2026-10-10 入库） | 6 帧 / 328 KB；「中间栏是这笔请求的真实响应帧」 | **首版作废**：中间栏中文是 GBK 化双重编码乱码、token 流恒渲染 `[object Object]`——数据源是真的，呈现不是买家收到的内容。根因：SSE 端点未声明 charset，Chromium CDP 在中文 Windows 上按 GBK 解码再重编码（线上字节实验证明网关写的本是 UTF-8）；`ChatController` 已显式声明响应 charset，GIF 同 perf 档重采（6 帧 / 281 KB，2026-10-11），采集脚本新增 U+FFFD 断错防复发 |
| **2026-10-10** | round30 记账的 biz-mock 测试数 | `10 + 66 + 345 + 45 = 466` | **实读 67**（源码未动、重跑仍是 67，那行的 66 是按 `64+2` 推的）；历史读数不动，更正登记在 `docs/EVIDENCE.md` |
