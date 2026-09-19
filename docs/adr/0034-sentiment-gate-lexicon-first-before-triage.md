# 情感识别走"词典规则优先、LLM 兜底"，高情绪在 TRIAGE 之前直接转人工

Context: 对标架构要求情感识别前置（4 分类，高情绪优先转人工），ShopPilot 现有转人工全部发生在意图判定之后（T0 显式升级、9 因降级，含票 41 新增的轮次用尽因）。情绪激动的用户走完全链路再转人工，等待时间本身就是二次激怒；同时引入 BERT 分类器会引入 Python 侧依赖与模型部署形态，与单机 JVM 验证件冲突。

Decision: 新增 `sentiment` 包，`SentimentGate` 插在 `INTAKE → TRIAGE` 之间，两级判定：

1. 第一层：情绪词典规则（纯 JVM，0 token）。命中强愤怒/威胁投诉词或规则组合（如脏词 + "投诉/315/曝光"共现）→ 直接定案 `ANGRY`；
2. 第二层：词典不确定时走一次 LLM 分类（复用 `LlmGateway`，输出 `Emotion { CALM, DISSATISFIED, ANGRY, URGENT }` + 置信度）。LLM 不可用时结果为 `UNCERTAIN`，不阻断、不升级——情绪门 fail-open，链路可靠性兜底仍由后续既有降级因子承担。

升级判据：`ANGRY` 或 置信度 ≥ 0.8 且情绪 ∈ {ANGRY, URGENT} → `fallback(EMOTION_ESCALATION)`。`FallbackReason` 新增第 10 个枚举值 `EMOTION_ESCALATION`（第 9 位已被票 41 的 `TOOL_ROUNDS_EXHAUSTED` 占用），话术先安抚后转接，工单带 `priority=high` 标记，复用 ADR 0009 的落库与队列反查路径。

被情绪门升级的请求不进 TRIAGE、不进缓存、不计入拦截率分母（分母口径不变，见 ADR 0003）。

Considered Options:

- 引入 BERT/独立分类模型服务：否决。新增运行时依赖与部署形态，违反单机验证件边界；词典 + LLM 兜底与 T0/T1/T2 同构，叙事与实现都自洽。
- 情绪门放在 TRIAGE 之后（判完意图再判情绪）：否决。激怒场景的核心诉求是"少等一轮"，且意图判定失败路径（INTENT_UNRESOLVED）会与情绪升级路径竞争，语义变混。
- 复用 `USER_REQUESTED` 而不加新枚举：否决。降级因子的可枚举性（ADR 0009）是核心资产，情绪升级的指标口径（`shoppilot_sentiment_escalated_total`）必须独立可查。
- 置信度不足即转人工（fail-closed）：否决。会把大量平静咨询误伤进人工队列；情绪门误放的后果只是"慢一轮"，误升的后果是人工坐席被灌水。

Consequences:

- `AgentStateMachine` 入口处新增一次前置调用，状态机 10 状态不扩（情绪门发生在 INTAKE 状态内部）。
- 新指标至少 3 个：词典定案数、LLM 分类数与耗时、情绪升级数（按 emotion 维度）。
- 词典表作为配置资产入库（`sentiment/lexicon.yml`），改词条不改代码；评测集新增 `cases-part4-emotion.jsonl` 20 条，词典层用例 0 token 可复跑。
- 已知上限照登：词典对反讽、阴阳怪气语料召回有限，属规则法固有短板，不做精度声称，只登词典层准确率实测值。
