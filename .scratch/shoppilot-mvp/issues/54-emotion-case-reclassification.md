# 54 — `emotion` 步：`EMO-ESC-02` 重分类为显式转人工样本

**What to build:** 让 `verify-emotion.ps1` 的样本集与 ADR 0042 对齐。现状唯一失败项 `EMO-ESC-02` 的问句含 T0 升级词表里的「转人工」，按 ADR 0042「显式转人工优先于情绪判定」落 `USER_REQUESTED` 是**正确行为**——红的是用例数据，不是代码。

**Blocked by:** None（碰 `scripts/verify-emotion.ps1`、`eval/cases-part4-emotion.jsonl`、`scripts/eval_suites.py`、必要时 `SentimentGateTest`）。

**Status:** ready-for-agent（2026-09-25）。

**依据：ADR 0031 第 10 行的事实性修正豁免**（用例数据与已锁定 ADR 冲突）。但它触碰验收断言面，所以本票的修法必须取「重分类 + 新增」而不是「把期望改成能过的那个」。

口径（ADR 0045 已定，本票只执行）：

- **取重分类，不取改期望**：把 `EMO-ESC-02` 直接改成期望 `USER_REQUESTED` 会让这条用例**不再测情绪驱动的升级**；改问句去掉「转人工」要动 part4 的 query。正解是把它移到"显式转人工"类目，并**新增**一条**不含任何升级词**的强情绪样本补回「词典层升级」的计数（add-only）。
- **新增样本的两条硬要求**：① 机械核对**不含** `ESCALATE_WORDS` 的七个词（`转人工`/`转个人`/`人工客服`/`真人客服`/`人工服务`/`转接人工`/`人工介入`）；② 它的情绪必须能被**词典层单独定案**（不能依赖第二层——local 档只有词典层，见 ADR 0043）。
- **不要被 `$escalations` 里的 `emotion` 字段误导**：该字段在脚本里是死代码，判据只读 `fallbackReason` 与 `ticketId`。
- **`eval_suites.py` 必须同步**：`:69-76` 的 emotion 分支把 `EMOTION_ESCALATION` 硬编码成唯一的升级证据（`escalate_ok`），单改 part4 的 `expect.reason` 会让离线套件必然红。
- **自述要换代**：round17 spec 与 README 里「词典层 8 条」的计数会变，按新基数改写并留换代指针。

- [ ] `EMO-ESC-02` 从 `$escalations` 移出，新增「显式转人工」类目（期望 `USER_REQUESTED`，**不**要求 `priority=high`——只有 `EMOTION_ESCALATION` 才传 `high`）
- [ ] 新增一条不含升级词的强情绪样本，先机械核对七个词零命中，再确认词典层能单独定案 ANGRY/URGENT
- [ ] `eval/cases-part4-emotion.jsonl` 按 add-only 新增显式转人工 case 与新的强情绪 case；若改了 `EMO-ESC-02` 那一行，在 Handoff 里单列理由
- [ ] `scripts/eval_suites.py` 的 emotion 分支与其 24 条 `selfcheck` 夹具同步，`python scripts/eval_suites.py` 仍 `SUITE SELFCHECK ok=24`
- [ ] `SentimentGateTest.LEXICON_ESCALATIONS` 若含原串则同步（该用例不经状态机，ADR 0042 不影响它，但样本要同源）
- [ ] round17 spec 与 `README.md` 的「词典层 8 条」计数换代 + 换届指针
- [ ] **修前红 / 修后绿两组活体读数**（`verify-emotion.ps1`）
- [ ] `pwsh -NoProfile -File scripts/check-ps-syntax.ps1` 通过

**Verify:**

```powershell
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
python scripts/eval_suites.py
pwsh -NoProfile -File scripts/verify-emotion.ps1
```

预期：`verify-emotion.ps1` 全过；`eval_suites.py` 夹具仍 24 条全绿。

## Handoff notes

（收口时补：关键决策、验证落点、三个现场追问）
