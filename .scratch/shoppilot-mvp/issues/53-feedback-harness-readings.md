# 53 — `feedback` 步：修两条恒失败的读数与一条错刺激

**What to build:** 让 `verify-feedback.ps1` 的三条隐式信号断言**真的开始测量**。现状里两条在结构上不可能通过、一条的刺激与自己的注释矛盾，而被测代码（`FeedbackService`、`ToolDispatcher` 的计数逻辑）按 ADR 0039 实现正确。

**Blocked by:** None（只碰 `scripts/verify-feedback.ps1`）。

**Status:** implemented（2026-09-27）。

**依据：ADR 0031 第 10 行的事实性修正豁免**（脚手架自身缺陷，不动被测功能行为）。

口径（ADR 0045 已定，本票只执行）：

- **三条红各自的性质不同，别一起改**：
  - ②③（重问 / 幂等重放）**读数 bug**：`Get-Counter`（`:27`）只有位置参数、没有 `ValueFromPipeline`，而 `:103/106/116/123` 用管道调用它 → 参数整体前移一格（`Metrics` 收到指标名、`Name` 收到标签、`Tags` 为空）→ `:28` 的 `$_ -like "$Name*$Tags*"` 永不命中 → 恒返回 `0.0` → `0 -gt 0` 假。**已用仓库自己的 pwsh 7.4.20 复现参数位移。**
  - ①（降级 negative）**刺激错**：`:36` 注释写「用一个必然走降级的问题」，`:42` 实际发的是用来验引用块的正常政策问句（实测 `intent=POLICY_RETURN cache=NONE degraded=false`）→ `fallbackReason` 为空 → 按 `FeedbackService.java:107` 本就不该自增。**注释描述的是意图，代码做的是另一件事。**
- **不许改被测代码**。若修完读数后 `implied_retry` 仍不增，那是模型能力问题（3B 未必两次都发 `applyRefund`），按 round20 spec 的登记处置，不回头改 `FeedbackService` 去迎合脚本。
- **收口要两组读数**（ADR 0045 的 Consequences 强制）：修前红 / 修后绿。门禁从"恒定失败"变成"真的在测"，只给修后绿无法区分两者。

- [x] `:103/106/116/123` 改为与 `:39/:93` 一致的位置调用形式（或给函数加 `ValueFromPipeline`），并在注释里写明选了哪一种、为什么 —— **选了第三种：加 `Get-Implied([string]$Kind)` 助手，把三参数调用从调用点整体消掉**（理由见 Handoff）
- [x] `negative` 的刺激换成真会走 FALLBACK 的请求（`转人工` → `USER_REQUESTED`，先单独验证过 0 → 1）
- [x] `:36` 的注释改成与代码一致的描述
- [x] **修前红读数**：`logs/r20-t53-feedback-before.txt` → `PASS 4 / FAIL 3`
- [x] **修后读数**：`logs/r20-t53-feedback-after-{a,b}.txt` 等 **4 次连跑** → 3 次 `6/1`、1 次 `7/0`
- [x] `implied_retry` 的转绿与转红都写清了机制（见 Handoff），未当成"链路通了"
- [x] 未改任何 `src/main` 下的 Java（`git status` 自证）
- [x] `pwsh -NoProfile -File scripts/check-ps-syntax.ps1` 通过（`files=30 errors=0`）

**本票额外修的两处**（取证时发现，均属"让门禁真的开始测量"同一性质）：

- **退款用了不拥有该单的买家**：脚本用 `C155` 问 `90002`，而演示固定单 `90001-90004` 的拥有者是 `C001`（`SeedRunner.java:62` 的 `DEMO_CUSTOMER`）→ 归属双条件判 NOT_FOUND → 写操作不成功 → 幂等结果不落库 → 这条断言**在修好读数之后仍然测的是一件不可能发生的事**。改为按本次进程取 token + 用 C001。
- **幂等键写死**：`"idempotencyToken":"verify-feedback-retry-1"` 是固定值，结果按 token 存 Redis → **下一次跑脚本的第一次请求**一开始就是重放，断言可能假绿。改为 `verify-feedback-retry-$PID`，并在写操作前复位演示固定单（与 `run_tool_eval.py` 同例）。

## Handoff notes

**关键决策**

- **取"加助手"而不是"把四处写法改对"**。票面给的选项是「改成位置形式」或「给函数加 `ValueFromPipeline`」。我选了第三种：加一个 `Get-Implied([string]$Kind)`，让六个读数点都不再触碰三参数调用。理由是**这个 bug 的性格**——它不是"写错了"，是"写错之后静默返回 0.0 且判据恰好是 `> before`"，所以它能活几个月。把调用形状改对只是修了这一次；把三参数调用从调用点消灭掉，才让同一个写法写不回来。函数上留了注释说明管道形式为什么会前移参数。
- **`negative` 的刺激用「转人工」而不是"换一个不存在的政策问句"**。注释的原意是"不在政策库且无工具诉求"，但那种问句会走 `INTENT_UNRESOLVED`，它确实降级，可它同时还落一张工单、且与 `verify-fallback.ps1` 的口径重叠。`转人工`（`USER_REQUESTED`）是 ADR 0042 之后最稳的一条降级路径，且已由 `verify-fallback.ps1` 7/7 背书。改之前先单发一次验证计数 0 → 1，不是改完再说。
- **`implied_retry` 的绿不能当"链路通了"——这正是票面第 5 条预警的事，而且真的发生了**。修后四次连跑：**3 次 `6/1`（这条红）、1 次 `7/0`（这条绿）**。机制从 SSE trace 逐帧看清了：失败那次的流转是 `PLAN round=0` → **`corrective=applyRefund`（写动作纠偏轮触发）** → `PLAN round=0` → `done`，`completionTokens=1`、`answer` 为空——**模型在已含上一轮成功答复的会话里不肯再发一次工具调用**，于是重复请求根本没走到网关的幂等层（`ToolDispatcher` 的 `guard.duplicate()`），计数自然不动。绿的那次计数 `0 → 1` 证明**幂等层本身是对的**。所以这条断言的绿红取决于模型这一次肯不肯重复发工具，**是模型行为抖动，不是链路通断**。
- **因此 `implied_retry` 按 ADR 0045 / spec 的登记处置：不修代码去迎合脚本**。要它稳定绿得让重复检测不依赖模型合作（见下面的新发现），那是功能改动、需另立项。

**验证落点**

- 计数读数修正：管道形式在脚本里已清零（`grep "Get-Metrics | Get-Counter"` 只剩注释里那条"别再这么写"的示例）；修后 `negative` 与 `implied_dissatisfied` **每次都涨**（如 `2 → 3`、`10 → 11`），不再恒为 0。
- 修前红 / 修后读数：`logs/r20-t53-feedback-before.txt`（`4/3`）与 `logs/r20-t53-feedback-after-{a,b}.txt`（`6/1`、`6/1`），另两次 `6/1`/`7/0` 未落盘。**`logs/` 不入库，干净克隆里没有**——所以收口读数按本仓惯例写进本 Handoff 与 `docs/EVIDENCE.md`。
- 语法门：`check-ps-syntax.ps1` → `files=30 errors=0`。
- 未动被测代码：`git status --short` 里没有 `src/` 下的文件。

**新发现（登记不执行，需另立项）**

客户端用**同一个 `idempotencyToken` 重试**时，可能拿到**空答案**而不是幂等回放：重复检测在 `ToolDispatcher.dispatch` 里，只有模型真的再发一次工具调用才会被执行；模型不发（见上），请求就走到 `done` 而 `answer` 为空、`plan` 为空、也没有 fallback。我的探针里 **3/3 复现**。这把「幂等」这条承诺的可靠性挂在了模型行为上——`idempotencyToken` 的存在意义恰是"客户端重试不该重复执行、应回放"，而当前实现只在模型配合时才成立。已记进 round20 spec 的登记节，候票 57。

**你需要能当场回答的三个追问**

1. *Q：这条断言现在到底是绿还是红？* A：**不确定，而且这本身是结论**——四次连跑三次红一次绿。红的机制我逐帧看过：模型在含上一轮成功答复的会话里不再发工具调用，重复请求走不到幂等层；绿的那次 `implied_retry` 从 0 变 1，说明幂等层正确。所以**不能说它绿了代表链路通了，只能说这次模型配合了**。
2. *Q：那为什么不一劳永逸地改成"按 token 直接判重放"？* A：那正是功能改动，而且要先想清楚一件事——**判重放的时机**。放在模型之前就等于"只看 token、不看用户说了什么"，会误伤"用户换了要求但客户端复用了旧 token"这类情形；放在模型之后就是现状。这是个真决策，ADR 0045 把它登记为待立项，不在本票里拍。
3. *Q：这票改了脚本，算不算"改验收判据让结果变绿"？* A：不算，方向和那条铁律相反——**改之前那两条断言恒为 `0 > 0` 恒假，即"从来没测过"；改之后它们真的开始读计数**。两条读数 bug 是脚手架缺陷（ADR 0031:10 豁免），`negative` 的刺激错和退款的买家错同属这一类：它们都不是"判据太严"，而是"刺激根本触发不了判据"。**修完仍有红的（`implied_retry`）我原样留着并写清了机制**，一条没摘。
