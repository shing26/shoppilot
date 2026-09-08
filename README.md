# ShopPilot

面向电商大促的高并发智能客服与业务网关：**静态政策 RAG + 动态业务 Tool Calling + 两级缓存 + 降级工单**，
跑在 Java 21 虚拟线程与 Spring Boot 3.3.5 上。

它要回答的不是"能不能对话"，而是大促洪峰下的四个工程问题：重复热点怎么不进模型、
对话怎么真的改成订单、语义缓存怎么不把反义问句串到一起、外部依赖挂了怎么兜住。

四个问题的答案都在下面，而且每条都带实测数字与证据文件——**没达成的也照写**。

| | |
| --- | --- |
| 命中路径 P99 | **22 ms**（200 并发，perf 模式；100 并发 32 ms，400 起进入排队） |
| 缓存总拦截率 | **74%**（L1 主导模型）/ **78%**（任务书 80% 命中口径）— 判据 ≥80%，**未达成**，归因见下 |
| 吞吐峰值 | **1013 QPS** @800 并发，错误率 0% — 判据 ≥1200，**未达成**，归因见下 |
| Token 节约率 | **62.4%**（实测，不是任务书里的 75%） |
| 虚拟线程收益 | 400-800 并发 **+64%**；100-200 并发无收益 |
| 降级路径 | 7 种 reason 全部可脚本复现，且每种都落成可查工单 |

完整口径与逐条对照：[docs/loadtest-report.md](docs/loadtest-report.md)、[docs/threshold-calibration.md](docs/threshold-calibration.md)。

![调试台](docs/console.png)

## 快速开始（一条命令）

前置：Docker Desktop、JDK 21、Ollama、PowerShell 7（`pwsh`）。中间件端口全部偏移并只绑 `127.0.0.1`，
不会和你机器上的别的项目抢 6379/6333/9200/8080。

```powershell
git clone <本仓库> ShopPilot; cd ShopPilot
pwsh -NoProfile -File scripts/up.ps1          # 起中间件 -> 拉模型 -> 构建 -> seed -> 入库 -> 起服务
start http://127.0.0.1:8082                   # 调试台
```

`up.ps1` 里每一步都可重入：容器已在跑就跳过，seed 非空即跳过，入库是幂等 upsert。
就绪判定用 Spring Boot 的 readiness 健康组（`/actuator/health/readiness`）：`ApplicationRunner`
跑完之前端口已经开着，但 `/actuator/health` 会返回 503 `OUT_OF_SERVICE`，脚本因此不会在
biz-mock 还在 seed 5 万单、网关还在预热 bge-m3 的时候就把流量放进来。
要停：`pwsh -NoProfile -File scripts/down.ps1`（加 `-Containers` 连中间件一起停，数据卷保留）。

<details>
<summary>手动分步（想知道 up.ps1 到底干了什么，或者不想用 pwsh）</summary>

```powershell
docker compose up -d                              # Redis 16379 / Qdrant 16333 / ES 19200
ollama pull bge-m3                                # embedding，1024 维，全程恒定
ollama pull qwen2.5:3b                            # local 模式的生成模型
mvn -o -DskipTests package                        # 干净机器去掉 -o 联网取依赖；或用 .\mvnw.cmd
pwsh -NoProfile -File scripts/start-bizmock.ps1   # :8091，seed 3 租户 / 200 买家 / 5 万订单
pwsh -NoProfile -File scripts/ingest.ps1          # 30 篇政策 -> 90 规则块 -> ES + Qdrant，推进 kb_epoch
pwsh -NoProfile -File scripts/start-gateway.ps1 -Profile local
```

三种运行模式（ADR 0001）：`local` = Ollama `qwen2.5:3b`（默认，演示与降级验证）、
`dev` = DashScope `qwen-plus`（工具调用准确率评测，需要 `Copy-Item .env.example .env` 填
`SHOPPILOT_LLM_API_KEY`）、`perf` = `MockLLMClient` 固定延迟（压测，只衡量网关编排层）。
换模式：`pwsh -File scripts/start-gateway.ps1 -Profile dev`。
`.env` 由启动脚本读进来注入服务进程（Spring Boot 自己不认 `.env`），
已经导出到环境里的同名变量优先，所以 CI 与实验脚本不需要 `.env` 也能覆盖配置。

</details>

## 三条演示

```powershell
pwsh -NoProfile -File scripts/demo.ps1                 # 三条连着跑
pwsh -NoProfile -File scripts/demo.ps1 -Which cache    # 只看一条：cache | isolation | fallback
```

1. **缓存拦截**：同一句"发什么快递"问两次。第一次打模型，第二次 `cacheLayer=L1`，
   脚本当场把 `shoppilot_llm_calls_total` 的前后增量打出来——命中路径的模型调用增量是 0。
2. **串号防线**：A 店买家问自己的 `90001`，B 店买家问同一个单号。B 店只得到"未在本店找到该订单"，
   不返回 403（403 等于承认这单存在）；伪造 token 直接 401；body 里塞 `tenantId` 被忽略并告警。
3. **降级转人工**：现场注入 `failRate=1.0`（经网关运维代理，脚本碰不到 biz-mock 的内部凭证），
   下一次物流查询不返回 500，而是同一条 SSE 通道里推 `fallback{reason=TOOL_UNAVAILABLE, ticketId}`，
   随后按工单号能从队列里读回这条工单。

界面版演示：浏览器开 `http://127.0.0.1:8082/`，右侧时间线逐帧显示状态机转移，
底部滑块就是同一个故障注入接口，右上抽屉是工单队列（认领/结单可点）。

## 架构

```
                浏览器 / App  （只跟 :8082 同源说话）
                          |
                 +--------v---------+
                 |  网关 :8082      |  Java 21 虚拟线程 / Spring Boot 3.3.5
                 |  AuthFilter      |  HS256 验签 -> TenantContext（身份只来自 token）
                 |  限流            |  Redisson 令牌桶：店铺配额 + 买家/IP，判定在编排之前
                 |  AgentStateMachine|  10 状态显式枚举，SSE 帧由转移产生
                 +---+----+----+----+
                     |    |    |
     L1 Redis 精确哈希  |    |   +--> Qdrant 1024 维  ┐
     L2 向量语义缓存 ────┘    |     ES BM25 倒排     ┴─ RRF(k=60) 混合检索 -> top5 注入 Prompt
                             |
                    +--------v---------+        HTTP + X-Internal-Token（跨进程边界，ADR 0002）
                    | biz-mock :8091   |        H2 + Spring Data JPA，@TenantId 行级隔离
                    | orders logistics coupons refunds tickets
                    +------------------+
```

| 层 | 选型 | 为什么是它 |
| --- | --- | --- |
| 网关与编排 | Java 21 + Spring Boot 3.3.5 | 虚拟线程让"每个请求一路阻塞式 HTTP"在 IO 密集下不再吃线程池（实测 +64%） |
| 意图与工具 | 自研 10 状态机 + Function Calling | 三级级联判定（规则 / 质心 / 模型），工具循环硬上限 2 轮（ADR 0007、0008） |
| 缓存 | Redis 7 + Qdrant | L1 零向量化才能守住 30 ms；L2 带 `tenant/scope/intent/kb_epoch` 强制过滤（ADR 0003） |
| 检索 | Qdrant 稠密 + ES 倒排 + RRF | 双引擎各有短板，融合成本 60 行代码（ADR 0010） |
| 业务侧 | H2 + Spring Data JPA | Mock 的是业务系统而不是业务逻辑：状态机、归属校验、幂等约束都是真的 |
| 通道 | Spring MVC `SseEmitter` | 单向推流 + 断线重连够用；POST SSE 需要自带读流（浏览器 `EventSource` 不支持 POST） |
| 模型依赖 | dev / local / perf 三模式 | 数字归属清晰：吞吐类指标只算编排层，推理成本另开一条曲线（ADR 0001） |

## 请求主链路

```
INTAKE      验签 -> TenantContext；归一化；实体正则扫描
TRIAGE      T0 规则 -> T1 向量质心 -> T2 模型（仅不确定时），10 意图，动作优先
            不确定即不准入缓存（fail-closed）
CACHE_READ  仅政策意图：L1 精确哈希（零向量化）-> miss 才向量化 -> L2 语义
            key = MD5(tenantId + scope + intent + kbEpoch + normalizedQuery)
            L2 filter = {tenant_id, scope, intent, kb_epoch}，cosine >= 0.95 + 极性守卫
RETRIEVE    Qdrant dense top20 + ES BM25 top20 -> RRF(k=60) -> top5
PLAN        tools 全量下发，模型返回 tool_call 即意图证据，全程一次模型请求
TOOL_EXEC   HTTP -> biz-mock，超时/熔断，硬上限 2 轮
SLOT_ASK    必填槽位缺失就追问，绝不猜订单号；两轮拿不到 -> 转人工
REPLY       流式输出 -> done 带 citations
CACHE_WRITE 异步写回，先过七道资格判定（降级话术、空答案、用过工具的都不写）
FALLBACK    7 种 reason -> 落可查工单
```

命名约定：`L1`/`L2` 只指缓存两级，`T0`/`T1`/`T2` 只指意图判定三级，两套序号不混用。

SSE 事件契约（10 个事件，客户端判定失败的唯一依据是"没收到 `done`"）：

```
meta | status | token | done | tool_executing | tool_result
slot_ask | fallback | duplicate_submit | rate_limited
```

## 指标与口径

**这张表逐条写"怎么量的"，未达成的照写未达成。**所有数字来自 `loadtest/results/` 下的产物，
表格由 `python scripts/build_loadtest_report.py` 生成，本文不誊写第二个数字。
吞吐与延迟类指标衡量的是**网关编排层**，生成侧是固定延迟的 Mock，不含模型推理（ADR 0001）。

| 指标 | 判据 | 实测 | 口径 | 证据 |
| --- | --- | --- | --- | --- |
| 缓存总拦截率 | ≥80% | **73.2%-74.0%** | (L1+L2+穿透合并)/有效请求，计数器差值；L1 主导模型，100-1600 并发 | `ladder-l1-perf-20260908-231233-final.csv` |
| 同上（任务书口径） | ≥80% | **77.8%-78.2%** | 80% 热点重复 + 10% 业务 + 10% 长尾 | `ladder-mix80-perf-20260908-233023-final.csv` |
| 命中路径 TP99 | <30 ms | **32 / 22 / 90 / 480 / 840 / 970 ms**（100/200/400/800/1200/1600 并发） | 派生事件 `cache[hitpath]` 分位数；该路径模型与远程向量化增量恒为 0 | 同上 + `verify-hit-zero-llm.ps1` |
| 未命中 TTFT | <500 ms | **592 / 743 / 2436 ms**（P50/P90/P99，500 长连接） | 服务端收请求→首个 token 帧，不含网络往返；Mock 首字本身 300 ms | `sse-ttft-20260909-001822-500-perf.csv` |
| 吞吐极限 | ≥1200 QPS 且错误率<0.1% | **1013 QPS**@800（L1 主导）/ **1141 QPS**@400（L2 主导），错误率 0% | `qps_scope=chat-only`，Locust 4 进程同机发压 | `ladder-l1-…-final.csv`、`ladder-l2-…-l2.csv` |
| L2 路径吞吐 | 报天花板与归因 | **22.8-27.1 QPS**（每请求真打 bge-m3）vs 1141 QPS（有进程内向量缓存），差 **约 40 倍** | profile `perf,no-embedding-cache`，远程向量化调用≈请求数；生产侧解法见 ADR 0011：embedding 拆独立批处理服务 + 向量缓存命中率当一等指标 | `ladder-l2-perf-20260909-012151-l2emb.csv` |
| 虚拟线程收益 | 开关两组 | 400 并发 **+64%**、800 并发 **+65%**；100 并发 -3%、200 并发 -3% | 同模型同并发，只关 `spring.threads.virtual.enabled` + 200 平台线程池 | `ladder-l1-perf,no-virtual-20260909-011351-novirtual.csv` |
| Token 节约率 | 关缓存基线对比 | **62.4%**（1096.8 → 412.3 token/请求）；拆开：穿透合并单独省 50.3%，缓存再省 24.4% | perf 模式 `shoppilot_llm_tokens_total` 差值/请求数，三档只差防线开关；token 由 Mock 按模板估算 | `ladder-l1-perf,nocache,nosf-…`、`ladder-l1-perf,nocache-…`、`ladder-l1-perf-…-cacheton.csv` |
| 工具调用准确率 | 分意图选对工具与填对参数各 ≥95% | **local 模式已测**（POLICY 100%、ACTION_ORDER 72.2/75.0%、LOGISTICS 88.9/87.5%、ADDRESS 66.7/60.0%、REFUND 66.7/62.5%、ESCALATE 61.1%、UNKNOWN 83.3%，格式为"选对工具/填对参数"）；**dev 模式待补** | 180 条人工校对用例（10 意图 × 18），对抗样本实测 40%；缺槽位的期望是追问而不是猜 | `eval/results/tool-eval-20260908-141504-local{-summary.csv,.csv,-meta.json}` |
| 语义缓存阈值 | 0.85-0.99 扫描 + 反义对不互命中 | 0.95 工作点**召回实测 0**；同桶反义最大余弦 **0.9682**，由极性守卫兜 | 118 组对抗对，向量层与系统层分开报 | `docs/threshold-calibration.md`、`docs/threshold-sweep.csv` |
| 混合检索质量 | hybrid 优于 dense-only | 16/16 与 16/16：**该语料上两者打平**，dense 已全中 | 同一次检索里两路前 5 对期望文档的命中名次 | `docs/retrieval-comparison.md` |
| HikariCP 饱和点 | 记录饱和点与调池前后差异 | 池 2/10/30 三档 QPS 差 **≤1.2%**；池=2 时 pending 峰值 39、获取均值 6.5 ms，池=10 起 pending 全程 0 → **任务书"默认 10 连接先于 CPU 饱和"未被实测支持** | 流量模型 `biz`（100% 业务办理），每档 45 s，池大小经 `hikaricp.connections.max` 反读确认 | `ladder-biz-perf-20260909-033940-pool2.csv` 等三份 |
| 写操作幂等 | 100% | 50 并发同 token → 库里 1 行；绕过网关直插由 DB 唯一约束拒 | 幂等键四元组 + `uk_refund_idempotency` | `verify-idempotency.ps1`、`TenantIsolationAndIdempotencyTest` |
| 降级路径 | 每种可复现且落工单 | **7/7** 帧内 ticketId 都能在工单队列里查回 | 故障注入全部经网关运维代理；脚本对每个工单号做队列反查 | `verify-fallback.ps1`、`FallbackReasonTest` |
| 可复现性 | 新机器照 README 一条命令起栈并跑通三条演示 | **同机通过，干净机器未验**：`down.ps1` → `up.ps1`（profile=local）起栈，`demo.ps1` 三条演示全过，调试台 15/15 | 起栈脚本按"中间件→模型→构建→seed→入库→网关"顺序等 health，每步可重入；起栈与演示的输出落在本机 `logs/`（不入库） | `scripts/up.ps1`、`scripts/demo.ps1`、`scripts/verify-console.mjs` |

![压测曲线：QPS 拐点与分位数时延](docs/loadtest-curves.png)

图由 `python scripts/plot_loadtest_curves.py` 生成，读的是 `loadtest/results/` 里同一批阶梯 CSV；
左图对数轴上三条曲线在 1000 QPS 附近压平，而"每请求真打一次 bge-m3"那条只有 20 多 QPS。

未达成的三条（拦截率、吞吐、TTFT）归因写在一起，不逐行重复：

- **拦截率 74-78% 而不是 80%**：分母里 15% 业务办理 + 15% 长尾按设计根本不准入缓存（动作意图进缓存等于把
  一次性状态当政策），可准入的 70% 里 L1 吃掉绝大部分，L2 在 0.95 工作点召回为 0（`docs/threshold-calibration.md`
  的实测）。要把 80% 凑上，得把分母改成"准入内命中率"——那个数确实是 100%，但换个口径刷绿没有意义，
  所以两列都留着。
- **吞吐 1013 而不是 1200**：发压机（Locust 4 进程）与被压网关在同一台 16 G 笔记本上，
  峰值档 CPU 采样 94-98%、最低空闲内存 0.0 GB，拐点由两边共同决定。网关侧错误率全程 0，
  虚拟线程对照组在 800 并发下反而只有 615 QPS。这条要在真机上复核需要一个独立发压节点。
- **TTFT 592 ms 而不是 <500 ms**：500 条长连接同样由同机发起，Mock 首字延迟自己就占 300 ms，
  剩下 292 ms 是排队与调度；同机限制同上一条。

## 验收对照

四条否决项（任一不过项目不算完成）：

| 否决项 | 判据 | 状态 | 证据 |
| --- | --- | --- | --- |
| 串号防线 | 跨租户同意图 0 次互命中；跨店查询不泄露 B 店字段 | **通过** | `verify_l2_filters.py`（租户/scope/意图/纪元四类过滤）、`verify-action-loop.ps1` 第 3 段、`verify-polarity.ps1` 8/8 |
| 写操作幂等 | 并发 50 同 token 仅 1 条；Redis 停机由 DB 唯一约束兜 | **通过** | `verify-idempotency.ps1`、`IdempotencyServiceTest`、`TenantIsolationAndIdempotencyTest` |
| 降级可复现 | 七种 reason 稳定触发且各有可查工单 | **通过** | `verify-fallback.ps1` 7/7、`FallbackReasonTest` |
| 身份不可伪造 | 无 token/伪造/过期 401；body 或参数带 tenantId 被忽略并告警 | **通过** | `AuthFilterTest`、`demo.ps1 -Which isolation`、`IdentityArchitectureTest` |

四条否决项的判据模式写的是 `dev`，实际证据取自 `local`/`perf` 与 JVM 用例。理由：这四条防线的正确性与
用哪家模型无关（越权与幂等发生在业务系统与仓储层），把它们绑在需要外部 API key 的模式上反而更弱。
需要 DashScope key 的是承诺项里的"工具调用准确率"，这一条**尚未补测**。

## 已知限制（不藏）

- **H2 内嵌库在写密集路径上是瓶颈**；50 并发同 token 的退款实测 1 行 + 49 个重放，但换 MySQL 才是生产形态。
- **跨实例 singleflight 只在单实例环境验证过**。Redis `SETNX` 那层写了、测了，但没有两个网关实例跑真实流量。
- **身份提供方是 mock 的**：验签逻辑真（HS256、过期、错签名都拒），发 token 的接口是演示入口（ADR 0014）。
- **政策语料是 LLM 生成后人工校对的合成数据**，不是真实平台条款；90 个规则块要测的是链路而不是召回率上限。
- **ES 停在够用级、不做 rerank 是判断不是未完成**：16 条查询上 dense-only 已经 16/16，精排没有可证明的收益，
  这条对比表就放在 `docs/retrieval-comparison.md`，打平也照登。
- **0.95 工作点上 L2 语义缓存召回实测为 0**：bge-m3 在短中文同意图改写对上最大余弦 0.9435。
  80% 级拦截由 L1 精确哈希与穿透合并承担，L2 只兜"同一政策、写法极近"这一小撮。
- **L2 那条曲线的名字要打折看**：压测语料只有 36 个不同问句，改写对在 L1 里就被精确吃掉了，
  所以 `ladder-l2-…` 里 `l2_delta` 全程为 0，它实际测的还是 L1。真正的语义缓存代价在 `no-embedding-cache` 那组。
- **`local` 模式的 3B 模型倾向用自然语言追问槽位而不是发 function call**，动作用例的参数抽取准确率因此偏低；
  网关侧用"ACTION 意图 + 首轮没调工具 + 没产出业务事实时自己派生工具"兜住链路，
  但 `MODIFY_DELIVERY_ADDRESS` 的 7 个槽位仍交模型抽，正则方案实测会把能办的单子一路问成转人工。
- **压测与发压同机**（见上一节），峰值 QPS 是网关与发压器的共同上限。
- **2 轮工具上限只在活体链路上跑到**：压测与 `verify-action-loop.ps1` 的事件序列证明它生效，
  但没有一条 JVM 内用例直接断言"第 3 轮会被拒"。
- **这台机器上 JVM 会在高并发下无日志消失**：凌晨两次 400 并发的业务重放后网关进程凭空不见，
  没有 `hs_err_pid*.log`、没有 Windows 应用日志条目、stderr 为空。同一档在 23:12 那轮跑到了 1600 并发
  且错误率 0。归因未定，但压测脚本每档都做健康与计数器单调性检查，宁可中止阶梯也不留下负差值的废数据
  （`scripts/run_loadtest.py` 的 `gateway_healthy`）。
- **perf 模式的 token 数由 Mock 按提示模板估算**，62.4% 的节约率要在 dev 模式重放同一流量模型复核真实计费 token。
- **网关侧没有 JVM 内端到端用例**：端到端验证靠 `scripts/verify-*.ps1` 打活体服务（真跨进程），
  代价是 `mvn test` 不覆盖它；`@SpringBootTest` 只在 biz-mock 侧。
  本机还有一条约束：服务在跑的时候 `mvn package` / `mvnw verify` 会被 fat jar 文件锁挡住
  （`Unable to rename ...jar to ...jar.original`），改完代码要先 `scripts/stop.ps1` 再构建；
  `mvn -o test` 不受影响。
- **biz-mock 整个进程消失时，降级帧里没有工单号**：工单存储就在 biz-mock（ADR 0009），
  `FallbackService.escalate()` 只能留下 warn 日志，网关侧没有本地暂存队列可补投。
  依赖只慢不挂（`failRate=1.0`）的场景工单照样能落，所以这是"下游彻底没了"这一格的缺口，
  PLAN 对 ticket 10 的判据（返回降级语义而非 500）仍然成立。
- **Qdrant 容器 healthcheck 长期报 `unhealthy`**（容器内 wget 访问 `/readyz` 超时），实测读写正常。
- **本机 `mvn` 与 `mvnw` 的本地仓库不同**：`mvn` 走 `E:\maven_repository`，wrapper 走 `~/.m2`。
  离线复现时统一加 `-Dmaven.repo.local=E:\maven_repository`，干净机器联网首跑无此问题。

## 考虑过但否决的方案

| 岔路口 | 选了 | 代价与理由 |
| --- | --- | --- |
| 缓存放在链路最前面（任务书原意） | 放在意图判定之后 | 少拦 6 个点。前置缓存必然跨意图串：`怎么退款` 与 `为什么不给退款` 余弦 0.9682（ADR 0003、0016） |
| 一个 embedding 客户端直接远程调用 | 进程内 LRU + 每问句穿透合并 | 实测冷缓存踩踏打出 35264 次远程请求全失败；合并后 18 次，拦截率从 0 回到 77.6% |
| biz-mock 做成网关里的一个包 | 独立进程 + 内部凭证 | 多 3 秒启动、多一个端口。同进程里"顺手读个字段"就能绕过租户传递，跨进程才逼出显式身份（ADR 0002） |
| 前端 `EventSource` | `fetch()` + `ReadableStream` 自带 SSE 解析 | 手写 40 行解析。`EventSource` 不支持 POST，body 传不了复杂对话请求 |
| 租户 = 平台 / 组织（含糊） | 租户 = 店铺，平台走 `scope`，跨店物理不聚合 | 术语先定死才有后面的三道防线；平台级政策共享一份而不是按店铺复制 N 份（ADR 0004） |
| 下游从裸 Header 读租户 | 身份只来自验签结果 + 架构测试守住单点 | body 里塞 `tenantId` 被忽略并告警；能自选租户的话后面所有行级隔离都是装饰（ADR 0005） |
| 自由 Agent 循环直到任务完成 | 工具循环硬上限 2 轮 + 10 状态 | 脱离时延预算谈自由循环是在线网关的自杀式写法（ADR 0008） |
| 把阈值降到 0.90 换 L2 召回 | 保住 0.95，另加词法极性守卫 | 0.85 处 precision 只有 0.231：召回是拿误命中换的，而误命中就是串号 |
| 接厂商 SDK | OpenAI 兼容端点 + 自写 HTTP 客户端 | 少 200 行依赖，换来 dev/local 同一条调用路径（ADR 0012） |
| Redis 不可用时拒绝一切写操作 | 照常执行，交给 DB 唯一约束 | 幂等存储是加速器不是正确性来源；为它把大促写操作全拒掉是本末倒置 |

## 面试讲法（STAR）

三条，每条先摆矛盾，再讲决策，最后给数字。

1. **任务书写的 80% 拦截率，我实测 74%。** 矛盾在于任务书把"语义缓存"当成主力，而实测表明主力是 L1 精确哈希
   与穿透合并：0.95 工作点上向量语义缓存的召回是 0，同意图改写对的最大余弦只有 0.9435。
   决策是不改口径、不改阈值，而是把防线拆开报（总拦截率 / 准入内命中率两列都在），
   同时给同桶反义补一个词法极性守卫（ADR 0016），因为 0.9682 那一对确实越过了 0.95。
   数字：74%（L1 主导）/ 78%（80% 热点口径）/ 命中路径 P99 22 ms 且模型调用增量为 0。
2. **一次踩坑：拦截率突然从 78% 掉到 0。** 定位下来是冷缓存雪崩：每个未命中请求都自己去打远程 embedding，
   8 分钟里打了 35264 次且全部失败。我加了两层——进程内向量 LRU、同问句穿透合并（等待者拿完整结果，
   不做 token 流广播），并把降级路径改成"检索引擎失败**不算**零命中"，
   否则一次抖动会被写成 60 秒负标记，自己把服务关成不可用。
   数字：远程向量化 35264 → 18 次，拦截率 0 → 77.6%，投毒计数改为显式计数器 `negative_suppressed_total`。
3. **业务闭环的时延预算比"聪明的 Agent"重要。** 10 状态显式枚举 + 工具循环硬上限 2 轮 + 缺槽位最多追问一次，
   超预算一律转人工并落**可查工单**（不是日志里一行字）。七种降级原因每种都有脚本能稳定复现。
   数字：业务办理链路 12/12 断言通过；50 并发同幂等 token 只产生 1 条退款；虚拟线程在 400-800 并发省 64-65% 时延。

## 复现

```powershell
# 单元与架构测试（57 项）
mvn -o test
# 压测全矩阵（阶梯 + SSE + 虚拟线程对照 + token 基线 + 连接池），每组带环境记录
pwsh -NoProfile -File scripts/run_experiment_suite.ps1                    # 全跑，约 40 分钟
pwsh -NoProfile -File scripts/run_experiment_suite.ps1 -Only l1,sse       # 只跑两条
python scripts/build_loadtest_report.py --strict                          # 由产物生成 docs/loadtest-report.md
python scripts/plot_loadtest_curves.py                                    # 画 docs/loadtest-curves.png（需 matplotlib）
# 验收脚本（对着活体服务跑）
pwsh -NoProfile -File scripts/verify-hit-zero-llm.ps1   # 命中路径零模型、零远程向量化
pwsh -NoProfile -File scripts/verify-action-loop.ps1    # 查得到 / 问得出 / 越不了权（12 项）
pwsh -NoProfile -File scripts/verify-fallback.ps1       # 七种降级原因 + 可查工单
pwsh -NoProfile -File scripts/verify-idempotency.ps1    # 并发同 token 与状态前置校验
pwsh -NoProfile -File scripts/verify-ratelimit.ps1      # 同步 429 与 SSE rate_limited
pwsh -NoProfile -File scripts/verify-polarity.ps1       # 反义对不互命中（要求 local/dev 模式）
node scripts/verify-console.mjs                         # 调试台 15 项（Playwright）
```

### 逐 ticket 验收动作 → 覆盖命令

PLAN.md 的 19 行动作都有机器可执行的落点，顺序、退出码、日志位置由一条命令固定：

```powershell
pwsh -NoProfile -File scripts/run-acceptance.ps1            # 构建 + 起栈 + 全部验收 + 三条演示
pwsh -NoProfile -File scripts/run-acceptance.ps1 -SkipBuild # 用现成 jar，只跑活体验收
```

| PLAN 行 | 覆盖它的命令 |
| --- | --- |
| 01 | `run-acceptance.ps1` 的 stop / build / unit / stack 四步（`mvnw verify` + `mvn -o test` + `up.ps1`）；`verify-plan-actions.ps1` 第 01 段判"三中间件在跑、两服务健康 UP" |
| 02 | `AuthFilterTest`、`verify-action-loop.ps1` 第 3 段、`demo.ps1 -Which isolation` |
| 03 | `verify-plan-actions.ps1 -WithRestarts`：重启 biz-mock，等 readiness 放行后再比两次 seed 的订单总数 |
| 04 | `verify-plan-actions.ps1` 第 04 段：活体 ES/Qdrant 条目数相等，再用上一轮入库日志的纪元与条数证明重跑不增长 |
| 05 | `verify-plan-actions.ps1` 第 05 段（SSE 逐帧、`done` 带非空 citations、同步端点同答案）、`verify-console.mjs` |
| 06 | `verify-hit-zero-llm.ps1` |
| 07 | `verify_l2_filters.py`（intent / tenant / scope / kb_epoch 四条 must-filter 各自独立生效）、`verify-polarity.ps1` |
| 08 | `python scripts/retrieval_compare.py` → `docs/retrieval-comparison.md` |
| 09 | `verify_l2_filters.py` 的纪元段（推进纪元后旧缓存点不再命中）、`python scripts/calibrate_intent.py` |
| 10 | `verify-plan-actions.ps1 -WithRestarts` 第 10 段：进程级 kill 之后仍以同一条 SSE 通道回降级语义 |
| 11 | `verify-action-loop.ps1`（两轮闭环 / 缺槽追问 / 跨店越权） |
| 12 | `verify-idempotency.ps1`、`IdempotencyServiceTest` |
| 13 | `verify-plan-actions.ps1` 第 13 段（逐发归因：被 429 的请求零模型调用）、`verify-ratelimit.ps1` |
| 14 | `verify-fallback.ps1`（七种 reason 各有可查工单）、`verify-plan-actions.ps1 -WithRestarts` 第 14 段（死端点） |
| 15 | `node scripts/verify-console.mjs`（Playwright 15 项，含"页面拿不到内部 token"） |
| 16 | `python scripts/run_tool_eval.py` → `eval/results/tool-eval-<时间>-<模式>.csv` 与 `-summary.csv` |
| 17 | `python scripts/calibrate_threshold.py` → `docs/threshold-sweep.{csv,png}` 与 `docs/threshold-calibration.md` |
| 18 | `run_experiment_suite.ps1` → `loadtest/results/`（每组一份 `env-*.json`）+ `build_loadtest_report.py` |
| 19 | 得由没参与的人照本页跑一遍才算；机器侧最接近的是 `run-acceptance.ps1 -Only stack,demo` |

各 ticket 的实现决策与"当时能答上来的三个追问"记在 `.scratch/shoppilot-mvp/issues/`，
汇总清单：`python scripts/collect_interview_questions.py` → [docs/interview-qa.md](docs/interview-qa.md)。

压测与标定脚本要一份独立 venv（起栈与三条演示不需要它）：
`python -m venv .venv-loadtest` 后 `pip install -r loadtest/requirements.txt`，
`run_experiment_suite.ps1` / `run_loadtest.py` 默认取 `<repo>\.venv-loadtest\Scripts\python.exe`。
`verify-console.mjs` 需要 Playwright：本机装在 `<repo>\.tools\node_modules`（不入库），
跑之前 `$env:NODE_PATH="<repo>\.tools\node_modules"` 指过去即可，脚本用 `createRequire`
读它，所以仓库里不需要一份 package.json。

## 目录

```
shoppilot-gateway/     网关：状态机、三级意图判定、两级缓存、混合检索、限流、降级、调试台
shoppilot-biz-mock/    业务中台：orders / logistics / coupons / refunds / tickets，@TenantId 行级隔离
shoppilot-tool-api/    纯契约 jar：10 意图枚举 + Function Schema + 工具 DTO（网关与 biz-mock 共用）
loadtest/              locustfile（四种流量模型）与 results/（保留 ladder-*.csv 与 env-*.json）
docs/adr/              16 份架构决策记录，正文里每处 ADR 编号都能点进去
docs/                  阈值标定、意图标定、检索对比、压测报告、面试问答清单
knowledge/             30 篇政策语料
scripts/               up/down/start/stop、ingest、demo、verify-*、run_loadtest、实验矩阵、报告生成
CHARTER.md  PLAN.md  CONTEXT.md
```
