# 128 round33 活体验证与收口登记

**Status:** implemented

## What to build

- **活体验证**：起栈后逐条复现 round33 的九类缺陷，确认「修好了」而不是「看代码像修好了」。
- **收口登记**：`docs/EVIDENCE.md`、`docs/CODE_MAP.md`、tracker README 按实测更新；
  round32 的两处未执行（真人 cold-read、`--suite hotel` 活体跑批）继续照登，不由本轮冒充。

## Blocked by

[125](125-domain-dimension.md)、[126](126-t0-industry-gate.md)、[127](127-prompt-citations-corpus.md)。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
python scripts/verify_eval_judge.py
python scripts/eval_suites.py; python scripts/verify_onepager.py
python scripts/eval_task.py
python scripts/retrieval_gate.py
python scripts/check_coverage.py
git diff --check; git status --short
```

## Handoff notes

- **活体读数（2026-10-17，profile=local，kbEpoch=6，37 篇 / 111 块）**：
  - D1/D2 跨行业：T001 与 T004 同一句「押金什么时候退」→ T001 `POLICY_HOTEL`、`dense=0`（转人工，
    不再给电商答案）；T004 `POLICY_HOTEL`、`dense=18`、答酒店押金条款。**不再共享平台缓存。**
  - D3 空召回不放宽：T004「生鲜坏了怎么赔」→ `dense=0`，不给电商生鲜条款。
  - D4 动作优先：T001「生鲜坏了我要退款」→ `ACTION_REFUND` + 追问订单号；补「90001」→ `ACTION_REFUND` +
    `toolUsed=true`，退款受理。
  - D5 酒店无工具：T004「我要取消订单」→ `toolUsed=false`，不调电商工具。
  - D6 泛词：T001「手机坏了怎么赔」、T004「早餐机坏了怎么赔」→ 均不给对应行业的条款（T0 已不判 fresh/hotel）。
  - D9 门店语料：T002「死蟹怎么赔」→ `dense=20`、答「签收后六小时内、按只数折算」。
  - D8 引用：`citationDetails` 带 `{ruleId, heading, doc}`。
- **0 token 门禁全过**：`verify`（JVM `408` + `45` 绿）、`verify_eval_judge` `40/40`、
  离线 rescore `cases=180 files=6 tool_diff=4`、`eval_suites` `ok=30`、`verify_onepager` `failures=0`、
  `eval_task` `ok=14`、`retrieval_gate` `ok cases=10`、`check_coverage` `modules=4` 全 OK。
  `RETRIEVAL_FIXTURE_SHA256` 随语料修正换代。
- **收口审计（2026-10-17 实跑，`logs/round33-audit.out` 不入库）**：`PASS 75 / FAIL 11 / SKIP 10 / 共 96 项`
  （推送后复跑；`A1` 与 `A2` 均转绿）
  （`G6_EXPECT` 换代 `[10, 69, 391, 45]` → `[10, 69, 412, 45]`；412 含 HTL-BKF-01 回归修复的 +4）。
  **先修掉审计自己的两处崩溃**：
  round31 删了 `docs/interview-qa.md` 却留着 E5/E5b 与两处 `DOC_GLOBS` 读它，审计自 round31 起
  `FileNotFoundError` 崩在半路、根本跑不到底；本轮把 E5/E5b 改成前置不成立判 SKIP、`DOC_GLOBS` 里
  不存在的文档跳过引用核对，**判据本身一字未动**。11 条红的性质逐条照登：
  `B7` 命中 `scripts/verify_eval_judge.py`（round23 以来窗口内的既有红）；`G6` 是「常数 536 ≠ 日志 448」——
  那两份 `logs/acceptance/{build,unit}.log` 仍是 2026-10-05 的清场日落点，**本轮没重跑带 build/unit 的
  活体矩阵**；`E2/E6b/E6c/F1c/H5b/H8/H8b/H10` 都是**先前轮次留下的文档漂移**（本轮 README diff 不碰这些行，
  已逐条核过）。**不许为消这些红改判据**。
- **未达成照登**：① round32 的**真人 cold-read 未执行**（无真实业务侧读者）；② round32 的
  `--suite hotel` 活体跑批**已由本票补做**（首跑 11/12 → 修 `HTL-BKF-01` → 12/12 绿，见 Addendum）；
  ③ 本轮的检索夹具只**重钉指纹**、`dense/lexical/fused` 未随语料重录
  （需活体 embedding）；④ 全量 22/25 步活体矩阵**未跑**，只跑了针对性探针；⑤ round32 收口审计读数未重跑。
- **三个现场追问**：
  1. 非电商域的政策意图标签仍不干净（T1 可给 `POLICY_FRESH`），但检索 domain 过滤兜住了泄漏——这条
     「标签不干净但不泄漏」的残留要不要单独立票收紧？
  2. 检索夹具只重钉指纹是否够用，还是必须在下一次清场日重录整份（含 `dense/lexical/fused`）？
  3. round32/round33 的两处 cold-read 与 hotel 活体跑批要不要合并成一次「清场日补做」，还是各自挂触发？

## Addendum（2026-10-17，补做 round32 登记的酒店活体跑批）

- round32 登记为未执行的 `python scripts/run_tool_eval.py --suite hotel` 本票补上。**首跑 12 条 11 绿 1 红**：
  `HTL-BKF-01.intent_ok` 红——「早餐是几点到几点」掉出 T0、判成 `UNKNOWN`，属票 126 词表收窄的回归。
  已在票 126 修（`HOTEL_WORDS` 改负向断言 `早餐(?!机)`），复跑 **12/12 绿**。
- **补跑顺带抓出套件自身的隐性缺陷**：默认 `customer-qps=5`（application.yml），缓存热起来后逐条请求
  10ms 内打满令牌桶，**第 6 条起整批 429**（复跑实测 7 条红全是 `HTTP Error 429`）。`scripts/eval_suites.py`
  加 `PACE_SECONDS = 0.25` 逐条节流（≈4 QPS < 5），冷/热两种状态都可复跑——**判据一字未动**。
- 数字换代：JVM 四模块 `10 + 69 + 408 + 45 = 532` → **`10 + 69 + 412 + 45 = 536`**（票 126 的 +4），
  同步 README badge / 正文、`docs/EVIDENCE.md`、tracker、收口审计 `G6_EXPECT`。0 token 门禁复跑：
  `verify_eval_judge 40/40`、`eval_suites ok=30`、`verify_onepager failures=0`、`eval_task ok=14`、
  `retrieval_gate ok cases=10`、`check_coverage modules=4` 全过。
- **仍照登未达成**：真人 cold-read 未执行；检索夹具 `dense/lexical/fused` 未随语料重录；全量活体矩阵未跑。
