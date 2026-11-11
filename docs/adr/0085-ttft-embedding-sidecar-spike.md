# 未命中 TTFT 的 embedding 形态：精简 sidecar 保 bge-m3，spike 定生死

Status: Accepted（2026-11-11 所有者确认；spike 门槛已过，见附录；实施票 = 票 212）
Date: 2026-11-11
Deciders: 所有者（票 211 评审产出）
Round: 49（spec：`.scratch/shoppilot-mvp/round49-spec-ttft-embedding-review.md`）

## Context

未命中 TTFT 判据 `< 500 ms` 长期挂红（`docs/STATUS.md`）。2026-11-11 归因刷新（perf 档本地网关、
54 次强制未命中、单连接串行，`loadtest/results/ttft-attribution-20261111-020944-1conn.csv`）：

```text
服务端 TTFT 均值 771.5 ms
  - Mock 首字下限            300.0 ms（perf 夹具，禁改）
  - 新问句向量化（服务端计时） 355.7 ms   ← 唯一可攻击段
  - 稠密检索                  6.8 ms
  - 词法检索（ES）            40.0 ms
  = 网关编排余量              69.0 ms
```

判据预算给 embedding 段约 **84 ms**（500 − 300 − 6.8 − 40 − 69）。两口径探针
（`loadtest/results/probe-embedding-20261111.txt`，本机 Ollama 串行）：

| 模型 | 同句重复 P50 | 新问法 P50 | 维度 |
| --- | --- | --- | --- |
| bge-m3 | 379.8 ms | **335.0 ms** | 1024 |
| nomic-embed-text | 69.7 ms | **74.3 ms** | 768 |

关键事实：bge-m3 的 7 字文本（novel P50 388.3 ms）≈ 32 字文本（384.9 ms，各 10 次、补测段落盘）——
**延迟与文本长度无关，是 Ollama 服务开销主导，不是模型算力**。复验跑（同日同口径）bge-m3
382.0/406.0 ms、nomic 76.5/73.3 ms，同量级；历史探针 252.6/277.3 ms（2026-10-22）与本次的差异属
机器负载漂移，历轮都远超 84 ms 预算，结论不变。

## Considered Options

**A. 精简 embedding sidecar（保 bge-m3）** —— 推荐主选，先 spike 后实施。
常驻模型的轻量进程（如 ONNX int8 + FastAPI，非第五个 Spring JVM）专管向量化，网关
`EmbeddingClient` 换指向。质量零风险（同一个模型、同一批向量，120 条检索夹具与 L2 工作点全部
原样有效）。可行性依据：延迟被服务开销主导 → 换掉 Ollama 这层后单条有希望进 84 ms；
但 ONNX CPU 前向的真实耗时未测，**spike 门槛 = 精简服务单条新问法 P50 ≤ 84 ms**，
过线才立实施票，不过线回落 D。代价：+1 进程的内存账（预估 0.6–1 GB），ADR 0058 的
「第五 JVM 500 MB」否决记录针对的是工单域 JVM，本形态需所有者点头推翻并记账。

**B. 换更小向量模型重入库** —— 质量实测出局。
nomic-embed-text（Ollama 本地已有，768 维）：同一候选池 dense hit@5 **98/120 = 81.7%**
（bge-m3 基线 120/120；`eval/results/retrieval-model-compare-20261111-nomic.txt`），
22 条丢失全部是同域细粒度混淆（fresh 池内理赔细则互串）、0 条反超。合格的小型中文模型
（bge-small-zh-v1.5 等）Ollama 官方库没有，需引入第三方模型 + 纪元推进重入库 + 120 条检索夹具
重录 + L2 0.95 工作点与极性守卫阈值重标定 + gold 复跑（付费）。**不选**：质量证据不支持，
判据面变更面最大。

**C. 查询向量预热** —— 覆盖率账不支持达标，登记为产品增强候选。
预热集（120 条检索夹具问句 + gold/评测问句集并集 351 条）对判据 sweep 的 7 条政策问句精确覆盖
**0/7（夹具）+ 3/7（gold 并集）**（算式与逐条明细：
`eval/results/preheat-coverage-20261111.txt`）。预热基准题本身 = 改测量不改能力，不诚实地跨过
这条线。作为「高频问句秒答」的产品能力另行登记（不在本轮达标路径内）。

**D. 维持红值照登** —— 合法兜底。归因账已三段计时器齐备，红值有完整归因与结构性上限说明。

## Decision（草案）

**主选 A：先做 spike（精简 sidecar 单条延迟实测，门槛 84 ms），spike 过线立实施票
（网关 `EmbeddingClient` 指向 sidecar + `result=remote` 分账口径保留 + 内存账入 ADR 0058 换代指针）；
spike 不过线回落 D，红值照登。B、C 不进入本轮。**

待所有者确认后 Status 改 Accepted；确认前不动任何实现代码。

## Consequences

- spike 是下一张票的第一格：只测延迟（起一个 sidecar 进程嵌 112 块语料规模的真实文本），
  不接网关、不改判据面；实测数据回填本 ADR 的附录后再立实施票。
- 若 A 落地：TTFT 账变为 300 + ~0–84 + 6.8 + 40 + 69 ≈ **416–500 ms**，判据首次具备达成路径；
  `shoppilot_embedding_latency_seconds{result=remote}` 口径不变（仍叫 remote，指向变了），
  票 47 的逐桶计数不变式继续有效。
- 若回落 D：TTFT 行保持红值，本 ADR 把「结构性上限」的归因从 bge-m3 精确到 Ollama 服务层，
  README 的未达成归因段同步。
- Ollama 仍承载生成模型（local 档）与 bge-m3（T1 质心、L2 写回等非关键路径调用继续走 Ollama；
  只有未命中路径的查询向量化切 sidecar——切换面在实施票冻结时定）。

## 附录：spike 实测（2026-11-11，门槛已过）

完整读数与复现命令见 `eval/results/spike-embedding-sidecar-20261111.txt`；模型
`Xenova/bge-m3` `onnx/model_int8.onnx`（int8，568 MB），onnxruntime 1.19.2，本机 16 核
（同机跑着 Docker 全栈与 Ollama 的真实争抢环境）：

- **进程内前向**：默认 16 线程短 novel P50 65.9 ms（争抢劣化）；**固定 8 线程 27.0 ms**（p90 46.8）、
  长 32 字 novel 45.6 ms、repeat 20.5 ms。
- **真实 HTTP 服务形态**（FastAPI+uvicorn sidecar，含环回往返，n=30）：短 novel **P50 34.7 ms /
  p90 38.9 ms**、长 novel 51.6 ms、repeat 28.1 ms。
- **门槛判定：过线**（34.7 ≪ 84，p90 也在线内；判据 sweep 7 条问句全部 ≤10 字，短句口径即判据口径）。
  对照 Ollama 同模型 novel P50 335–406 ms，改善 ~10x；落在 ADR 0011 的 20–60 ms CPU 口径区间内。
- **int8 质量抽检**：同候选池 dense hit@5 **120/120 与 fp16 完全一致**、int8 独有命中 0——量化
  未打乱 120 条夹具 dense 序。边界：hit@5 保序 ≠ L2 0.95 工作点不漂，绝对余弦的显式验收项归实施票。
- **资源账修正**：sidecar 进程 RSS 实测 **~1.2 GB**（超本文预估 0.6–1 GB 上沿），ADR 0058 的推翻
  记账按实测数进实施票。
- 实施票（票 212）的形态以此为准：sidecar + `EmbeddingClient` 未命中路径切换 + L2 工作点显式
  验收项；立票已就绪，执行等所有者对裁定（A）的确认。

────────────────────────────────────────────
【附录追加：实施落地读数（2026-11-11，票 212；只追加不改上文）】

票 212 按本裁定 A 落地（切换面：`EmbeddingClient.embed()` 切 sidecar、`embedWarmup()` 留
Ollama；T1 质心与 L2 写回**未切**——保守方案，离线链路可靠性不押新进程）。活体归因
（perf 档本地 JVM + sidecar 容器，单连接 45 s 强制未命中，负载窗口 = 容器栈 + Ollama + sidecar
在跑、无构建进程）：

- **embedding 段（`result=remote` 服务端计时）46.3 ms**（修前 355.7，判据预算 ≤84 达标；
  `shoppilot_embedding_fallback_total` 全程 0——85 次 remote 全部直达 sidecar，零回落）。
- **服务端 TTFT 均值 472.7 ms**（修前 771.5，判据 ≤500 达标）＝ Mock 300 + 向量化 46.3 +
  稠密 7.4 + 词法 41.6 + 编排余量 77.4 ms。
- **L2 工作点（显式验收项，阈值零改动）**：hit 层分布修前 L1=0/L2=4/NONE=50，修后
  L1=0/L2=2/NONE=83 与 L1=0/L2=9/NONE=89——int8 查询 × fp16 缓存的 L2 语义命中修后仍发生
  （两个窗口夹住修前比率，样本噪声量级），极性守卫 0 拦截（207 次 L2 查找）。无边界漂移，
  0.95 工作点原值不动、无重标定。
- 资源记账：镜像 ~579 MB + 权重层 568 MB，运行 RSS ~1.2 GB（与 spike 容器形态 1.178 GiB 一致）。

产物 `loadtest/results/ttft-attribution-20261111-081438-sidecar-1conn.csv`、
`sse-ttft-20261111-081604-1-sidecar.csv`；实现与验证全账见票 212 Handoff notes。
ADR 0058 的换代指针与 RSS 记账按本段实测数归票 213 收口。
