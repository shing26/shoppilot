# 票 214：回归集 + 边界集 + 共享判分层与结构校验器

Round: 50（spec：[round50-spec-agent-eval-expansion.md](../round50-spec-agent-eval-expansion.md)，所有者 2026-11-11 开轮批准）
ADR: 无（评测资产；不触发 ADR 0030/0031）　Status: implemented（2026-11-11）　Blocked by: 无

## What to build

把历史故障与边界问句做成**可复跑的确定性评测集**，判分 0 token：

1. `eval/regression-cases.jsonl`：40 条 / 20 对。来源只有两类可追溯出处——`git log` 的
   `fix(roundNN)` 提交与票面 Handoff、`docs/EVIDENCE.md` 的未达成项。每对 = **历史故障原句**
   （`variant=original`，只进 dev）+ **≥1 条口语变体**（`variant=colloquial`，可进 holdout），
   对内 tenant / customer / kind / expect 完全一致。`kind` 三分：`intent` / `tool` / `answer`。
2. `eval/edge-cases.jsonl`：54 条 / 7 类——`multi_intent` 10、`fresh_pin` 12、`slot_continue` 8、
   `typo` 8、`unknown` 6、`empty_recall` 4、`numeric_homograph` 6。`fresh_pin` 必须
   `doc + section` 双钉（只要求引用 `fresh-*` 不够）。多轮用例用 `turns` 前置轮。
3. `scripts/agent_eval_cases.py`：共享判分层（`score()` / `good_observation()` / `mutations()` /
   `selfcheck()`），只**调用**既有 `run_tool_eval.judge()` 与 `answer_facts.judge()`，不改判据函数；
   内含既有四个判据文件的 SHA-256 守卫（`EXISTING_JUDGE_FILES`）。
4. `scripts/verify_agent_eval_cases.py`：结构校验器——哈希守卫、条数下限、ID/问句唯一、
   成对规则、来源可追溯正则、holdout ≥35%、原句不进 holdout、doc/section 存在、
   问句不回流语料/提示词、变异自检。

## 契约

- Allowed paths：`eval/regression-cases.jsonl`、`eval/edge-cases.jsonl`、
  `scripts/verify_agent_eval_cases.py`、`scripts/agent_eval_cases.py`（共享层）。
- Acceptance：40 / 54 条；成对规则与 `expect` 一致；holdout 35.0% / 37.0%；
  doc/section 全部存在；`selfcheck` 全绿（382 个变异全红）。
- Verify：`python -I scripts/verify_agent_eval_cases.py`；`python -I scripts/agent_eval_cases.py`。
- Forbidden：改既有 gold、改判据函数、把问句写进 `knowledge/` 语料或 `src/main/resources/prompts/`、
  为凑数复制既有问句、调宽任何阈值。

## 验收项

- 回归 40 / 20 对、边界 54 / 7 类；holdout 35.0% / 37.0%；kind 16/12/12。
- 既有 180 条 `tool-cases`、`answer-cases`、`retrieval-cases`、`handoff-golden` 零字节改动（哈希守卫绿）。
- 382 个变异（回归 182 + 边界 200）全部正对照绿、注入缺陷红。
- 新问句零回流语料与提示词。

## Handoff notes

（2026-11-11 实现，随 round50 分支 `claude/inspiring-bose-af1eee` 交付；所有者书面签收合并见票 216。）

**关键决策**

- **成对而非单条**：只抄 issue 标题会让 holdout 变成背题。历史原句锁在 dev，口语变体才可进 holdout，
  测的是「换个说法还认不认」；校验器把「一对内有且只有一条 original、租户/客户/kind/expect 一致」
  写成断言，不靠人记。
- **`fresh_pin` 用 `doc + section` 双钉**：只断言「引用了 fresh-* 文档」时，「召回对了、引到无关小节」
  仍会绿；12 条把时限、冷链、包装破损、死蟹、缺重、切开、二次理赔、T002 店铺级升级逐条钉到小节。
- **共享层只有一个判据入口**：`agent_eval_cases.py` 调既有 `judge()` 而不是重写一套，确保新集合与
  既有 gold 用同一把尺；四个既有判据文件进 SHA-256 守卫，任何人为了让自己变绿去改它们都会当场红。
- **变异证据的诚实边界**：382 个变异证明的是**判据对自己的缺陷敏感**，不等于生产代码变异分数；
  生产代码级变异沿用票 163/164/165/168、round34、票 210 的已入账记录，本轮零 Java 改动、不重复造。

**验证落点**

- `python -I scripts/verify_agent_eval_cases.py`：OK——回归 40 条 / 20 对，边界 54 条 / 7 类；
  split 回归 holdout 14（35.0%）、边界 holdout 20（37.0%）；kind `{intent:16, tool:12, answer:12}`；
  边界 `{multi_intent:10, fresh_pin:12, slot_continue:8, typo:8, unknown:6, empty_recall:4, numeric_homograph:6}`。
- `python -I scripts/agent_eval_cases.py`：自检 OK——40 + 54 + 30 条、523 个变异全部红（其中本票 382）。
- 2026-11-11 合并前，由本会话在干净 worktree（`git status --short` 空）复跑上述两条命令，读数同上。
- 用例集指纹：回归 `caa034c3697b0993`、边界 `e2727760b3202edd`（sha256 前 16 位）。

**三个现场追问**

1. 回归/边界 124 条**未做活体复跑**：全部走 dev 档模型是真实费用，本轮只交付 0-token 结构/变异证据。
   活体抽样由谁批预算、抽多少条，需要所有者定。
2. `max-slot-asks: 1` 是产品口径还是本机配置？若是本机配置，`slot_continue` 可拆成真正的两次追问；
   若是产品口径，`E-SL-05/07` 把升级本身写成期望就是对的。
3. 变异框架目前只做「构造好观测 + 注入缺陷」，没有做生产代码级变异；后者成本高，是否值得单独立票，
   留给 round50 之后判定。
