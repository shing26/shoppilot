# 54 — `emotion` 步：`EMO-ESC-02` 重分类为显式转人工样本

**What to build:** 让 `verify-emotion.ps1` 的样本集与 ADR 0042 对齐。现状唯一失败项 `EMO-ESC-02` 的问句含 T0 升级词表里的「转人工」，按 ADR 0042「显式转人工优先于情绪判定」落 `USER_REQUESTED` 是**正确行为**——红的是用例数据，不是代码。

**Blocked by:** None（碰 `scripts/verify-emotion.ps1`、`eval/cases-part4-emotion.jsonl`、`scripts/eval_suites.py`、必要时 `SentimentGateTest`）。

**Status:** implemented（2026-09-27）。

**依据：ADR 0031 第 10 行的事实性修正豁免**（用例数据与已锁定 ADR 冲突）。但它触碰验收断言面，所以本票的修法必须取「重分类 + 新增」而不是「把期望改成能过的那个」。

口径（ADR 0045 已定，本票只执行）：

- **取重分类，不取改期望**：把 `EMO-ESC-02` 直接改成期望 `USER_REQUESTED` 会让这条用例**不再测情绪驱动的升级**；改问句去掉「转人工」要动 part4 的 query。正解是把它移到"显式转人工"类目，并**新增**一条**不含任何升级词**的强情绪样本补回「词典层升级」的计数（add-only）。
- **新增样本的两条硬要求**：① 机械核对**不含** `ESCALATE_WORDS` 的七个词（`转人工`/`转个人`/`人工客服`/`真人客服`/`人工服务`/`转接人工`/`人工介入`）；② 它的情绪必须能被**词典层单独定案**（不能依赖第二层——local 档只有词典层，见 ADR 0043）。
- **不要被 `$escalations` 里的 `emotion` 字段误导**：该字段在脚本里是死代码，判据只读 `fallbackReason` 与 `ticketId`。
- **`eval_suites.py` 必须同步**：`:69-76` 的 emotion 分支把 `EMOTION_ESCALATION` 硬编码成唯一的升级证据（`escalate_ok`），单改 part4 的 `expect.reason` 会让离线套件必然红。
- **自述要换代**：round17 spec 与 README 里「词典层 8 条」的计数会变，按新基数改写并留换代指针。

- [x] `EMO-ESC-02` 从 `$escalations` 移出，新增「显式转人工」类目（期望 `USER_REQUESTED`，**不**要求 `priority=high`——只有 `EMOTION_ESCALATION` 才传 `high`）
- [x] 新增一条不含升级词的强情绪样本（`EMO-ESC-09`：`什么破玩意儿！收到就是坏的，你们这质量也太差了`），先机械核对七个词零命中，再确认词典层靠 `破玩意儿` 单独定案 ANGRY（local 档无第二层）
- [x] `eval/cases-part4-emotion.jsonl` 按 add-only 新增 `EMO-ESC-09`；`EMO-ESC-02` 那一行改了 `intent`（→ `ESCALATE`）与 `expect`（→ `{escalate: false, reason: "USER_REQUESTED"}`），理由见 Handoff
- [x] `scripts/eval_suites.py` 的 24 条 `selfcheck` 夹具仍 `SUITE SELFCHECK ok=24` —— **判分器一行未改**，因为 `escalate: false` + `reason` 已在既有判据的表达力之内（算过才确定，见 Handoff）
- [x] `SentimentGateTest`：新增 `EMO-ESC-09`（8 → 9），计数断言 `8.0d` → `9.0d`，DisplayName 同步；`EMO-ESC-02` 的样本**留在这里没删**（该用例不经状态机，ADR 0042 管不到它）
- [x] round17 spec、`README.md`、票 36 的「词典层 8 条」自述换代 + 换届指针
- [x] **修前红 / 修后绿两组活体读数**：修前 `logs/acceptance/emotion.log`（2026-09-24 矩阵，`EMO-ESC-02 FAIL`）；修后 `logs/r20-t54-emotion-after.txt` → **`PASS 30 / FAIL 0`、exit 0**
- [x] **额外修掉一处中止**（票面未列，修完 02 才发现）：工单队列反查缺 bearer → 401 → 脚本中止，导致 8 条 priority 反查与 12 条非升级断言从未运行
- [x] `pwsh -NoProfile -File scripts/check-ps-syntax.ps1` 通过（`files=30 errors=0`）

**Verify:**

```powershell
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
python scripts/eval_suites.py
pwsh -NoProfile -File scripts/verify-emotion.ps1
```

预期：`verify-emotion.ps1` 全过；`eval_suites.py` 夹具仍 24 条全绿。

## Handoff notes

**关键决策**

- **取"重分类 + 新增"，不取"把期望改成能过的那一个"**（ADR 0045 已定，此处只记执行）。直接把 `EMO-ESC-02` 的期望改成 `USER_REQUESTED` 会让这条用例**不再测情绪驱动的升级**；改问句去掉「转人工」则要动 part4 的 query。最终把 02 移入"显式转人工"一类、用新增的 `EMO-ESC-09` 补位——**「词典层 8 条」这个计数因此不变，变的是成员**，所以文档只需换届指针、不必改基数。
- **显式转人工那一类的判据是两条，不是一条**：`reason == USER_REQUESTED` **且工单不带 high**。后者不是装饰——只有 `EMOTION_ESCALATION` 才传 `high`（`AgentStateMachine` 的 escalate 分支），所以"带不带 high"正是两类出口的区分点；只断言 reason 会把两者都当成"升级了"，看不出路由错。
- **`EMO-ESC-02` 留在 `SentimentGateTest` 里没删**。那个测试直接测 `SentimentGate`、**不经状态机**，ADR 0042 管不到它：02 的问句含 `破店`（`strong_anger`），词典层照样定案 ANGRY，所以它是一条有效的词表样本，只是活体那边归到了另一类。删掉它才是过度更正。
- **`eval_suites.py` 一行没改，而且是先算过才确定的**。取证结论说"改 02 的期望就得动判分器"（`escalate_ok` 把 `EMOTION_ESCALATION` 硬编码成唯一升级证据）。实际算一遍：`escalate_ok = (fallback == "EMOTION_ESCALATION") == bool(expect.escalate)`，取 `escalate: false` 时 `False == False` 成立，`reason_ok` 再逐字钉住 `USER_REQUESTED`；两条合起来完整表达了"该转人工、但不是情绪驱动的"。**既有判据的表达力够，就不扩判据**——24 条夹具自检照旧全绿。
- **新增样本先活体验证再落文件**：先单发一次确认 `fallbackReason=EMOTION_ESCALATION`、工单 `priority=high`、七个升级词零命中，才写进 part4 与脚本。避免"写进去再跑，红了才回头查"。
- **额外发现并修掉一处中止（本轮第 5 个脚手架缺陷）**：队列反查把 `/api/v1/support/ops/tickets` 当纯运维端点调用（只带 `X-Ops-Token`），而它走 JWT 鉴权 → 401 → `ErrorActionPreference=Stop` 当场中止。后果是**那 8 条 priority 反查与 12 条非升级断言在矩阵里从来没跑过**——该步长期"跑到第 9 条就结束"，而矩阵只看到 exit 1，把它与 `EMO-ESC-02` 那一条红混成同一个读数。**这条不是票面列的**，是修完 02 之后发现"怎么还是 exit 1"才挖出来的；补上 bearer 后断言数从 9 变 30。

**验证落点**

- part4：21 行合法 JSONL、无重复 id、ESC 组 9 条（`EMO-ESC-01..09`）。
- `SentimentGateTest`：`Tests run: 8, Failures: 0`（含 `9.0d` 的新计数断言）。
- `python scripts/eval_suites.py` → `SUITE SELFCHECK ok=24`（判分器未改，夹具仍全绿）。
- 活体修后：`logs/r20-t54-emotion-after.txt` → **`PASS 30 / FAIL 0`、exit 0**；其中 9 条 priority 反查（8 张 `high` + 显式那张 `priority=` 空）与 12 条非升级断言**是第一次真正运行**。
- 语法门：`check-ps-syntax.ps1` → `files=30 errors=0`。

**你需要能当场回答的三个追问**

1. *Q：把期望从 `EMOTION_ESCALATION` 改成 `USER_REQUESTED`，这不就是"改判据让结果变绿"吗？* A：方向相反。改动前那条断言**与已锁定的 ADR 0042 直接冲突**——ADR 0042 规定显式转人工优先，所以 `USER_REQUESTED` 才是正确行为，红的是用例数据而不是系统。而且判据没有被改松：新类目判的是**两条**（reason 逐字 + 工单不带 high），比原来那条更细；`EMO-ESC-02` 的样本一条没删（gate 测试那边还留着），另加一条 `EMO-ESC-09` 补位，**词典层样本总数没减**。
2. *Q：`EMO-ESC-09` 凭什么保证触发情绪升级？* A：靠词典层而不是靠模型——它含 `破玩意儿`，在 `sentiment/lexicon.yml` 的 `strong_anger` 里，命中即定案 ANGRY（ANGRY 优先于 URGENT），**不需要第二层 LLM**——这很关键，因为 local 档按 ADR 0043 只有词典层。落文件前单发验证过：`fallbackReason=EMOTION_ESCALATION`、工单 `priority=high`、七个升级词零命中。
3. *Q：这票为什么断言数从 9 变成了 30？* A：因为原来**根本没跑完**。队列反查只带了 `X-Ops-Token`，而该端点走 JWT 鉴权 → 401 → `ErrorActionPreference=Stop` 直接中止，所以 8 条 priority 反查与 12 条非升级断言从未执行，矩阵看到的"1 条红"其实是"跑到第 9 条就断了"。**这提醒一件事：一个 exit 1 的步骤，要看清它是"某条断言红了"还是"中途崩了"**——前者的证据力是判据结论，后者只是中断信号。我这次差点把两者当成一回事（上一次跑完我用 `EXIT=$?` 取到的其实是 `grep` 的退出码，误判成"全过"）。
