# 53 — `feedback` 步：修两条恒失败的读数与一条错刺激

**What to build:** 让 `verify-feedback.ps1` 的三条隐式信号断言**真的开始测量**。现状里两条在结构上不可能通过、一条的刺激与自己的注释矛盾，而被测代码（`FeedbackService`、`ToolDispatcher` 的计数逻辑）按 ADR 0039 实现正确。

**Blocked by:** None（只碰 `scripts/verify-feedback.ps1`）。

**Status:** ready-for-agent（2026-09-25）。

**依据：ADR 0031 第 10 行的事实性修正豁免**（脚手架自身缺陷，不动被测功能行为）。

口径（ADR 0045 已定，本票只执行）：

- **三条红各自的性质不同，别一起改**：
  - ②③（重问 / 幂等重放）**读数 bug**：`Get-Counter`（`:27`）只有位置参数、没有 `ValueFromPipeline`，而 `:103/106/116/123` 用管道调用它 → 参数整体前移一格（`Metrics` 收到指标名、`Name` 收到标签、`Tags` 为空）→ `:28` 的 `$_ -like "$Name*$Tags*"` 永不命中 → 恒返回 `0.0` → `0 -gt 0` 假。**已用仓库自己的 pwsh 7.4.20 复现参数位移。**
  - ①（降级 negative）**刺激错**：`:36` 注释写「用一个必然走降级的问题」，`:42` 实际发的是用来验引用块的正常政策问句（实测 `intent=POLICY_RETURN cache=NONE degraded=false`）→ `fallbackReason` 为空 → 按 `FeedbackService.java:107` 本就不该自增。**注释描述的是意图，代码做的是另一件事。**
- **不许改被测代码**。若修完读数后 `implied_retry` 仍不增，那是模型能力问题（3B 未必两次都发 `applyRefund`），按 round20 spec 的登记处置，不回头改 `FeedbackService` 去迎合脚本。
- **收口要两组读数**（ADR 0045 的 Consequences 强制）：修前红 / 修后绿。门禁从"恒定失败"变成"真的在测"，只给修后绿无法区分两者。

- [ ] `:103/106/116/123` 改为与 `:39/:93` 一致的位置调用形式（或给函数加 `ValueFromPipeline`），并在注释里写明选了哪一种、为什么
- [ ] `negative` 的刺激换成真会走 FALLBACK 的请求（`转人工` → `USER_REQUESTED` 是现成选择，ADR 0042 后由 `verify-fallback.ps1` 7/7 稳定复现）
- [ ] `:36` 的注释改成与代码一致的描述
- [ ] **修前红读数入库**：跑一次 `verify-feedback.ps1` 并留路径式记录
- [ ] **修后绿读数入库**：同上
- [ ] `implied_retry` 若转绿，Handoff 里写清两次 `applyRefund` 是否**真的都发了**（读 `shoppilot_llm_write_nudge_total` / `tool_result` 事件），别把"恰好绿了"当"链路通了"
- [ ] 未改任何 `src/main` 下的 Java（`git status` 自证）
- [ ] `pwsh -NoProfile -File scripts/check-ps-syntax.ps1` 通过

**Verify:**

```powershell
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
pwsh -NoProfile -File scripts/verify-feedback.ps1
```

预期：修后 `反馈闭环验收：PASS 7 / FAIL 0`（或 `implied_retry` 按登记保留红并说明理由）。

## Handoff notes

（收口时补：关键决策、验证落点、三个现场追问）
