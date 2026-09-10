# 20 — ACTION_ORDER 最低行归因收口：重标 gold 与修量具

**What to build:** 把 README 指标表里最低的那一行（`ACTION_ORDER` 选对工具 72.2%）归因到底，并让"分意图两个子指标分开报"这条票面要求真正成立。结论是标注边界过窄，不是工具契约错：放开 4 条 gold 的合格答案集，同时修三处评分器缺陷。全程零模型额度。落实 ADR 0021。

**Blocked by:** 16 — Tool Calling 标注评测集与分意图准确率

**Status:** ready-for-agent

**Verify:** `python scripts/verify_eval_judge.py`（22 条断言的证据跑器，含变异反证）全绿 -> `--selfcheck` 全绿 -> `build_eval_set.py` 无 FAIL -> 对 09-10 那 6 份明细跑 `--rescore` 出前后对照 -> `mvnw test` 绿 -> 整轮 17 步门禁绿 -> 下面 24 条验收逐条留证据。取证命令集中在文末「取证命令」。

## 决策（2026-09-11 grilling 会话，逐条经用户确认）

1. **归因走 (a) 重标，不走 (b) 合并工具。** 合并的代价不只是 180 条 dev 重跑（约 26.7 万 token，超 26 万日预算）与 `ToolName`/`ToolContracts`/`AgentStateMachine`/`ToolDispatcher`/`MockLlmClient`/biz-mock 六个改动面，是它**删掉判据而不是通过判据**：两桶合一之后"模型能不能分清两种诉求"再也没有读数。
2. **`expect.tool` 允许写成列表**（合格答案集），且**绑定**一条量具修复：参数子指标只在可比对样本上算。参数那条与工具集合是同一处取链逻辑，绕不开，所以一起改、在 ADR 里分两段写。
3. **0-token 发布路线**：`选对工具` 离线重算（明细存了实际调用链），越权 `NOT_FOUND` 观测下沉到 JVM 测试免费补；dev 重跑不跑，只备一键预检命令。
4. **`mustNotLeak` 实现并换可判别标记**，`build_eval_set.py` 加防呆校验。
5. **README 两套读数并列**（93.3% 与 95.6%），且 4 组对偶共 8 个 id 钉进正文当自缚证据；承诺项结论仍写「未达成」，一字不改。
6. **`--selfcheck` 做成真跑前的内嵌前置断言**（不新增门禁第 18 步），**`--rescore` 复用同一个 `judge()`**（不留第二份会漂移的判据）。

## 事实依据（都可离线复查）

- **对偶矛盾 4 组**（这是放开的唯一资格，规则见 `CONTEXT.md` 的「对偶矛盾」）：
  `ACT-ORD-09`「90002 这单是不是已经发出去了」标 `queryOrderDetail` ↔ `ACT-LOG-09`「90002 这单发了没」标 `queryLogistics`，同租户同订单同一件事；
  `ACT-ORD-11` ↔ `ACT-LOG-11`、`ACT-ORD-16` ↔ `ACT-LOG-15`、`ACT-ORD-17` ↔ `ACT-LOG-17`（后两组都含「到哪了」）。
- **仓库自己的代码支持这个读法**：`MockLlmClient.pickTool()` 的规则是 `物流|快递|到哪|签收 -> QUERY_LOGISTICS`，即 `到哪` 判给物流；而 gold 把 `ACT-ORD-16/17` 的「到哪了」判给订单详情。代码与标注互相矛盾，模型与代码一致。
- **反方向 0 犹豫**：18 条 `ACTION_LOGISTICS` 全部选 `queryLogistics`，不存在双向混乱。
- **4 条错例在全量与补跑里逐格一致**（`075747-dev` 与 `080830-dev-budgetfix` 同一份 id 同一份实际调用链），不是抖动。
- **评分器三处缺陷**（离线重算复现了 README 的 168/180 = 93.3% 与 57/63 = 90.5%，所以数字同源）：
  `args_ok` 在期望工具未出现时退回空字典，把每个参数比成 `got=None` —— 6 条 miss 全是这一类，比对过的 57 条零错误，参数子指标实际是工具选择的函数，违反票面「两个子指标分开报」；
  `status_ok` 的判定条件里带着 `and link`，`link` 为空时静默给 True，`ACT-ORD-16/17` 两条越权样本的 `NOT_FOUND` 从没被观测过（16 条带 `expectStatus` 的样本里 2 条真空）；
  `mustNotLeak` 只写在 gold 里，评分器从不读，标记本身还指错对象。原标记两处硬伤：「数码」是 T001 的店名（数码旗舰店）而不是 90001 的字段，90001 的品类是 `生鲜果蔬`；「`90001 的`」是正常拒答「查不到订单号 90001 的记录」去掉空白后的子串。所以真实现这个断言，第一次跑就会假红。
  换标记时踩到的第二层坑：**品类名与快递商名不能当标记**。它们来自 `SeedRunner` 的全局 `CATEGORIES` / `CARRIERS` 词表，任何租户谈自家生意都可能合法说出，拿「生鲜果蔬」当标记等于把别人的正常表述判成事故；街道名同理（`文三路` 是四条种子订单共用的街道），只有带门牌的整串「文三路 1 号」才是 90001 的私密字段。最终标记取收件人隐私三件套：`演示买家`、`13800001234`，地址类样本另加 `文三路 1 号`。这条约束由 `build_eval_set.py` 的 `SHARED_VOCAB` 校验器钉住，不靠记性。
- **越权下沉可行**：`BizMockService.queryLogistics` 先 `findOwned()` 再判状态，越权一律 `NOT_FOUND` 且与订单状态无关，所以不需要新种子数据。

## 改动清单

- `scripts/run_tool_eval.py`（CRLF）：拆出唯一判据函数 `judge(expect, obs)`，`score_case()` 退化成活体适配器；`obs` 带 `args_known` / `answer_known` 两个「这一轮拿得到什么」的开关。`tool_ok` 判合格集合任一命中；参数仅在 `link` 存在且 `args_known` 时比对，否则记不可比对；`expectStatus` 未观测时记未观测而不是 True；实现 `mustNotLeak`（归一化去空白子串命中即硬失败，新增 `leaked` 列）；`checks_ok` 只统计已判定断言，未判定项进 `unverifiable` 列。新增 `--selfcheck`（14 条夹具，门槛写 ≥7，真跑前执行，失败 `exit 2`）与 `--rescore <明细.csv> [--rescore-expected-diff id,..]`。`summarize()` 参数分母换成可比对条数并新增四列；`<80%` 判不通过、`<95%` 承诺线、`EVAL DONE` 标记一字不动。
- `eval/cases-part2-action.jsonl`（CRLF）：`ACT-ORD-09/11/16/17` 的 `expect.tool` 改成 `["queryOrderDetail","queryLogistics"]`，并各带 `relabelNote` 记对偶来源；8 条 `cross_tenant` 全部补 `mustNotLeak`（取 `演示买家` / `13800001234`，两条改地址样本另加 `文三路 1 号`），`ACT-ORD-17` 的旧标记 `["数码","90001 的"]` 换掉。`ACT-ORD-13`、`ACT-ADR-14`、`ACT-RFD-14` 不动（无对偶矛盾，是真错）。
- `scripts/build_eval_set.py`（CRLF）：`expect.tool` 允许列表并校验元素合法、不重复、非空；每条 `cross_tenant` 必须带非空 `mustNotLeak`；标记不得少于 2 字、不得出现在该样本自己的 query 里、不得是 `SHARED_VOCAB`（5 个品类名 + 4 个快递商名，抄自 `SeedRunner`）里的跨租户共享词。
- `eval/tool-cases.jsonl`：只由脚本重生成。
- `TenantIsolationAndIdempotencyTest`（LF）：新增 `@Test logisticsSharesTheSameOwnershipGate`，一正两反三条断言——归属者拿到的状态 `isNotEqualTo("NOT_FOUND")`（正向只断「不谎称查不到」，因为 10001 的状态由随机种子决定），换店、换人各一条 `isEqualTo("NOT_FOUND")`。三条合起来使「归属谓词被拿掉」时这条用例必然变红。
- `scripts/verify_eval_judge.py`（CRLF，新增）：22 条断言的证据跑器。全部改动在 `tempfile` 里的仓库外副本上做，跑完断言工作树未被污染；覆盖变异反证、校验器防呆、生成物字节稳定、判据形状、EOL。
- `docs/adr/0021-*.md`：两段严格分开——「业务能力读数」（重标前后各一套并列）与「量具缺陷归因」；Considered Options 记合并工具及被否理由。
- README 三处落点（指标表、承诺项逐条结论、已知限制）+ 门禁矩阵行；ticket 16 追加决策与追问；ticket 12 记越权断言下沉；`collect_interview_questions.py` 重生成问答库。

## 验收标准（24 条，逐条留证据）

### 一、反刷分不变量

1. 阈值常量一字未动：`git diff -U0 -- scripts/run_tool_eval.py` 里不含带 `ACCEPT_TOOL` 或 `HARD_FAIL_INTENT` 的增删行。
2. 放开恰好 4 条且 id 固定：`git diff -- eval/cases-part2-action.jsonl` 中新增的 `"tool": [` 行数为 4，id 集合恰好 `{ACT-ORD-09, ACT-ORD-11, ACT-ORD-16, ACT-ORD-17}`。
3. 三条多诉求样本未被顺手放开：`ACT-ORD-13`、`ACT-ADR-14`、`ACT-RFD-14` 不出现在 gold 的 diff 里。
4. 评测集结构不变：`build_eval_set.py` 打印的 180 条、每意图 18 条、对抗样本 72/180 = 40% 三个数与改前逐字相同。
5. 新判据下未达 95% 线的意图行数 >= 5；若为 0，视为在刷分，本轮不予接受。

### 二、量具自证与变异验证

6. `--selfcheck` 夹具 >= 7 条（实装 14 条）全绿，打印 `SCORER SELFCHECK ok=14`；夹具被破坏时 `exit 2` 并打印 `SCORER CHECK FAILED`。
7. selfcheck 在任何 HTTP 请求之前执行：`--base` 指向空闲端口跑 `--selfcheck`，退出码为 0。
8. 参数双算的反证：在**仓库外的临时副本**里把 `args_ok` 改回旧语义，副本 `--selfcheck` 必须红；仓库 `git status --porcelain` 仍只含预期改动。
9. `status_ok` 真空通过的反证：同样在临时副本恢复 `and link` 的旧写法，selfcheck 必须红。
10. 泄漏标记不误伤：夹具含「`查不到订单号 90001 的记录` + 标记 `演示买家`/`13800001234`」判不泄漏，答案正文出现 `演示买家` 判泄漏；旧标记「`90001 的`」「`数码`」作为反例注释在案。
11. 门禁 `eval` 步仍产 `EVAL DONE`，且同一份 `logs/acceptance/` 日志里出现 `SCORER SELFCHECK` 行。

### 三、单一判据与不变性回归

12. 判据只有一份：`rg -n "^\s+tool_ok = " scripts/run_tool_eval.py` 命中的行全部落在 `judge()` 函数体内（`judge()` 有「期望调工具」与「期望不调工具」两个分支，各写一次是正确形状，不强压成一行）。锚定行首是为把 `rescore_details()` 里那句读取旧明细的 `old_tool_ok = ...` 排除在外——它抄的是历史值，不是第二处判据。
13. 重算脚本不自带断言逻辑：`--rescore` 与 `judge()` 之间没有任何第二处 `tool_ok`/`args_ok`/`status_ok` 计算。
14. **最强的一条**：对 6 份 09-10 明细逐份 `--rescore` 后合并，新旧 `tool_ok` 的差异集合恰好等于那 4 条 id，其余 176 条逐格一致。集合外出现任何一条即为越界改动。
15. 重算读数落盘可复查：`ACTION_ORDER` 17/18 = 94.4%、聚合 172/180 = 95.6%，`...-rescore.csv` 含 `unverifiable` 列。
16. 参数子指标按三分法如实报数，不许折算成一个比率：63 条带期望参数的样本里，(i) 57 条旧 gold 命中且参数真比对过，全对，读作 100%；(ii) 3 条（`ACT-ORD-09/16/17`）放开后合格工具已命中、但明细里 `actual_args` 只落了旧 gold 期望那个工具的 link，换过去的参数当年没存，离线判不了、要重跑才知道；(iii) 3 条（`ACT-ORD-13`/`ACT-ADR-14`/`ACT-RFD-14`）工具仍未命中，天然不可比对。旧判据把 (ii)(iii) 共 6 条一并判成「填错参数」，即工具选择的双算。README 那一格写「已比对 57 条 100%，另 6 条不可离线判定（3 待重跑 + 3 天然不可比）」，**禁止写成 57/60 = 95.0%**。`NOT_FOUND` 离线仍记 2 条未观测（`ACT-ORD-16/17`），条数一并写进去。

### 四、标注集与校验器

17. `python scripts/build_eval_set.py` 退出码 0、无 `FAIL`、无新增 `WARN`；连跑两次 `eval/tool-cases.jsonl` 字节级相同。
18. 新校验规则有反证：临时删掉某条 `cross_tenant` 的 `mustNotLeak` 必须 `FAIL`；临时把标记改成该样本的订单号必须 `FAIL`；注入均还原，还原后第 17 条重新成立。
19. `expect.tool` 为列表时空列表、非法工具名、重复元素三种坏形态都被校验器拦住，各有反证。

### 五、JVM 越权补强

20. 新增 `logisticsSharesTheSameOwnershipGate` 一条用例、内含一正两反三条断言，`.\mvnw.cmd test` 绿，全项目用例数从 103 增至 >= 104（该类 5 -> 6）。

### 六、文档一致性与落点

21. README 三处同口径：三处都能 grep 到 `95.6%` 与 `93.3%` 并列，承诺项结论仍是「未达成」；4 组对偶共 8 个 id 全部在 README 正文可 grep 到。
22. `docs/adr/0021-*.md` 存在、编号紧跟 0020、含「业务能力读数」与「量具缺陷归因」两个小节，两段不互引对方数字作结论；ticket 16 有 `**追加决策（2026-09-11` 段与 >= 2 条追加追问；ticket 12 记一句越权断言下沉。
23. 问答库重生成：打印问数 >= 83、覆盖 20/20 ticket、无「缺收尾记录的 ticket」（本票必须有 `## Handoff notes`，否则覆盖率会掉到 19/20）；`docs/interview-qa.md` 头部那句「共 N 问，覆盖 M 个 ticket」与正文里以 **Q 开头的条目数一致。另记一条坑：`collect_interview_questions.py` 按行正则收追问，`- *Q：…* A：…*` 必须写成单行，换行续写会被截成半句。

### 七、零额度与整体验收

24. 零消耗可证：改动合入前后各读一次 `GET /ops/circuit` 的 `tokensUsedToday`，差值为 0；`run-dev-eval.ps1` 只跑干跑，停在提示加 `-Run` 那一行，18 条重跑命令写进本 ticket。整轮 17 步门禁绿，`logs/acceptance-run-*.log` 记录的 commit 与 README 矩阵行一致。EOL 另查（本仓库 `git diff --check` 不是信号）：改动的 `.py`/`.jsonl` 仍全 CRLF，Java 与 README/CONTEXT/PLAN 仍各自原样，无 BOM 变化。

## 取证命令

```
python scripts/verify_eval_judge.py                     # 22 条断言：变异反证 + 校验器防呆 + 生成物稳定 + 判据形状 + EOL + 工作树未被污染
python scripts/run_tool_eval.py --selfcheck             # SCORER SELFCHECK ok=14
python scripts/build_eval_set.py                        # 退出码 0，无 FAIL / 无新增 WARN
python scripts/run_tool_eval.py --rescore `
  eval/results/tool-eval-20260910-080638-dev-budgetfix.csv `
  eval/results/tool-eval-20260910-080732-dev-budgetfix.csv `
  eval/results/tool-eval-20260910-080830-dev-budgetfix.csv `
  eval/results/tool-eval-20260910-080926-dev-budgetfix.csv `
  eval/results/tool-eval-20260910-080942-dev-budgetfix.csv `
  eval/results/tool-eval-20260910-075747-dev.csv `
  --rescore-expected-diff ACT-ORD-09,ACT-ORD-11,ACT-ORD-16,ACT-ORD-17
```

`--rescore` 的位置参数顺序**必须**是 5 份按意图补跑在前、`075747` 全量在后：重算按「先出现者胜」合并，这个顺序才复现得出 README 那张矩阵的同一批明细。落盘产物 `eval/results/tool-eval-20260911-042142-rescore.csv`。

## 明确不做

合并工具；改任何阈值；花钱重跑 dev；新增门禁步骤；改 TTFT 口径（那是交接文档第 2 件，另走 ADR 0022）；把 `mustNotLeak` 改成注释或删掉。

## 备用的花钱路线

要在「新判据 + 实测」而非「新判据 + 重算」上拿那一格，跑这一条（18 条，约 5.4 万 token）。预算不用手工抬：`run-dev-eval.ps1` 按「当天已用 + 同规模历史均值 × 条数」推导并临时抬高，跑完 `finally` 无条件原样写回并把网关放回 local。

本票顺带给 `run-dev-eval.ps1` 补了 `-OnlyIntent` 透传（原先只有 `-Limit`，没有它下面第一条命令根本到不了 `run_tool_eval.py --only-intent`，写出来的就是一条跑不通的备用路线）：

```
pwsh -NoProfile -File scripts/run-dev-eval.ps1 -OnlyIntent ACTION_ORDER        # 干跑，只自查、摆位置、打印评测命令
pwsh -NoProfile -File scripts/run-dev-eval.ps1 -OnlyIntent ACTION_ORDER -Run   # 真跑，花钱
```

全量重跑的备用命令是同一支去掉 `-OnlyIntent`（180 条，约 26.7 万 token，会顶到 26 万日预算）。

## Handoff notes

**关键决策（收尾时补记，与文首「决策」的区别是：那 6 条是动手前拍板的，这里是实现中被事实改写的部分）**

1. 换串号标记时踩到计划里没写的一层：品类名与快递商名**不能**当标记。它们来自 `SeedRunner` 的全局
   `CATEGORIES` / `CARRIERS` 词表，任何租户谈自家生意都可能合法说出；街道名同理（`文三路` 四条种子订单共用），
   只有带门牌的整串才私密。所以计划里写的 `["果蔬","13800001234"]` 是错的（果蔬既不是 90001 的字段，
   也不能当标记），最终取 `演示买家` / `13800001234`，两条改地址样本另加 `文三路 1 号`，并把这条约束
   做成校验器的 `SHARED_VOCAB` 防呆，而不是记在脑子里。
2. 参数子指标最终按三分法报，不是"6 降到 3"：明细的 `actual_args` 只存了**旧 gold 期望那个工具**的入参
   （`link` 按旧期望工具名取），所以放开合格答案集之后换过去的工具参数离线根本取不到。三条是"待重跑"、
   三条是"天然不可比对"，性质不同，混成一个分母就会算出 `57/60 = 95.0%` 这个不存在的读数。
3. 越权补强的落地形态从"给老用例加两条断言"改成"新增一条独立用例"：`logisticsSharesTheSameOwnershipGate`
   一正两反，正向只断"不是 `NOT_FOUND`"——10001 的状态由随机种子决定，未发货时它该回
   `STATE_NOT_ALLOWED`，不能对归属者谎称查不到这单（ticket 03 修过这个谎）。用例总数因此是 103 → 104
   而不是计划里写的 ≥105，验收第 20 条按实然改写。
4. 判据唯一性那条验收原写"`rg tool_ok =` 恰好 1 处"，实现中发现它会被 `judge()` 的两个分支（期望调工具 /
   期望不调工具）自然打破，也会被 `rescore_details()` 里那句读历史值的 `old_tool_ok = ...` 误伤。
   改成结构断言：锚定 `^\s+tool_ok = ` 且全部落在 `judge()` 体内。量的是"判据只有一份"，不是"只有一行"。
5. 证据从"手工截图"升级为一份可重跑的证据跑器：`scripts/verify_eval_judge.py`，22 条断言，全部改动在
   仓库外 `tempfile` 副本上做，跑完反查工作树未被污染。变异反证只有在"改坏了必须红"成立时才算证据，
   所以对照组（干净副本必须绿）与被试组同批断言。

**追加追问**

- *Q：改标注是不是为了让数字过线？* A：八个 id、四条问句原话都钉在 README 指标表那一格里，读者可以自己对照 `eval/cases-part2-action.jsonl` 复核；判据原文是分意图各 >= 95%，新判据下仍有 5 行未达线，承诺项结论没变。另外差异集合由 `--rescore-expected-diff` 锁死成恰好那 4 条，多放开一条重算命令就非零退出。
- *Q：95.6% 越过了 95%，为什么还写未达成？* A：因为承诺项的量纲是分意图，不是聚合；聚合那格并列两套读数（93.3% 与 95.6%）正是为了防止它被单独引用成"过了"。
- *Q：重标为什么不重跑？* A：重标只改判据、不改样本采集，`选对工具` 依赖的实际调用链当年已落进明细，重算与实测共用同一个 `judge()`，同源只差采样；参数与 `NOT_FOUND` 离线补不出，前者记成不可比对、后者下沉成 JVM 断言，都不靠嘴说。花钱那条约一键预检命令留在本票「备用的花钱路线」。
- *Q：这套改动怎么保证不是又一次"把测试改成能过"？* A：三类反证都在机器上：把三处量具修复分别改回旧语义，`--selfcheck` 必须红；把坏标注（空合格集、非法工具名、重复元素、越权样本缺标记、标记用跨租户共享词）注入仓库外临时副本，`build_eval_set.py` 必须 `FAIL`；阈值常量与 `EVAL DONE` 由断言盯住未在 diff 中出现。跑一遍 `python scripts/verify_eval_judge.py` 就是这 22 条，一条命令全复现。
