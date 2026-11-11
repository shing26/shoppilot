# 票 212：embedding sidecar 落地——未命中路径切精简向量服务（TTFT 达标路径）

Round: 49（spec：[round49-spec-ttft-embedding-review.md](../round49-spec-ttft-embedding-review.md)）
ADR: 0085（Accepted，2026-11-11 所有者确认）　Status: implemented（2026-11-11）　Blocked by: 无
契约：[plans/212-embedding-sidecar.md](../../plans/212-embedding-sidecar.md)（2026-11-11 冻结）

## What to build

裁定形态 = A（精简 sidecar 保 bge-m3），spike 已过线（`eval/results/spike-embedding-sidecar-20261111.txt`）：

1. **sidecar 进程**：FastAPI + ONNX int8（`Xenova/bge-m3` `model_int8.onnx`）+ CLS 池化，
   **固定 8 线程**（spike 实测默认 16 线程同机争抢劣化 2.4x）；模型权重与依赖进 Docker 镜像
   （568 MB 权重 + ~1.2 GB RSS 运行账，按实测数记，ADR 0058 换代指针写明理由）。
   **容器形态已在 spike 预演通过**（`eval/results/spike-embedding-sidecar-20261111.txt` 容器段）：
   python:3.11-slim + volume 挂模型 = 579 MB 镜像 + 1.178 GiB 运行内存，宿主→容器 HTTP
   短 novel P50 35.5 ms 过线；两个实现坑照登——基础镜像走 `docker.m.daocloud.io`
   （docker.io 直连 500）、容器内绑 `0.0.0.0` 而非 127.0.0.1（否则端口映射 Empty reply）。
2. **网关切换**：`EmbeddingClient` 未命中路径的查询向量化指向 sidecar（HTTP）；
   T1 质心、L2 写回等非关键路径调用是否同切由实现时按延迟读数定，票面留痕。
3. **指标分账不变式**：`shoppilot_embedding_latency_seconds{result=remote}` 口径保留
   （仍叫 remote，指向变了），票 47 的逐桶计数不变式进用例；不新增第四桶。
4. **L2 工作点显式验收项**：int8 hit@5 已 120/120 保序（spike 附录），但绝对余弦会轻移——
   L2 0.95 工作点与极性守卫阈值带**修前/修后两组读数**判定；有漂移才重标定，且重标定是
   显式验收项不是顺手调；无漂移则原值不动。
5. **接线**：`docker-compose.yml`（full profile）与 `scripts/up.ps1` 各加 sidecar 服务定义；
   sidecar 不可达时行为 = 沿用现 Ollama 路径（降级有账，不静默换实现）。

## 边界

- 不改 TTFT `<500 ms` 判据、Mock 300 ms 夹具、gold、`judge()`、降级枚举、分母。
- 不改 `knowledge/` 语料；不重入库；不推进纪元（向量同模型同维，Qdrant/ES 库原样可用——
  这是保 bge-m3 形态的最大收益）。
- 不动 ADR 0001/0011/0058 原文，换代指针在收口时由票 213 登记。
- Allowed paths / Verification commands 在开工前按验收链路冻结进 `plans/212-*.md`。

## Verify

```bash
bash ./mvnw -B -ntp verify                 # 782 tests / 131 suites 基线，新增代码配用例
python scripts/ttft_attribution.py         # 修前/修后：embedding 段 355.7 → ≤84 ms，TTFT 均值 ≤500
python scripts/probe_embedding_latency.py --samples 10   # Ollama 口径仍在（对照）
python scripts/run_sse_ttft.py             # 未命中 TTFT sweep 判据读数
git diff --check && git status --short
```

容器档活体：compose full profile 起 sidecar，未命中首答走 sidecar，`cacheLayer=NONE`
场景 TTFT 实测进 `loadtest/results/`。

## 验收项

- 未命中路径 embedding 段 ≤84 ms（服务端计时），TTFT 判据 sweep 达成或如实照登。
- 分账三桶不变式用例绿；`result=remote` 计数在 sidecar 路径上增长。
- L2 0.95 工作点/极性守卫修前修后读数留档，处置（原值/重标定）有记录。
- sidecar 降级路径（进程不可达 → Ollama 回落）有用例或活体证据。
- ADR 0058 换代指针理由 + RSS 实测记账。

## Handoff notes

（2026-11-11 实现，全部 Verification commands 执行，读数达标，验收项逐条对照见下。）

**关键决策**

1. **切换面**：`EmbeddingClient.embed()` 的两条路（no-embedding-cache 直接路径与 loadOnce
   singleflight 发起者）都经新的 `embedRemote()`：`query-base-url` 非空先打 sidecar、失败回落
   `base-url` 重试一次并记 `shoppilot_embedding_fallback_total`；`embedWarmup()` 不经过
   `embedRemote()`，永远打 `base-url`（重试逻辑原样）。票面第 2 点「T1 质心、L2 写回是否同切按
   延迟读数定」——**未切**（保守方案）：T1CentroidLayer / IngestRunner / StartupInitializer 全部
   走 embedWarmup 路径，离线链路的可靠性不押在新进程上；sidecar 段实测 46.3 ms 已远低于预算，
   不需要为再省几 ms 把离线链路也押上去。
2. **`result=remote` 口径逐字未动**：计数与计时的分法、桶名、维度校验全不变，变的只是远程指向。
   回落成功时 remote 桶记的是「sidecar 一趟 + Ollama 一趟」的合计——那是调用方真实等到的耗时，
   与计时器既有语义（含失败调用）一致。
3. **sidecar 模型分发 = 构建期下载**（hf-mirror，`HF_ENDPOINT` 可覆盖），镜像自包含；README 写明
   本机已有 spike 权重时挂卷 `.scratch/spike-sidecar/model:/app/models:ro` 可跳过下载。权重不进 git
   （`.gitignore` 忽略 `shoppilot-embedding-sidecar/models/`）。onnxruntime pin 1.19.2、
   `SPIKE_THREADS` 默认 8、容器内绑 0.0.0.0——三条 spike 坑全部落在 Dockerfile/app.py 默认值里。
4. **compose 容器档 gateway 默认指向 sidecar**（`SHOPPILOT_EMBEDDING_SIDECAR_URL` 可覆盖），
   gateway `depends_on` sidecar healthy；sidecar mem_limit 1536m（RSS 实测 ~1.2 GB 留余量）。
   `scripts/up.ps1` 本机档：Docker 可用则 `docker compose --profile full up -d --build
   embedding-sidecar` 并给网关进程导出 URL，Docker 不可用打印跳过说明（回落 Ollama 是既有路径，
   不让 sidecar 缺席打红起栈）；进程环境已显式给过该变量时不自动接线（.env 手工配置原样生效）。
5. **`ConfigValidationTest.applicationPlaceholderSetIsPinned`** 按其登记家法把
   `SHOPPILOT_EMBEDDING_SIDECAR_URL` 加进 pinned 清单（新 env 面登记进清单，不绕门禁）。
   测试数 782→790（新增 8 条），README badge/复现段/CI 段与 TUNING_PLAN 计数按
   verify_doc_truth.py 的锚点机械同步（round43 同款处理，判据/夹具/gold 零触碰）。

**验证落点（全部实际执行）**

- `bash ./mvnw -B -ntp verify`：BUILD SUCCESS，四模块 **12 + 92 + 636 + 50 = 790 tests / 132
  suites**，0 失败（782 基线 + 8 条新增：EmbeddingSidecarRoutingTest 8 用例——sidecar 直达 /
  500 回落计数 / 维度错回落计数 / 连接拒绝回落 / 回落也失败抛既有异常 / embedWarmup 不受
  sidecar 配置影响 / sidecar 路径仍记 remote 桶 / singleflight 发起者也走 sidecar）；
  覆盖率棘轮门在 verify 内通过。
- `python -I scripts/verify_doc_truth.py`：文档真值 OK（790/132，README badge、复现段、CI 段、
  TUNING_PLAN、STATUS 全一致）。
- `pwsh -NoProfile -File scripts/check-ps-syntax.ps1`：**本机没有 pwsh 7**（PS 5.1 下
  ParseFile 把 UTF-8 无 BOM 脚本当 GBK 读，报全量乱码假错）。以 UTF-8 输入等价门
  （ReadAllText UTF-8 + Parser::ParseInput，同一套 scripts\*.ps1 集合）验证：**35 files / 0
  errors**，up.ps1 改动在列。pwsh 7 安装后应复跑原命令——留给现场追问。
- `python scripts/ttft_attribution.py`（perf 档本地 JVM + sidecar 容器 :8110，单连接 45 s
  强制未命中）：**embedding 段（result=remote 服务端计时）46.3 ms（修前 355.7，预算 ≤84 达标）；
  服务端 TTFT 均值 472.7 ms（修前 771.5，判据 ≤500 达标）**；稠密 7.4 / 词法 41.6 / 编排余量
  77.4 ms。`shoppilot_embedding_fallback_total` 全程 **0**（85 次 remote 全部直达 sidecar，
  零回落）。产物 `loadtest/results/ttft-attribution-20261111-081438-sidecar-1conn.csv`
  （+原始时间戳同名件）。
- `python scripts/run_sse_ttft.py --connections 1 --duration 45 --force-miss --policy-only
  --label sidecar`：knowledge 桶 n=89，**p50 423 / p90 725 ms**（修前 attrib 口径 762/1046）。
  产物 `loadtest/results/sse-ttft-20261111-081604-1-sidecar.csv`。
- **L2 工作点修前/修后读数（阈值零改动）**：hit 层分布修前 L1=0/L2=4/FLIGHT=0/NONE=50
  （`sse-ttft-20261111-020944-1-attrib.csv`），修后 L1=0/L2=2/NONE=83（081438）与
  L1=0/L2=9/NONE=89（081604）。int8 查询向量对 fp16 缓存向量的 L2 语义命中在修后**仍然发生**
  （两个窗口 2/85 与 9/98，夹住修前 4/54，样本噪声量级）——跨运行时自相似 ~0.987 > 0.95 与
  spike 预测一致；`shoppilot_cache_l2_polarity_blocked_total` 全程 0（207 次 L2 查找）。
  **无边界漂移，0.95 工作点原值不动，无重标定。**
- 负载窗口说明：归因跑在 `mvnw verify` 结束后的窗口；同机仍跑着容器中间件栈
  （ES/Qdrant/Redis/postgres/biz-mock/ticket 容器）+ Ollama + sidecar 容器，无构建进程。
  属 spike「重负载窗口」与「轻负载窗口」之间的中等负载，读数 46.3 ms 仍远低于 84 ms 预算。

**三个现场追问**

1. 容器档 gateway 现在默认指向 sidecar（AC 5 的接线）——若运维想把 full 档退回纯 Ollama，
   `.env` 里 `SHOPPILOT_EMBEDDING_SIDECAR_URL=`（空）即可，回落路径在但 sidecar 不再被依赖；
   要不要给 down.ps1 加「停 sidecar」的显式选项？（现状：`--profile full up/down` 随档起停，
   未单独处理。）
2. `check-ps-syntax.ps1` 的 pwsh 7 依赖在本机缺失：本次用 UTF-8 输入等价门顶过（35/0），
   等价脚本在会话临时目录、未入库。要不要把「pwsh 不可用时降级 ParseInput」写进
   check-ps-syntax.ps1 本身（PS 5.1 兼容），还是维持「必须装 pwsh」的现状不动？
3. 长句（32 字）novel 在重负载窗口曾超线（spike 复验 100.6 ms），本轮判据 sweep 的 7 条问句
   全 ≤10 字不受影响；若未来把长句问句纳入判据 sweep，sidecar 段在重负载下的 p90（52-83 ms）
   是否仍达预算需要单独复测——票 213 收口或 round50 要不要把负载窗口写进归因产物的固定字段？
