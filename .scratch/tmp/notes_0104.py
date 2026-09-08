# -*- coding: utf-8 -*-
"""Fill Handoff notes for tickets 01-04 and tick their boxes."""
import pathlib
import re
import sys

ROOT = pathlib.Path("D:/ShopPilot/.scratch/shoppilot-mvp/issues")
PLACEHOLDER = "（收尾时填写：关键决策 + 你需要能当场回答的三个追问）"

NOTES = {}

NOTES["01-skeleton-and-compose.md"] = """
**关键决策**

1. **三模块而不是单模块。** `shoppilot-gateway` / `shoppilot-biz-mock` / `shoppilot-tool-api`，其中 `tool-api` 是纯契约 jar，只依赖 Jackson 注解，不引 Spring。这样"给模型的 Function Schema"和"网关的 HTTP 调用签名"由同一份 DTO 生成（ticket 10），跨进程边界在编译期就被钉住，而不是靠两边手工对齐字段名。
2. **宿主端口全部偏移：网关 8082、biz-mock 8091、Redis 16379、Qdrant 16333、ES 19200，且只绑 `127.0.0.1`。** 这台机器上 Docker Desktop 的 wslrelay 已经占住 6379/6333/9200，另有 `opspilot-*`、`nexus-*` 容器占着 8080/8081。偏移不是洁癖，是"绝不与别人抢端口"的硬约束——所有中间件都只监听回环，不对外暴露。
3. **中间件内存上限显式写死**：Redis `maxmemory 128mb` + `allkeys-lru`（容器 192m）、Qdrant 512m、ES `-Xmx512m`（容器 1024m）。16 G 开发机同时跑别的项目，不设上限的 ES 会在压测时把整机拖进交换区，那组延迟数字就没有意义了。
4. **虚拟线程开关从第一天就在配置里**（`spring.threads.virtual.enabled`），并预留 `no-virtual` profile 关掉它。ticket 18 的对比实验要求"不改代码就能切换"，临时加开关的实验不可信。
5. **Maven wrapper 用 `only-script` 分发**（`mvnw` / `mvnw.cmd` / `.mvn/wrapper/maven-wrapper.properties`，锁 3.9.14），干净机器上 `./mvnw verify` 会自己取 Maven。
6. **`.env.example` 只列变量不列值**，`.gitignore` 覆盖 `.env`、`target/`、H2 数据文件、`.venv-loadtest/`、`loadtest/results/locust-*`（保留 `ladder-*.csv` 与 `env-*.json` 两份可复核产物）。

**你需要能当场回答的三个追问**

- *Q：为什么 ES 用 7.17 而不是 8.x？* A：8.x 强制 HTTPS + 客户端版本协商，本机内存预算下多一层安全握手不值当；7.17 的 `_search` + BM25 就是这个项目要用的全部能力，闭源特性一个没用。
- *Q：Redis 设了 `allkeys-lru`，缓存被驱逐了怎么办？* A：语义缓存的正确性不依赖 Redis 驻留——L1 被驱逐等价于一次 miss，走穿透路径重新生成并写回。真正不能丢的是幂等记录，那部分有 biz-mock 的 DB 唯一约束兜底（ticket 12）。
- *Q：`./mvnw` 在你机器上跑得起来吗？* A：能，但要注意本机 `mvn` 的全局 `conf/settings.xml` 把 `localRepository` 指到了 `E:\\maven_repository`，而 wrapper 用的是默认 `~/.m2/repository`。离线复现时加 `-Dmaven.repo.local=E:\\maven_repository` 即可对齐；干净机器联网首跑不需要这个。

**验证记录（2026-09-05）**

`docker compose up -d` 三容器起来；两服务 `/actuator/health` 均 UP；`mvn -o verify` 全绿。Qdrant 容器 healthcheck 长期报 `unhealthy`（`/readyz` 在其内部 wget 下超时），实测读写正常，已记入 README 已知限制。
"""

NOTES["02-mock-jwt-identity.md"] = """
**关键决策**

1. **身份只从签名 token 来，请求体/查询参数/Header 里出现的任何 `tenantId` 一律忽略并打告警日志。** 这是 ADR 0005 的第一道防线：如果客户端能自选租户，后面所有行级隔离都是装饰。
2. **`POST /auth/mock-token {tenantId, customerId}` 是刻意保留的"不安全"入口**，HS256 自签、30 分钟过期、密钥读 `SHOPPILOT_JWT_SECRET`。它存在的意义是让"换一家店的 token"在演示里是一行 curl；生产替换成真实授权服务，网关侧代码不动。
3. **身份经 `TenantContext`（`ThreadLocal`）单点下发，`finally` 显式清理。** 虚拟线程下 `ThreadLocal` 有内存泄漏风险但不会被线程复用污染（虚拟线程用完即弃），所以够用；代码里标注 `ScopedValue` 为未来替换点，不开 `--enable-preview` 是因为预览特性不该进一个要给别人跑的面试项目。
4. **`X-Conversation-Id` 由客户端提供但只作会话键使用**，不参与鉴权；租户与买家身份仍然只来自 token，避免"会话 ID 里藏租户"这种第二身份通道。
5. **用 `IdentityArchitectureTest`（ArchUnit 风格的可控对象测试）守住"下游只能经 `TenantContext` 取身份"**：扫描业务包，禁止除 `identity` 包外任何类引用 JWT 解析或读取原始 Header。这条约束不守住，ticket 03/10/11 的隔离会在后续迭代里被绕过。

**你需要能当场回答的三个追问**

- *Q：伪造一个 claims 正确的 JWT 不行吗？* A：HS256 验签需要密钥；未签名（`alg=none`）与错签名都在 `JwtService` 解析阶段抛错，filter 直接 401。过期 token 同样 401，`AuthFilterTest` 三条用例分别覆盖无 token、伪造、过期。
- *Q：网关重启后 token 会失效吗？* A：不会，密钥在环境变量里，不在进程内存里。这是有意的——重启打断正在打字的用户已经够糟，再把所有人的会话踢掉是雪上加霜。
- *Q：为什么不直接把 tenantId 放进 MDC 或者方法参数往下传？* A：往下传要改一长串签名，改一处漏一处就是越权；MDC 只用于日志，不作为可信来源。`TenantContext` 是唯一可信入口，且有架构测试兜着。

**验证记录（2026-09-05）**

A 店 token 请求 200 且下游取到 `T001/C001`；伪造签名 token 与过期 token 均 401；带 `tenantId` 字段的请求体被忽略并告警。
"""

NOTES["03-biz-mock-data-foundation.md"] = """
**关键决策**

1. **biz-mock 是独立进程（:8091），不是网关里的一个包。** ADR 0002：只有跨进程，"租户身份必须随调用传递"才无法被偷懒绕过——同进程里顺手读个字段就过去了，跨进程则必须显式带上 `X-Internal-Token` 与身份。
2. **只监听 `127.0.0.1`，所有管理端点校验 `X-Internal-Token`**，缺失或不匹配 401。内部凭证永不下发浏览器（ticket 15 的调试台经网关代理取数）。
3. **金额一律以"分"为单位的整型存储。** 浮点金额在退款与满减叠加场景会累积误差，这是业务系统的基本功，不是电商特例。
4. **订单状态机显式枚举并在仓储层拒绝非法转移**：`CREATED -> PAID -> SHIPPED -> DELIVERED -> COMPLETED`，分支 `CANCELLED` 与 `REFUNDING -> REFUNDED`。状态校验放业务系统而不是网关，因为业务系统才是状态真相的所有者（ticket 12 的前置校验也建立在这条上）。
5. **租户感知仓储用 Hibernate `@TenantId`（discriminator 列）实现行级隔离**，这是 ADR 0005 的第二道防线；第一道是 token 身份注入，第三道是查询端点强制归属条件。裸 JPQL 绕过由"新增实体默认受管 + 越权用例进 CI"压制。
6. **seed 幂等：`orders` 表非空即跳过**，所以重启不翻倍；规模 3 租户 / 200 买家 / 5 万订单 / 约 20 万物流节点，并刻意造出跨店买家样本（同一买家在 A、B 两店各有订单），否则"跨店越权"这条最重要的用例根本没有素材。
7. **故障注入参数 `delayMs` / `failRate` 内建在订单与物流端点上**，ticket 14 的降级链路靠它复现，不需要真的拔网线。

**你需要能当场回答的三个追问**

- *Q：A 店查 B 店订单，为什么返回"未在本店找到该订单"而不是 403？* A：403 会确认"这单存在"，本身就是信息泄露。`@TenantId` 让 B 店订单在 A 店的会话里根本不可见，语义上等价于不存在，这也是 CI 用例的断言内容。
- *Q：5 万订单在 H2 上不会慢吗？* A：会，而且慢是设计的一部分——H2 写入是本项目已知的吞吐瓶颈（README 已知限制里写明）。压测报告里的 TP99 含这段真实成本，不是内存玩具。
- *Q：seed 数据是随机的，演示怎么保证可复现？* A：随机造数之外另钉四张演示固定单 90001-90004（ticket 12），`POST /api/admin/demo/reset` 复位。

**验证记录（2026-09-05）**

seed 连跑两次订单总数不变；A 店身份查 B 店订单返回"未在本店找到该订单"，无 500、无空指针；`TenantIsolationAndIdempotencyTest` 覆盖越权与唯一约束。
"""

NOTES["04-knowledge-ingestion.md"] = """
**关键决策**

1. **30 篇政策文档覆盖四类**：退换货细则 8 篇、生鲜保鲜理赔 7 篇、跨店满减与定金膨胀 8 篇、发货与快递政策 7 篇；LLM 生成后人工校对，术语与 `CONTEXT.md` 对齐。语料是合成的，README 明确写了这一点，不假装是真实平台条款。
2. **按标题层级切分，目标 300-600 字，不重叠**，实测 30 篇切出 90 个规则块。不做滑窗重叠是刻意取舍：重叠块会在 RRF 融合时互相抢名次，让 top-5 里三块是同一句话的三种截断，检索质量反而下降。
3. **`ruleId = hash(sourceDoc + headingPath)`，同时作为 ES `_id` 与 Qdrant point id。** 两边共用同一个 ID 是"重跑即幂等 upsert"的前提，也是 ticket 08 能把两路召回对齐融合的前提。
4. **元数据落 `ruleType` / `applicableCategory` / `scope` / `tenantId` / `sourceDoc` / `effectiveFrom`**；平台级条目 `tenantId` 固定为平台标识（`RuleChunk.PLATFORM_TENANT`），全租户共享同一份，不按店铺复制 N 份（ADR 0004）。
5. **embedding 恒为本地 `bge-m3` 1024 维，经 Ollama**（ADR 0001）。入库与在线检索必须同模型同维度，换模型等于换向量空间，混用会让缓存与检索同时失效。
6. **Ollama 未启动时脚本给出可执行提示而非堆栈**：`请先运行 ollama serve 与 ollama pull bge-m3`。这类"接手就能跑通"的报错文案是项目可用性的一部分。
7. **`kb_epoch` 由脚本在成功入库后推进**，作为缓存纪元：政策一改，旧答案整纪元作废（ticket 06/09 的失效机制靠它，不靠 TTL）。

**你需要能当场回答的三个追问**

- *Q：为什么不用现成的文档解析框架？* A：语料是自己写的 Markdown，标题层级就是天然结构；引入解析器只增加依赖和不确定性。切分逻辑集中在 `MarkdownChunker` 一个类里，好测也好改。
- *Q：90 块是不是太少？* A：对演示与压测足够，因为要测的是链路而不是召回率上限。真正的信息量在元数据过滤（scope/tenant/intent）上，块数增加不改变结论。
- *Q：重跑会不会产生重复向量？* A：不会，Qdrant 用 `ruleId` 作 point id，重复写是覆盖；验收就是"连跑两次两边条目数相等且不增长"。

**验证记录（2026-09-05，后续多次重跑）**

`scripts/ingest.ps1` 连跑两次：ES 与 Qdrant 均 90 条且不增长；`kb_epoch` 每次成功入库 +1（当前值为 9，含历次语料修订）。
"""

def apply(name, body):
    path = ROOT / name
    if not path.exists():
        print(f"MISSING {name}")
        return 1
    text = path.read_text(encoding="utf-8")
    if PLACEHOLDER not in text:
        print(f"NO PLACEHOLDER {name}")
        return 1
    text = text.replace("- [ ] ", "- [x] ")
    text = text.replace("**Status:** ready-for-agent", "**Status:** done")
    text = text.replace(PLACEHOLDER, body.strip())
    path.write_text(text, encoding="utf-8")
    print(f"updated {name}")
    return 0

bad = 0
for name, body in NOTES.items():
    bad += apply(name, body)
sys.exit(1 if bad else 0)
