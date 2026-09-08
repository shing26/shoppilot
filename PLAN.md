# ShopPilot 实施计划（细化版）

原始任务书见 `CHARTER.md`。本文件是 2026-09-08 grilling 会话后的可执行版本，决策理由见 `docs/adr/`，术语见 `CONTEXT.md`。

## 定位

面试作品，单机可复现。所有对外宣称的指标必须标注测量口径（ADR 0001）。交付窗口 2026-09-08 至 2026-09-13，无余量。

## 架构形态

```
shoppilot-gateway  :8082  Java 21 + Spring Boot 3.3 + 虚拟线程
                         鉴权 / 限流 / 意图判定 / 缓存准入 / Agent 状态机 / SSE / 降级
shoppilot-biz-mock :8091  Spring Boot + H2 + JPA，仅监听本机，校验 X-Internal-Token
                         orders / logistics / coupons / refunds / tickets + 故障注入
shoppilot-tool-api        工具 DTO 与 OpenAPI schema 契约，Function Schema 与调用签名同源
调试台                     gateway/src/main/resources/static/index.html（零构建链，fetch + ReadableStream 手解 SSE）
中间件                     Redis 7 / Qdrant / Elasticsearch 7.17（够用级）
```

## 运行模式

| profile | LLM | 用途 | 产出指标 |
| --- | --- | --- | --- |
| `dev` | DashScope `qwen-plus`（OpenAI 兼容） | 功能演示、工具调用准确率评测 | 准确率、检索质量 |
| `local` | Ollama `qwen2.5:3b` | 降级链路专项验证（模型不可用时的 fallback） | 降级可用性 |
| `perf` | `MockLLMClient` 固定延迟 | Locust 压测 | QPS、TP99、拦截率 |

Embedding 恒为本地 `bge-m3`（1024 维）。`dev` 设 token 日预算熔断，默认 20 万。

## 请求主链路

```
INTAKE   验签 JWT -> TenantContext；归一化；实体正则扫描
TRIAGE   T0 规则 -> T1 向量质心 -> T2 模型（仅不确定时），10 意图，动作优先
         判定 cacheable / dynamic，不确定即不准入（fail-closed）
CACHE_READ  仅 cacheable：L1 精确哈希（零向量化）-> miss 才向量化 -> L2 语义
            key = MD5(tenantId + scope + intent + kbEpoch + normalizedQuery)
            L2 filter = tenant_id + scope + intent + kb_epoch，cosine >= 0.95
RETRIEVE    Qdrant dense top20 + ES BM25 top20 -> RRF(k=60) -> top5 注入 Prompt
PLAN        tools 全量下发，模型返回 tool_call 即意图证据，全程一次模型请求
TOOL_EXEC   HTTP -> biz-mock，超时/熔断，工具循环硬上限 2 轮
SLOT_ASK    必填槽位缺失追问一次，仍缺 -> ESCALATE；绝不猜槽位
REPLY       流式输出
CACHE_WRITE 异步写回，走资格判定
FALLBACK    六种 reason -> 落工单
```

分层命名约定：`L1` / `L2` 只指缓存两级（精确哈希 / 向量语义），`T0` / `T1` / `T2` 只指意图判定三级（规则 / 质心 / 模型）。两套序号不得混用，代码与文档中不得出现裸 `L1` 指代意图层。

## 写回资格

意图为政策咨询、检索有命中、答案非空且长度达标、未触发降级或转人工、未触发工具调用、无错误标记。条目携带 `intent / tenantId / scope / kbEpoch / sourceRuleIds / modelId`，读取时校验 intent 与 tenant 一致。TTL 6h + 知识库纪元强制失效。singleflight 两层：进程内 `ConcurrentHashMap<String, CompletableFuture>` 合并，叠加 Redis `SETNX` 跨实例合并；等待者收完整答案后一次性推送，上限 2s。

## 安全与隔离

行级逻辑隔离（禁止称物理隔离），三道防线：身份只来自验签结果、仓储层强制归属谓词、越权用例进 CI。订单归属双条件 `order.tenantId == session.tenantId && order.customerId == session.customerId`。跨店不聚合。写操作幂等键 `(tenantId, customerId, action, token)` + 退款单 DB 唯一约束兜底。限流双维度（租户配额 + 买家/IP），被限流走 SSE `rate_limited` 而非裸 429。

## SSE 事件契约

```
meta            {conversationId, intent, cacheHit, cacheLayer, traceId}
status          {state, ts}                      状态机转移即事件
tool_executing  {tool, label}
tool_result     {tool, status, summary}
slot_ask        {slot, question}
token           <text delta>
done            {answerId, citations[], usage{promptTokens, completionTokens}}
fallback        {reason, ticketId?}
rate_limited    {retryAfterMs, message}
```

## 知识入库

30 篇政策 Markdown（LLM 生成 + 人工校对），按标题层级切分为规则块，目标 300-600 字，不重叠。元数据 `ruleType / applicableCategory / scope / tenantId / sourceDoc / effectiveFrom`。`ruleId = hash(sourceDoc + headingPath)` 同时作为 ES `_id` 与 Qdrant point id，重跑幂等 upsert，因此不存在双写一致性窗口。政策变更推进 `kb_epoch` 使旧答案整体作废。

## 指标口径

```
总拦截率      命中数 / 全部有效咨询请求        对齐任务书 80%
准入内命中率  命中数 / 准入缓存的请求数        诊断意图规则表质量
Token 节约率  1 - 实际 token / 关缓存基线 token（cache.enabled=false 重放同一 trace）
TTFT          服务端收请求 -> 写出首个 token 帧，不含网络往返
工具准确率    分意图报告，拆"选对工具"与"填对参数"
```

语义缓存阈值以 0.95 为默认值，并用反义/近义对抗对做 0.85-0.99 扫描标定，输出 precision/recall 曲线；串号防线第一层仍是缓存准入与意图分区，阈值只负责同意图内的表述归一。

## 数据与知识规模

3 租户 / 200 买家 / 5 万订单 / 约 20 万物流节点；30 篇政策文档切分为规则块。seed 脚本幂等可重跑。

## 压测

流量模型两条曲线分开报：L1 主导（55% 文本重复热点 + 15% 口语改写 + 15% 业务办理 + 15% 长尾 RAG）与 L2 主导。QPS/TP99 压同步端点，SSE 端点单独测 500 并发长连接 TTFT 与内存。阶梯 50->1200 并发找拐点。必做虚拟线程对比实验（关闭 + 200 平台线程池 vs 开启）。压测脚本每请求生成新幂等 token。指标经 Micrometer 暴露给 Prometheus，Grafana 导入现成 JVM 看板加两个自定义 panel。

## 排期

| 日期 | 切片 | 停下即可演示的内容 |
| --- | --- | --- |
| 9/8 | S0 骨架 + S1 最细竖切 | 一句话进、政策答案出、SSE 打字机 |
| 9/9 | S2 业务办理 | 查订单/查物流闭环 + 槽位追问 + 归属校验 |
| 9/10 | S3 写操作与防护 | 改地址/退款 + 幂等 + 限流 |
| 9/11 | S4 检索与缓存补全 | ES + RRF + L2 语义缓存 + 两层 singleflight + 写回资格 |
| 9/12 | S5 降级工单 + local 验证 | 故障注入 + 六种 reason + 工单接管 + Ollama 降级链路 |
| 9/13 上午 | S6 评测与压测 | 分意图准确率 + 阈值扫描曲线 + 双曲线 + 虚拟线程对比 + 节约率基线 |
| 9/13 下午 | S7 材料固化 | README + 演示脚本 + STAR 话术 |

排期不可压缩的不是代码量，而是压测重跑轮次（每轮 20-40 分钟，同机不可并行）与每日 1 小时的读码与"三个追问"作答。

## Agent 协作规范

范围恢复的理由见 ADR 0015，三条硬规则：

1. 每个 ticket 收尾产出"关键决策 + 你需要能当场回答的三个追问"，写入 ticket 文件的 `## Handoff notes`。
2. 每天 1 小时读当日产出，优先读状态机转移、缓存准入判定、归属校验三处。
3. agent 不得自行修改 `CONTEXT.md` 术语与 ADR 结论；发现冲突停下来问，不得选一个"能跑通"的写法继续。

工单位于 `.scratch/shoppilot-mvp/issues/`，按依赖序编号，执行入口见 `to-tickets` / `implement` 约定：一次只处理 frontier 上一个 ticket，ticket 之间清空 context。

## 验收标准

四条**否决项**：任一不过则项目不算完成。其余为**承诺项**，未达线必须给出实测值与归因，不得改口径蒙混。

| 类别 | 验收项 | 通过判据 | 模式 | 证据 |
| --- | --- | --- | --- | --- |
| 否决 | 串号防线 | 跨租户同意图改写对 0 次互相命中；跨店订单查询 100% 返回"未在本店找到该订单"且不泄露任何 B 店字段 | dev | CI 越权用例 + ticket 17 对抗对数据 |
| 否决 | 写操作幂等 | 并发 50 次同幂等 token 的退款仅生成 1 条记录；Redis 停机时 DB 唯一约束仍拦住 | dev | 集成测试 |
| 否决 | 降级可复现 | 七种 reason 各有脚本或用例可稳定触发，且每种都落到可查工单 | dev | 故障注入脚本 + 用例 |
| 否决 | 身份不可伪造 | 无 token / 未签名 / 过期 token 均 401；body 或参数携带 tenantId 被忽略并告警 | dev | ticket 02 用例 |
| 承诺 | 缓存总拦截率 | >= 80%（L1 主导模型）；未达则报实测值并归因到准入判定或语料重复度 | perf | 压测 CSV |
| 承诺 | 命中路径 TP99 | < 30ms，且断言该路径零 embedding 调用、零模型调用 | perf | 压测 CSV + 计数器断言 |
| 承诺 | 未命中 TTFT | < 500ms（perf 模式，MockLLM 固定延迟 300ms 首字）；dev 模式只报实测不承诺 | perf | 压测 CSV |
| 承诺 | 工具调用准确率 | 选对工具 >= 95% 且填对参数 >= 95%，分意图报告；任一意图 < 80% 判不通过并写明原因 | dev | 180 条标注集评测 CSV |
| 承诺 | 吞吐极限 | L1 主导模型下 QPS >= 1200 且错误率 < 0.1%；否则报真实拐点 | perf | 阶梯压测报告 |
| 承诺 | L2 路径吞吐 | 报实测天花板并归因到本地 embedding 推理，给出生产侧解法 | perf | 曲线图 |
| 承诺 | 虚拟线程收益 | 开关两组数据，给出 IO 密集场景提升幅度与无提升的场景 | perf | 对比表 |
| 承诺 | Token 节约率 | 关缓存基线 vs 开启缓存，报百分比与基线跑测方法 | perf | 两次跑测 CSV |
| 承诺 | 可复现性 | 新机器照 README 一条命令起栈并跑通三条演示 | dev | 演示脚本执行记录 |
| 承诺 | 数字诚实性 | README 指标表逐条标注测量口径与数据来源文件 | — | README |

### 逐 ticket 验收动作

| ticket | 验收动作 | 通过判据 |
| --- | --- | --- |
| 01 | 新 shell 执行 `docker compose up -d` 后跑 `mvnw verify` | 三中间件健康、两服务 health 为 UP、构建全绿 |
| 02 | 用 A 店 token 与伪造 token 各请求一次 | 前者 200 且上下文身份正确，后者 401 |
| 03 | 跑 seed 两次后查订单总数 | 两次结果一致不翻倍；A 身份查 B 订单返回未找到 |
| 04 | 跑入库脚本两次后统计 ES 与 Qdrant 条目数 | 两边数量相等且重跑不增长 |
| 05 | 页面问"生鲜坏了怎么赔" | 逐字输出、`done` 含非空 citations、同步端点返回同结果 |
| 06 | 同一问题连问两次并看计数器 | 第二次零模型调用零 embedding 调用；降级话术不进缓存 |
| 07 | 用改写句与含订单号句各问一次 | 前者定案政策意图、后者定案动作意图且不准入缓存 |
| 08 | 用"7天无理由"数字写法查询 | hybrid 召回正确规则块，dense-only 对比表落盘 |
| 09 | 改写句二次提问 + 推进纪元后再问 | 纪元前命中 L2、纪元后不命中；跨租户不命中 |
| 10 | 停掉 biz-mock 后问一次物流 | 返回降级语义而非 500 |
| 11 | 问"10023 发货没"与不给订单号各一次 | 前者两轮内出答案，后者触发 `slot_ask` 且绝不猜订单号 |
| 12 | 并发 50 次同 token 退款 + 对 SHIPPED 单改地址 | 仅 1 条退款记录；改地址被状态校验拒绝并说明原因 |
| 13 | 超租户配额连打 | 返回 `rate_limited` 且模型调用计数不增长 |
| 14 | `failRate=1.0` 注入 + 模型端点指向不存在地址 | 分别产出 `TOOL_UNAVAILABLE` 与降级工单；local 两条 smoke 通过 |
| 15 | 浏览器打开页面走完三条演示 | 时间线逐帧正确、故障注入经代理生效、页面拿不到内部 token |
| 16 | 跑评测脚本 | 输出分意图双子指标 CSV，对抗样本占 30% |
| 17 | 跑阈值扫描 | 0.85-0.99 曲线图与数据落 `docs/`，反义对在 0.95 下不互命中 |
| 18 | 跑双曲线 + 对比 + 基线三组 | 每组含环境记录，重跑可复现 |
| 19 | 让未参与的人照 README 跑一遍 | 十分钟内起栈并完成三条演示 |

## 已知限制（写进 README，不藏）

H2 内嵌库在写密集路径上是瓶颈；跨实例 singleflight 已实现但只在单实例环境验证过；身份提供方是 mock 的（验签真、发 token 假）；政策语料为生成数据非真实平台条款；ES 停在够用级、不做 rerank，理由是无精排价值，不是没时间。

## 环境事实

JDK 21 位于 `E:\java\jdk21`（`JAVA_HOME` 当前指向 jdk-18，需按项目覆盖）；Maven 3.9.14 走 aliyun 镜像；Python 3.11.9，Locust 待装；i5-12500H 12C16T / 16 GB。

端口分配（本机既有占用不可动）：8080 nexus-web、8081 与 8090 为其它 java 项目、Docker 的 wslrelay 占住 6379/6333/9200。ShopPilot 因此使用 gateway **8082**、biz-mock **8091**、Redis **16379**、Qdrant **16333**、ES **19200**，全部只绑 127.0.0.1。

## 高概率卡壳点

HikariCP 默认 10 连接在虚拟线程高并发下先于 CPU 饱和；Qdrant 与 ES 的 Docker 内存配额；DashScope tool calling 返回格式细节。任一处卡住半天时，按 ADR 0015 的三类理由判定该不该砍：因打字耗时砍的可恢复，因墙钟砍的不可恢复，因无价值砍的不该被重新加回来。四条否决项（串号、幂等、降级可复现、身份不可伪造）在任何裁剪下都不得动。
