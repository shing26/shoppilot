# 16 — Tool Calling 标注评测集与分意图准确率

**What to build:** 一份人工校对的标注集，跑出"选对工具"与"填对参数"两个子指标的分意图报告，让 `>= 95%` 这个数字有出处。落实 ADR 0001。

**Blocked by:** 11 — 业务办理闭环

**Status:** ready-for-agent

**Verify:** 以 `--limit 10` 冒烟后跑完整集 -> 输出分意图双子指标 CSV，对抗样本占比 30%，人工校对记录在案。

- [x] 规模约 180 条：10 意图各 15-20 条；候选 query 可由 LLM 生成保证口语多样性，但期望标注（该调哪个工具、参数填什么）必须人工校对
- [x] 30% 为对抗样本：缺槽位、多意图混合、订单号不存在、跨租户订单、纯口语无关键词
- [x] 两个子指标分开报：工具选择正确率、参数完全匹配率；不得合并成一个数字
- [ ] 评测在 `dev` 模式跑，记录模型名、日期、token 消耗，结果落 CSV 进仓库 —— 未勾：`.env` 缺 DashScope key，脚本能识别 dev 但跑不起来；见收尾记录第 6 条
- [x] 评测脚本受 token 日预算熔断约束，跑前预估消耗并打印
- [x] 缺槽位样本的期望结果是 `SLOT_ASK` 而非硬编一个订单号——猜槽位算错，不算聪明
- [x] 用例：评测脚本可用 `--limit` 跑小样本冒烟，不必一次烧完整集

## Handoff notes

**关键决策**

1. 语料分三个手写分片（policy / action / edge）合并成 `eval/tool-cases.jsonl`，180 条、10 意图各 18 条。口语变体由 local 的 qwen2.5:3b 生成，但 `expect`（该调哪个工具、参数填什么、该不该问槽）逐条人工校对。
2. 对抗样本实测 40%（72/180：colloquial 34 + missing_slot 12 + multi_intent 10 + order_not_found 8 + cross_tenant 8），高于票面 30% 下限，因为跨租户与订单不存在各多放了几条。
3. 指标从票面的两个拆成三个再加一列：选对工具、填对参数、缺槽时是否正确问槽（`slotask_accuracy`），外加 `fabricated_cases` 单列。合并成一个数字会把"没问槽但参数蒙对"和"凭空编了个订单号"混成一谈，而后者是最危险的失败模式。
4. `missing_slot` 用例的期望写成 `slotAsk: true` + `mustNotContainArgs: ["orderNo"]`，模型编造订单号时 `ToolDispatcher` 直接判 fabricated、不发起查询，即使工具选对也不算通过。
5. 熔断前置：跑前用样例估算单 case tokens × 样本数，与 `/ops/circuit` 的日预算剩余比较，超了 exit 2（要 `--force` 才继续），符合 ADR 0012。非 dev 模式额外打印"仅验证链路，不进入验收口径"。
6. **本轮数字来自 `local`（qwen2.5:3b），不是 dev。** dev 那一栏（qwen-plus）待 `.env` 提供 DashScope key 后重跑，脚本与阈值判定线已就绪：`HARD_FAIL_INTENT` / `ACCEPT_TOOL` 两条线只在 `mode == "dev"` 时才参与红绿判定。

**需要能当场回答的三个追问**

1. "任务书说工具调用准确率 ≥95%，你这里最高 88.9%？" —— 95% 是 dev 模式（qwen-plus）的验收线，本轮 local 用 3B 小模型跑，作用是自证链路与评分口径。分意图报就是为了让差距可见：POLICY_* 四个意图 100%，失败集中在 ACTION_* 的多参数抽取，指向模型能力而非编排契约。
2. "ACTION_ADDRESS 66.7%，还有 2 条凭空编订单号，怎么兜？" —— 改地址是唯一"写操作 + 两个必填槽"的意图，小模型缺槽时倾向填空而不是问。防线是三层叠加：`ToolDispatcher` 订单号格式校验当场拦（这 2 条正是被拦下才计 fabricated 而不是蒙混过关）、缺槽走 `SLOT_ASK` 追问、真正落库前还有 ticket 12 的订单状态前置校验 + 幂等 token。
3. "180 条能说明什么？" —— 每意图 18 条，分辨率 1/18 ≈ 5.6 个百分点，够看出 POLICY(100%) 与 ACTION(66-89%) 的结构性差距，不够区分 95% 与 97%。所以报告按区间读，并把逐条明细留在 CSV 里可复查，不拿点值当结论。

**复现**

```
python scripts/run_tool_eval.py --limit 10           # 冒烟
python scripts/run_tool_eval.py                      # 全量 180 条
```

明细 `eval/results/tool-eval-20260908-141504-local.csv`，分意图汇总同名 `-summary.csv`，运行环境 `-meta.json`。

**2026-09-09 重跑（`tool-eval-20260909-092638-local.*`）**：ADR 0017 把显式转人工下沉到 T0 之后，ESCALATE 行 选对工具 61.1% -> 88.9%、综合 11.1% -> 44.4%。其余逐格比对：ACTION_ADDRESS / ACTION_ORDER / ACTION_REFUND 与四条 POLICY 完全一致，只有 ACTION_LOGISTICS（结构化追问 94.4% -> 100.0%、猜槽位 1 -> 0）与 UNKNOWN（77.8% -> 83.3%、猜槽位 1 -> 0）两行因 3B 非确定性变化。本轮 180 条请求失败 0 条，但服务端同期记到 99 次 embedding 超时（`shoppilot_embedding_failure_total`）——按 ADR 0007 走 fail-closed 进模型、稠密召回单路独扛，POLICY 四行的选对工具仍全为 100%，这只说明降级路径没把答案打断，不代表 embedding 健康。

