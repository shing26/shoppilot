# Changelog

本文件按 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 的类别收敛。**版本单位是「轮」**——每轮对应一批实现单元、每项关键决策挂一篇 ADR；逐轮的完整台账（spec、实现单元、逐条读数与未达成项）不在本文件展开，入口见下表。

| 索引 | 位置 |
| --- | --- |
| 逐轮台账与实现单元 | [`.scratch/shoppilot-mvp/README.md`](.scratch/shoppilot-mvp/README.md) |
| 决策记录 | [`docs/adr/`](docs/adr/)（编号 0001-0069，0022 有意预留未占用） |
| 指标证据与未达成项 | [`docs/EVIDENCE.md`](docs/EVIDENCE.md) |
| 发布声明与冻结策略 | [`RELEASE.md`](RELEASE.md) |

---

## [Unreleased]

`v1.0.0` 之后的工作（round16 – round35，逐轮依据见 [`docs/PROJECT_PLAN.md`](docs/PROJECT_PLAN.md) 的冻结策略与 [`.scratch/shoppilot-mvp/`](.scratch/shoppilot-mvp/README.md) 台账）。

### Added

**Agent 与对话链路**

- 情绪门：词典层 0 token 定案，高情绪先于意图判定转人工；dev 口径 LLM 分类兜底（ADR 0034/0043）。
- 有序 Plan 步骤：前序依赖表达式 + 前步失败即中止 + 步骤分账指标（ADR 0036）。
- Prompt 版本化：外置版本资产 + meta fail-fast + 响应携带版本（ADR 0037）。
- 满意度反馈闭环：显式点踩落库 + 隐式信号计数 + 复核工单流转（ADR 0039）。
- 涉资动作不可抵赖：模型只有草案权（`PENDING_APPROVAL`），放行必须是已验签坐席账号并落审计（ADR 0047/0063）。
- 极性守卫：语义缓存命中的词法反义复核，反义对 8/8 不互命中（ADR 0016）。
- 检索融合 0 token 录放回归门：由录制序重算融合比对 top-K（ADR 0049）。
- **有界工具循环 2 → 4 轮 + 读工具一次反思重试**（round35 / ADR 0069）：上限是硬上限（超限照旧 `TOOL_ROUNDS_EXHAUSTED` 落工单），读工具的③操作性失败（超时/不可用）由系统把失败事实回填、让模型换一种做法再试一次——占同一份轮次预算、按工具合计 1 次、写工具不反思、**任何闸都先于反思判定**；三态（触发 / 不触发 / 次数用尽）可枚举可判（`plan[].attempt` / `reflection` + `shoppilot_reflection_total{outcome}` + trace 的 `reflection=<tool> attempt=<n>` 行）。
- **T002 定责转人**（R7，2026-10-20）：门店细则「看照片不能定责」的活鲜定责诉求由 T0 确定性升级（`Intent.ESCALATE`），落 `INTENT_UNRESOLVED` + 可查工单；规则按租户收口（平台 `fresh-06` 反过来要求商家按照片折算，gold 的 T001 同句有负例断言）。

**业务域与多租户**

- 工单统一实体：四个来源同表 + 历史行回填，跨租户读写隔离用例钉死（ADR 0055）。
- 分流规则表（数据不是代码）+ 优先级三档 + SLA 计时。
- Redis Streams 审计事件骨干 + 工单 resolve 出站投递：按渠道投递、`event_id` 幂等、只有 2xx 算送达、失败落回执工单（ADR 0054/0059）。
- 工单与坐席服务独立成第四服务 `:8092`，数据随服务迁移（ADR 0053）。

**前端**

- 坐席工作台 `/workspace/`：真账号登录、工单队列、领取/处理/释放、退款审核面板（ADR 0057）。
- 买家中心 `/buyer/`：登录 + SSE 会话 + 我的工单；买家自己的工单端点只认买家 JWT。
- 两个前端构建产物入库，CI 逐字节校验（改源码必须重建提交）。

**身份与凭据治理**

- 身份域：`users` 表 + bcrypt + `/auth/login|register|me` + 角色守卫；自报身份头全退役（ADR 0056/0058）。
- 凭据姿势守卫下沉共享库：回环默认值 WARN，非回环 + 默认令牌/空凭据拒启（ADR 0062）。
- 飞书长连接：官方 SDK WebSocket 客户端 + 事件归一接入 + 事件录放门（0 token 夹具哈希钉死）；零暴露红线——不开新端口、无入站回调、无隧道（ADR 0060/0064/0065）。

**持久化与运维**

- Postgres 持久档：迁移集平级目录 + LOB 映射矩阵钉死 + 容器档默认持久（ADR 0061）。
- 备份恢复演练：`pg_dump -Fc` → `DROP FORCE` → restore → 授权重放，API/LOB/授权三层 14/14（脚本即 runbook）。
- 按库用户隔离：`bizmock_app` / `ticket_app` 各自拥有、跨库 FATAL 双拒。
- 容器档：四服务镜像 + compose profile + 两份克隆并存在端口右移后实测通过。
- 验证分档（daily / live / full）：判据逐字相同、切步骤；活体矩阵 28 步。

**交付层**

- README 工程验证索引、三十秒架构图、「防线开 vs 关」数字看板、两段现场录像（采集/合成脚本入库，自带断错）。
- 故障复盘：静默失败的三种形状（[`docs/incident-2026-10-silent-failure-modes.md`](docs/incident-2026-10-silent-failure-modes.md)）。
- 架构走读与架构 FAQ（[`docs/design-walkthrough.md`](docs/design-walkthrough.md)、[`docs/architecture-faq.md`](docs/architecture-faq.md)）。

### Changed

- 定位改写：从「面试作品」到「可运行的客服系统雏形」（ADR 0052；范围红线继承自 ADR 0024）。
- 四服务拆分：gateway / biz-mock / ticket / 共享 tool-api，跨进程 HTTP 边界带内部凭证（ADR 0002/0053）。
- CI 门禁从五步扩到九步（+task 判据 / +检索录放门 / +告警规则测试 / +两个前端产物校验），0 token 不变。
- 指标名计数换代 41 → 55 → **56/58**（round35 票 145 新增 `shoppilot_reflection_total{outcome}`；三模块 56 / 四模块 58）；token 计量加 `source=provider|estimate` 标签（名字不变 → 计数不变）；`shoppilot_plan_steps_total{steps}` 值域随 4 轮出现 `3`/`4`。
- dev 档 token 日预算 260000 → **600000**（round35 票 149；闸门本体保留，ADR 0012 一字未改）。
- 验证判据持续收紧：覆盖率棘轮、告警规则 `promtool test rules` 两侧断言、套件夹具门。

### Fixed

- **CI 前端构建产物漂移**：改了前端源码未重建入库产物导致 main 自 round30 起持续红；产物重建提交后恢复九步全绿（run 38080802513）。
- **静默失败三连**：事件骨干从未在真 Redis 上跑通而门禁全绿——`MONITOR` 取证定位配置缺失/写死/流名当组名三个缺陷；新增 0 token 配置一致性判据（首次运行即抓到第三缺陷）。
- SSE 响应头显式声明 `charset=UTF-8`：未声明时按区域嗅探的客户端（Chromium CDP）会按系统区域解码产生双重编码乱码。
- 「转人工」情绪门回归（ADR 0042）、退款审核读回、追问无取消出口、`/auth/login` 缺 `username`、`up.ps1` 运行中打包换瘦包等活体验收抓出的产品缺陷。
- **写动作守卫漏吃行业门**（round35 票 146 / 台账 R11）：非电商租户的办理类诉求会触发纠偏一枪、还给模型「直接调用 `applyRefund` 把它真的办掉」的指令（与 prompt 第 7 条冲突、有诱导谎报风险）——`expectedWrite` 在非电商租户置空，纠偏与「写挂起超限」两条路径一并吃上门。
- **响应 token 字段不是全量**（round35 票 145 / 台账 R12）：预算检查后收尾只报最后一跳、`fallback()` 与受理出口恒 0、缓存命中不记入口层——逐请求 `TokenUsage` 总账一次收口（响应字段仍不是计费口径，成本读指标增量）。
- **反思失败被吞**（R14-A，2026-10-20）：读工具③失败进入反思后，模型若不重试、直接作答，失败曾以 `degraded=false` 的普通回答收尾（无 fallback、无工单）——收尾前检查失败是否被任何一次成功派发解决；未解决则强制 `TOOL_UNAVAILABLE` + 工单（ADR 0009）。新增 2 格 JVM，变异对照实测为真。
- **跨店同问同答**（round34 / ADR 0068）：平台缓存桶的共享前提从「引用全为平台条款」收紧为「本行业该意图不存在店铺级条款」（`ShopRuleIndex` 探针，读写同源、失败 fail-closed）——修掉「先有人问过，别家门店细则就永不生效、甚至本店答案被顶掉」；同批修空召回把模型自由发挥当答案（政策意图 0 条且未降级 → 确定性转人工 + 工单），以及 `citationDetails` 命中路径缺出处两处契约错位。新增活体门禁 `verify-store-isolation.py`（矩阵 29 步）。
- **B3 审计「谁放的钱」在 Redis 流路径丢失**（round34 补记 / 票 132）：`RedisAuditChannel.toMap` 漏写 `actorAuthenticated`、`fromMap` 回落旧构造，凡走流的审计事件一律落成「未认证」——资金动作的责任人查询面分不出来；补字段 + 十参构造 + 线路往返测试（活体直证修复前 `auth=False` → 修复后 `auth=True`）。
- 验收工装三处漂移（round34 补记 / 票 133）：`verify-refund-approval.ps1` 的默认口令/审计端点/PS7 数组形状/action 名、`verify-workspace.mjs` 的旧选择器；`console` 的 staff-only 读面缺口登记待裁定。
- **调试台退款面板读不到队列**（round34 补记 / 票 134，R9 裁定 ①）：round30 起队列读取为 staff-only，而调试台只有 buyer 的 mock 令牌——面板恒 403。补坐席账号登录路（footer，口令用完即清、令牌只在页面内存），面板保持只读并指向坐席工作台；`console` 门禁重写为「未登录可读原因 → 表单登录 → 只读形态」，44/44。

新增：**两店交替压测参数组**（`storemix`：`--tenants/--tenant-mode` + locust `alternate` 逐请求轮转）——稳态命中率无差异、代价在足迹（两店 26 vs 21 个 L1 key，+24%），口径边界见 `docs/EVIDENCE.md`。

### Performance

- 命中路径 P99 22 ms（200 并发，模型调用增量恒为 0）；单请求 token 412.3 vs 关防线 1096.8（**-62.4%**）。
- 虚拟线程：400-800 并发时延 **-64/-65%**；100-200 并发无收益。
- 吞吐拐点 1013-1141 QPS、错误率 0%（判据 ≥1200 未达成，归因与原始产物见 [`docs/EVIDENCE.md`](docs/EVIDENCE.md)）。

## [1.0.0] - 2026-09-17

首个冻结版本：多租户客服网关（意图级联 / 两级缓存 / 有界 Agent / 降级工单）+ 业务中台 + 评测与 CI 门禁。范围边界、冻结策略与重开条件见 [RELEASE.md](RELEASE.md) 与 [ADR 0030](docs/adr/0030-round14-closure-scope-and-reopen-triggers.md)。

## 未达成与登记项

未达成指标**保留红值、判据、归因和限制**，逐条见 [`docs/EVIDENCE.md`](docs/EVIDENCE.md)；
登记不执行项（每笔要么有触发条件、要么有「永不做」裁决）见 [`.scratch/shoppilot-mvp/registered-debt.md`](.scratch/shoppilot-mvp/registered-debt.md)。
