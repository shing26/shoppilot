# round33 spec —— 跨行业隔离与 T0 动作优先

> 状态：**执行中**（2026-10-17 开轮）。
> 依据：[ADR 0067](../../docs/adr/0067-round33-cross-industry-isolation-and-intent-precedence.md)
> （所有者政策覆盖，不自动续期）。
> 触发来源：round32 落地后，业务侧实机测试复现“换店铺/换行业问同一句，答案几乎一样”。

## 0. 本轮一句话

把“跨行业”从**知识库文件名不同**补成**运行时可隔离的 domain 维度**，并修掉 T0 在“动作 + 政策”
同句上的优先级回归。验收不是“看代码像隔离”，而是 T001/T004 同一问法不再串答案、T002 门店死蟹
细则真的能答、酒店办理类诉求真的不碰电商工具。

## 1. 已复现缺陷

以下均为 round32 后本机活体复现，修复票必须为每类写先红后绿的回归测试：

| 编号 | 缺陷 | 复现 | 根因落点 |
| --- | --- | --- | --- |
| D1 | 跨行业平台缓存污染 | T001 先问“押金什么时候退”，T004 同一句 `L1` 命中同一条电商答案 | `CacheService` 平台桶无 domain |
| D2 | 跨行业知识泄漏 | T001/T002/T004 问“生鲜坏了怎么赔”，命中同一批电商平台条款 | `HybridRetriever` 可见范围无 domain |
| D3 | 空召回放宽意图 | T001 问酒店政策，空召回后拿电商政策回答；T004 问生鲜亦然 | `HybridRetriever` 空召回 `intent=null` |
| D4 | “动作优先”失效 | “生鲜坏了我要退款”先判 `POLICY_FRESH`，追问订单号后丢失退款动作 | `T0RuleLayer` 只在实体/第一人称时动作 |
| D5 | 酒店办理进电商工具 | T004 “我要取消订单”变 `ACTION_REFUND` 并调 `APPLY_REFUND` | T0 无行业门；工具裁剪只看 `isAction()` |
| D6 | 泛词误判 | “手机坏了怎么赔”判 `POLICY_FRESH`；“早餐机坏了怎么赔”判 `POLICY_HOTEL` | `FRESH_WORDS` / `HOTEL_WORDS` 过宽 |
| D7 | 电商提示词硬编码 | T004 也拿到“你是电商店铺的在线客服助手” | agent-system `v1.0.0.md` |
| D8 | 买家引用契约错位 | 网关 `List<String>`，买家端按对象读，只显示“政策条款” | `AgentResult` / `SseEventSink` / `App.vue` |
| D9 | 语料内容冲突 | `return-01` 写“十四天内可申请七天无理由”；T002 无门店死蟹细则 | `knowledge/return-01...` / T002 语料缺失 |

## 2. 范围

**做：**

1. `Domain` 维度：租户解析、规则块、入库 payload、检索过滤、缓存 key/桶/L2 filter、负缓存。
2. T0 顺序与词表精度：行业门、显式动作优先、泛词收窄。
3. 提示词换代：行业无关底座 + 酒店纯咨询约束。
4. 引用契约只增：`citationDetails`，买家端优先读；旧 `citations` 字符串保留。
5. 语料修正：七天正文、T002 门店死蟹细则、内容一致性回归测试。
6. 活体验证：跨租户/跨行业问答、退款待办、酒店无工具、门店语料可检索。

**不做：**

- 不新增酒店办理工具、意图或状态机分支；
- 不把酒店语料挂 `PLATFORM`；
- 不改 180 条 gold、`MIN_CONFIDENCE` / `MIN_MARGIN`、缓存阈值或验收分母；
- 不宣称上线；不为“看起来完整”加新业务功能。

## 3. 硬约束

- **行业隔离 fail-closed**：未登记 domain 的租户只看本租户规则，不看平台电商政策。
- **同一问法在不同 domain 不共享平台桶**：缓存 key 必须可被测试直接断言。
- **空召回不再放宽 intent**：没有命中就是没有命中；检索降级与空结果保持两种语义。
- **酒店永远不挂电商工具**：`POLICY_HOTEL` 是政策意图，工具列表必须为空。
- **动作优先只对显式动作生效**：不能把“退款政策是什么”误判成 `ACTION_REFUND`。
- **只增兼容**：旧客户端继续读 `citations`；新买家端读 `citationDetails`。
- **判据面零改动**：新增测试不得通过调阈值、改 gold 或改 CI 分母来变绿。

## 4. 验证形态

| 层 | 内容 |
| --- | --- |
| JVM | `T0RuleLayerTest`（动作优先 / 酒店行业门 / 泛词反证）；`CacheServiceTest`（domain 分区 / 跨 domain 不可见）；`HybridRetrieverDomainFilterTest`（过滤含 domain、空召回不放宽）；`MarkdownChunkerTest`（语料 domain）；`KnowledgeConsistencyTest`（七天正文 / T002 死蟹细则）；`SseEventSinkTest`（citationDetails）；`PromptCatalogTest`（新版本与通用底座） |
| 0 token 门禁 | `mvnw verify`、`verify_eval_judge.py`、`eval_suites.py`、`retrieval_gate.py`、`verify_onepager.py` 照旧；CI 九步不变 |
| 活体 | 同一问法 T001/T004；T004 酒店政策；T004 办理类诉求；T002 死蟹细则；`生鲜坏了我要退款` 追问后补单号仍执行退款 |
| 证据 | 落 `docs/EVIDENCE.md`；未执行项照登 |

## 5. 票

| # | 票 | 依赖 |
| --- | --- | --- |
| 124 | ADR 0067 + round33 spec（本文件） | 无 |
| 125 | `Domain` 维度落检索/缓存/入库 + JVM 隔离用例 | 124 |
| 126 | T0 行业门、动作优先与词表精度 + JVM 用例 | 124 |
| 127 | 提示词换代、citationDetails、语料修正 + JVM 用例 | 124 |
| 128 | 活体验证与收口登记 | 125-127 |

## 6. 登记节

- round32 的两处未执行：**`--suite hotel` 活体跑批已由 round33 补做**——首跑 12 条 11 绿 1 红
  （`HTL-BKF-01` 早餐问句掉出 T0），修 `HOTEL_WORDS` 负向断言后复跑 12/12 绿，见 `docs/EVIDENCE.md`；
  **真人 cold-read 仍未执行**，照登，不由 round33 冒充。
- 补跑顺带修掉套件自身的隐性缺陷：默认 `customer-qps=5` 下缓存热起来后第 6 条起整批 429，
  `eval_suites.py` 加 0.25s 逐条节流（判据一字未动）。
- round32 的收口审计读数未重跑；round33 收口时按实测更新，未跑清场日矩阵就照登。
- 接新行业的成本由“改四处”改为“显式登记 domain + 语料 domain + 意图/词表/入库映射/评测集”，
  一页纸与 CODE_MAP 必须同步，不许继续写“只换知识库”。
- 本轮新增 `Domain` 与 `citationDetails` 都属于接口面变更，必须由 JVM 契约用例钉住。
