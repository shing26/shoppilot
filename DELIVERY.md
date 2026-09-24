# 交付契约

本仓库的**入口层**。一屏读完，用途只有一个：让任何人在 60 秒内知道「这个项目有什么、在哪、到哪一步了」。

本文件**不复述内容**，只做索引。数字与判据的唯一入口是 [`docs/EVIDENCE.md`](docs/EVIDENCE.md)。

---

## 五件索引

| # | 回答的问题 | 本仓落在哪 | 状态 |
| --- | --- | --- | --- |
| ① | 为什么做它？解决什么？放弃过什么？ | [`CHARTER.md`](CHARTER.md)（业务背景与痛点、架构指标、里程碑）<br>[`PLAN.md`](PLAN.md)（架构形态、主链路、排期）<br>[`docs/adr/`](docs/adr/)（**44 编号 / 43 篇**，0022 有意预留） | 具备 |
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

## 当前状态（2026-09-24）

- **发布**：`v1.0.0` 已冻结（tag → `7f4334c`，CI run `35104751284` 验证）
- **未发布**：`v1.0.0` 之后 **51 个提交**，属 round15 – round19 五轮
- **当前轮**：无进行中的轮次。最近一轮是 round19「可信性观测补齐」，**已收口**。round17 起的三轮重开都是[显式的政策覆盖](docs/adr/0044-round19-reopen-for-trust-observability.md)（依次为 [0033](docs/adr/0033-round17-reopen-for-architecture-completeness.md) / [0041](docs/adr/0041-round18-reopen-for-scoring-dimension-completeness.md) / 0044），不满足 ADR 0031 的触发条件，均已诚实记录为覆盖，且**不自动续期**
- **三条未达标红线**（缓存拦截率、吞吐峰值、未命中 TTFT）**照挂不摘**，判据与归因见 `RELEASE.md`。**不通过改口径、阈值或 Mock 参数刷绿**
- **round19 的三条未达成**同样照登：活体针对性步未真跑、全量 22 步矩阵本轮不跑、输出上限是否影响 180 条 gold 读数未验证

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
| `pom.xml` 版本 | `0.1.0-SNAPSHOT` | **A** 改 `1.0.0` —— 与 tag 对齐，声明"仓内即已发布版本"<br>**B** 改 `1.1.0-SNAPSHOT` —— 承认 `v1.0.0` 之后有 51 个未发布提交，正在走向下一个版本<br><span class="st">脚本已全部用通配符（`shoppilot-gateway-*.jar`），两条路都不会打断启动链</span> |

### 已登记的口径说明（非缺陷）

| 项 | 说明 |
| --- | --- |
| 正式 tracker 位于 `.scratch/shoppilot-mvp/` | README 第 839 行明确了这是**有意为之**（「不是临时草稿目录」），`.gitignore` 也已为 `.scratch/**/*.md` 开白名单。<br>保留记录的原因：任何**按目录名扫仓库**的自动工具都会漏掉它——`sk` 类工具与外部审计脚本尤甚。若要保持，建议在 tracker 自身 README 顶部再加一行说明。 |

### 已修正（2026-09-20）

| 项 | 原值 | 现值 |
| --- | --- | --- |
| README 的 ADR 范围陈述（第 835 行） | 「ADR 0001-0030」 | 「ADR 0001-0040（0022 未占用）」 |
| README 的票号范围（第 38、839 行） | 「票 01-32」 | 「票 01-41」 |
| 入口层 | 无 | 本文件 |
