# 55 — `plansteps`：更正两处不实登记，并把该步登记为已知不达成

**What to build:** 只改登记材料，判据一字不动。现状 `plansteps` 步 0/7，机制是 local 档 `qwen2.5:3b` 不产生两步链（`shoppilot_plan_steps_total{steps="2"}` 恒 0），语义已由 `PlanExecutionTest` 5 项确定性地钉住。但这条红的**登记材料本身有两处不实**，会误导下一个人。

**Blocked by:** None（纯文档，只碰 `.scratch/`、`docs/`、`README.md`）。

**Status:** ready-for-agent（2026-09-25）。

**依据：ADR 0031 第 10 行的事实性修正豁免**（改的是登记，不是判据）。

口径（ADR 0045 已定，本票只执行）：

- **判据不动**。ADR 0043 的 Considered Options 明写「把判据改窄去适配实现，本仓明令禁止」，AGENTS.md 也禁「不改验收判据、阈值来让结果变绿」。所以 7 条判据原样保留、该步在 local 档**照红**。
- **两处不实登记**：
  1. round17 spec 的 F3 行与 `issues/39-plan-ordered-steps.md` 都把 `PlanExpressionTest` 列为覆盖源，而**该文件在仓内不存在**（只有 `main/.../agent/PlanExpression.java`）；实际覆盖者是 `PlanExecutionTest` 第 2 条（注入形态整条拒收）与第 3 条（引用未执行的前步 / 缺失字段）。
  2. `steps=2:0 aborted:7` 的 `aborted:7` 是**进程启动以来累计**的 Prometheus 计数器，被当成本次 7 条用例的读数。只有 `steps=2:0` 与 `rejected:0` 对「从未达成」是硬证据。
- **补一句可自证的取证口径**：`verify-plan.ps1` 的 `Get-ToolSteps`（`:24-26`）只筛 `TOOL_EXEC` 行、把 `round=` 行丢了，所以**归因无法从该脚本自证**；要看完整响应 trace 的 `round=` 序列，或加读 `shoppilot_llm_multi_tool_calls_total`（识别"一次回复塞两个调用、代码只取第一个"这个真实混淆项）与 `shoppilot_llm_write_nudge_total`。
- **可选实验，不作为收口条件**：把 local 模型换 `qwen2.5:7b` 试一次两步链。**必须走 `.env`**（`SHOPPILOT_LOCAL_LLM_MODEL`；WMI launcher 只认 `.env`，shell 里 export 无效，见 `docs/EVIDENCE.md`），零仓内 diff。试了按"可选实验"登记，不改任何判据。

- [ ] 更正两处 `PlanExpressionTest` 引用（round17 spec F3 行、`issues/39-plan-ordered-steps.md`）→ 改为 `PlanExecutionTest` 第 2/3 条
- [ ] 在 round17 spec 的 F3 行与 `docs/EVIDENCE.md` 的 22 步矩阵行写明 `aborted` 是进程生命周期累计、`steps=2:0` 与 `rejected:0` 才是硬证据
- [ ] 补「该脚本丢了 `round=` 行 → 归因不能自证」的取证口径，并给出可替代的计数器
- [ ] 把该步在 local 档登记为**已知不达成**，措辞与 README 已有的「未达成照登」一致
- [ ] （可选）`.env` 换 `qwen2.5:7b` 试两步链，读数按可选实验登记
- [ ] 本票**不产生代码改动**：`git status --short` 只应有 `.scratch/`、`docs/`、`README.md` 下的文件
- [ ] `git diff --check` 干净

**Verify:**

```powershell
git diff --check
git status --short
```

预期：改动只在文档路径内；`plansteps` 步的判据与读数一个字都没变。

## Handoff notes

（收口时补：关键决策、验证落点、三个现场追问）
