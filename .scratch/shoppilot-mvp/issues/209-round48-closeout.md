# 票 209：round48 收口

Round: 48（spec：[round48-spec-quality-closure.md](../round48-spec-quality-closure.md)）
ADR: 0083　Status: implemented　Blocked by: 200-208

## What to build

1. **登记换代**：
   - `docs/current-metrics.json` 的吞吐行按票 208 的公平发压复测改判（1468 QPS@800、
     1351 QPS@1200），条件里同时保留 Locust 同机旧读数与差额归因；
   - 缓存、TTFT 两行保留红值与判据，只补 2026-11-05 的复测读数与结构性上限说明；
   - 工具准确率行按 2026-10-27 全量 dev-167 / 定向 refund-168b / local-180b 三份产物重写，
     不再保留 2026-09 的旧读数。
2. **生成物**：`python scripts/gen_status.py` 重生成 `docs/STATUS.md`；
   `python scripts/build_loadtest_report.py` 重生成 `docs/loadtest-report.md`，
   新增“轻量发压器交叉验证”一节与结论改写；不手改两个生成物。
3. **台账**：`README.md` 的指标表（吞吐 / 工具准确率）与“未达成”归因段同步；
   `docs/EVIDENCE.md` 补票 208/209 条目；`docs/TUNING_PLAN.md` 补阶段 4 决策与阶段 5 证据。
4. **tracker**：round48 行改为 **done**，票范围 200-209；索引补 208/209。
5. **边界**：不开始 round49；不启用 Stage3 路由；不重置 token 预算键；
   不把任何未复跑的分项写成已达成。

## Verify

```bash
python scripts/gen_status.py
python scripts/verify_doc_truth.py
python scripts/gen_status.py --check
python scripts/build_loadtest_report.py
git diff --check
git status --short
```

## 验收项

- round48 行与索引能直接看出 200-209 全部 `implemented`、本轮已收口；
- `docs/STATUS.md` 与 `docs/loadtest-report.md` 均由脚本生成，`--check` 通过；
- 四项指标每项一句话说清“达成 / 不可达 / 读数过期”，并指到产物；
- 残余项（付费复跑、TTFT 架构、缓存裁决、独立发压节点、旧修订块清理、cassette 扩容、
  Stage3 转正）逐条有名有触发条件，不写“待跟进”；
- 工作树不混入 `logs/`、`hs_err_pid*.log`、`replay_pid*.log`、过期快照与 `.scratch/ux-probe/shots/`。

## Handoff notes

**本轮十张票的最终状态**

| 票 | 内容 | 状态 |
| --- | --- | --- |
| 200 | 文档真值同步 | implemented |
| 201 | LLM 录放回归门 | implemented（6 张卡） |
| 202 | 答案级评测 | implemented（56/60，4 条缺口照登） |
| 203 | 编排器拆分 | implemented（1768→73 行） |
| 204 | Testcontainers 集成层 | implemented（本机无 Docker，8 条 skip；nightly 待实跑） |
| 205 | 前端安全网 | implemented（Vitest 30 条；活体冒烟未跑） |
| 206 | 检索/路由决策 | implemented（120 条夹具已录） |
| 207 | 审计五问残余 | implemented（#4 已测，残余如实登记） |
| 208 | 四项指标复测 | implemented（吞吐改判；缓存/TTFT 不可达；退款待全量复跑） |
| 209 | 本轮收口 | implemented |

**四项指标的最终口径**

| 指标 | 结论 | 一句话 |
| --- | --- | --- |
| 吞吐 | **达成（复测口径）** | 同一网关同时段：轻量发压器 1468@800 / 1351@1200，Locust 1015@800；差额在发压侧 |
| 缓存率 | **不可达** | 任务书 mix80 的可缓存占比 78.1%，准入内命中率 100%；80% 需要改判据或改 ADR 0003 准入 |
| 未命中 TTFT | **不可达** | 300 Mock + 253–311 embedding + 15 检索 + 104 编排 ≥ 672 ms；需 ADR 0011 的独立 embedding |
| 退款意图 | **代码已修、读数过期** | dev 定向 18/18；全量 94.4% 早于票 168；local 3B 9/18 是模型不调工具 |

**验证与文档换代**

- `docs/current-metrics.json`：吞吐行改判；缓存/TTFT 补条件；工具行换代。
- `docs/STATUS.md`：`python scripts/gen_status.py` 重生成；`--check` 通过。
- `docs/loadtest-report.md`：`python scripts/build_loadtest_report.py` 重生成；
  新增轻量发压器一节，结论区分“历史 Locust 上限”与“同日公平复测”。
- `README.md`：指标表吞吐/工具两行、两处“未达成”归因段同步。
- `docs/EVIDENCE.md`：票 208/209 两条（含 381,709 token 事故与模式修复）。
- `docs/TUNING_PLAN.md`：阶段 4 判定不进入微调；阶段 5 回归证据。

**残余与触发条件**

1. **dev 全量 180 复跑**（退款官方读数）：需所有者批准付费 token（约 100 万）；
   触发 = 所有者同意后的下一次 dev 评测窗口。
2. **TTFT 达标**：需 ADR 0011 的独立 embedding 批处理服务或更小向量模型重入库；
   触发 = 生产侧决定为 embedding 单列资源，或允许重跑检索质量评测。
3. **缓存 80% 裁决**：ADR 0030 第 4 条；触发 = 所有者二选一（改判据 / 放开准入）。
4. **独立发压节点复核**：当前 1468 是本机同租读数；触发 = 有第二台机器可发压。
5. **旧知识修订块清理**：Qdrant/ES 112 vs 语料 111（`rev-KR1793566139828-0`）；
   触发 = 下一次改 ingest 清理逻辑。
6. **cassette 扩容 / 答案级 56→60**：审计第 1 条残余；触发 = 允许录制 180 gold 的 token 预算。
7. **Stage3 路由转正**：影子已落地默认关；触发 = 所有者接受政策组 3.3→10.1 s SLA
   且 ≥200 轮真实流量 + 容量门通过。
8. **Testcontainers 与前端活体冒烟**：本机无 Docker / 8082 未起；触发 = nightly runner 或下一次起栈。

**三个现场追问**

1. **为什么吞吐能改判、缓存不能？** 吞吐的差额被同日对照实验定位到发压器（同网关同时段，
   只换客户端）；缓存的差额在分母里，换客户端不会让 22% 非准入流量变成可命中。
2. **381,709 token 怎么发生的？** `-Profile perf` 只设了 Spring profile，`.env` 的
   `SHOPPILOT_LLM_MODE=dev` 优先级更高，实际走了 qwen-plus；超过 20 万默认预算后熔断接管。
   已修启动器，perf 现在强制 `llmMode=perf`；预算键保留不清零。
3. **下一轮先做什么？** 若所有者批准付费复跑，先做 dev 全量 180（唯一能把退款行改绿的动作）；
   否则先做 TTFT 的 embedding 方案评审——它是剩下两项里唯一有工程解的。

## 2026-11-06 收口后追加（所有者裁决执行）

收口当夜三条"待定事项"全部执行完毕，本票状态不变（implemented）：

1. **dev 全量 180 复跑已执行**（所有者批准付费）：合并读数 **179/180 = 99.4%**，
   ACT-RFD-13 转绿（ACTION_REFUND 18/18）；唯一红点换为 ACT-ORD-14（qwen-plus 行为漂移，
   gold 原句 0/5 发工具、口语变体 12/12）。总 token：全量 444,848 + 定向补跑 382,264；
   中途 biz-mock 宿主进程静默死亡造成 ACTION 段环境红，已按环境红排除后补跑。
   残余 1 的"触发条件"（所有者批准）已兑现，退款红点闭合。
2. **缓存 80% 裁决 = 改定义**：ADR 0084，判据换「准入内命中率 ≥80%」（实测 100%），
   ADR 0030 第 4 条闭合。残余 3 关闭。
3. **round49 方向 = TTFT/embedding**：所有者选定，等 round49 spec 开工。残余 2 进入射程。

token 账目：20261106 键累计 1,208,821（含 11-05 误用 381,709，无清零）。
