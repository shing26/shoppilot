# 55 — `plansteps`：更正两处不实登记，并把该步登记为已知不达成

**What to build:** 只改登记材料，判据一字不动。现状 `plansteps` 步 0/7，机制是 local 档 `qwen2.5:3b` 不产生两步链（`shoppilot_plan_steps_total{steps="2"}` 恒 0），语义已由 `PlanExecutionTest` 5 项确定性地钉住。但这条红的**登记材料本身有两处不实**，会误导下一个人。

**Blocked by:** None（纯文档，只碰 `.scratch/`、`docs/`、`README.md`）。

**Status:** implemented（2026-09-27）。

**依据：ADR 0031 第 10 行的事实性修正豁免**（改的是登记，不是判据）。

口径（ADR 0045 已定，本票只执行）：

- **判据不动**。ADR 0043 的 Considered Options 明写「把判据改窄去适配实现，本仓明令禁止」，AGENTS.md 也禁「不改验收判据、阈值来让结果变绿」。所以 7 条判据原样保留、该步在 local 档**照红**。
- **两处不实登记**：
  1. round17 spec 的 F3 行（`:106`）把 `PlanExpressionTest` 列为 0 token 覆盖源，而**该文件在仓内不存在**（只有 `main/.../agent/PlanExpression.java`）；实际覆盖者是 `PlanExecutionTest` 第 2 条（注入形态整条拒收）与第 3 条（引用未执行的前步 / 缺失字段）。**全仓只此一处引错**——票 39 引的是正确的 `PlanExecutionTest`（取证时误记为"两处"，本票一并更正 round20 spec / ADR 0045 / tracker 里的同一说法）。
  2. `steps=2:0 aborted:7` 的 `aborted:7` 是**进程启动以来累计**的 Prometheus 计数器，被当成本次 7 条用例的读数。只有 `steps=2:0` 与 `rejected:0` 对「从未达成」是硬证据。
- **补一句可自证的取证口径**：`verify-plan.ps1` 的 `Get-ToolSteps`（`:24-26`）只筛 `TOOL_EXEC` 行、把 `round=` 行丢了，所以**归因无法从该脚本自证**；要看完整响应 trace 的 `round=` 序列，或加读 `shoppilot_llm_multi_tool_calls_total`（识别"一次回复塞两个调用、代码只取第一个"这个真实混淆项）与 `shoppilot_llm_write_nudge_total`。
- **可选实验，不作为收口条件**：把 local 模型换 `qwen2.5:7b` 试一次两步链。**必须走 `.env`**（`SHOPPILOT_LOCAL_LLM_MODEL`；WMI launcher 只认 `.env`，shell 里 export 无效，见 `docs/EVIDENCE.md`），零仓内 diff。试了按"可选实验"登记，不改任何判据。

- [x] 更正 `PlanExpressionTest` 引用（round17 spec F3 行 `:106`）→ 改为 `PlanExecutionTest` 第 2/3 条；并更正 round20 spec、ADR 0045、tracker 里"两处"的不准确说法（票 39 引的是对的）
- [x] 在 round17 spec 的 F3 行与 `docs/EVIDENCE.md` 的 22 步矩阵行写明 `aborted` 是进程生命周期累计、`steps=2:0` 与 `rejected:0` 才是硬证据
- [x] 补「该脚本丢了 `round=` 行 → 归因不能自证」的取证口径，并给出可替代的计数器
- [x] 把该步在 local 档登记为**已知不达成**，措辞与 README 已有的「未达成照登」一致
- [ ] （可选）`.env` 换 `qwen2.5:7b` 试两步链，读数按可选实验登记 —— **本票未做**，理由见 Handoff
- [x] 本票**不产生代码改动**：`git status --short` 只应有 `.scratch/`、`docs/`、`README.md` 下的文件
- [x] `git diff --check` 干净

**Verify:**

```powershell
git diff --check
git status --short
```

预期：改动只在文档路径内；`plansteps` 步的判据与读数一个字都没变。

## Handoff notes

**关键决策**

- **判据一个字都没改，该步照红。** ADR 0043 的 Considered Options 明写「把判据改窄去适配实现，本仓明令禁止」，所以 7 条判据原样保留、`plansteps` 在 local 档继续红，本票只改登记。这条比看起来重要：它是"未达成照登"这条纪律在**本地模型能力**这个新场景下的第一次适用——之前照登的都是"判据达标但数字没到"，这次是"判据本身在当前口径下不可达"。
- **"两处不实"这个说法在取证报告里就不准，动手前核对了一次才没写错。** 取证结论是「round17 spec 的 F3 行与票 39 **都**引 `PlanExpressionTest`」。我按票面去改之前先 grep 了一遍全仓，发现**只有 F3 行一处**引它——票 39 引的是正确的 `PlanExecutionTest`（其 `:12` 与 `:68` 逐字正确）。所以实际只需要改一处，而票面、round20 spec、ADR 0045 里那句"票 39 也引"是我照抄取证结论带进来的**新错误**，随本票一并更正。**教训写在这儿：核对过再"更正"，否则更正本身会变成新的失真源。**
- **`aborted` 的语义更正写进了两处而不是一处**：F3 行（讲 Plan 的地方）与 `docs/EVIDENCE.md` 的 22 步矩阵行（讲矩阵读数的地方）。理由是 2026-09-24 那次误读就发生在读矩阵读数的时候，只在 F3 行写不足以拦住同一个误读。
- **可选实验（换 `qwen2.5:7b`）没做，理由是它会得出不可用的结论**：本机 15.8 GB 内存只剩 2.0 GB、4 GB 显存被四套项目 17 个容器争抢，而网关在同样状态下**已经原生 OOM 过一次**（`hs_err_pid29612.log`）。7B 比 3B 更重，最可能的结果是"加载不起来"——那既不能证伪、也不能证实"3B 能力上限"这条归属，只会往读数里加一条环境噪声。**留给在一台没被挤满的机器上做**，判据与本票无关（任何换模型实验都不得改判据）。

**验证落点**

- 覆盖源更正：`round17-spec-architecture-completeness.md:106`（F3 行）不再引 `PlanExpressionTest`，改为 `PlanExecutionTest` 第 2/3 条并注明全仓只此一处引错。
- 计量语义：F3 行与 `docs/EVIDENCE.md:48` 都写明 `aborted` 是进程生命周期累计、`steps="2"` 与 `rejected` 恒 0 才是硬证据。
- 取证口径：F3 行与 EVIDENCE 都补了「`Get-ToolSteps` 只筛 `TOOL_EXEC`、丢了 `round=` 行 → 归因不能自证」，并给出替代读数（`shoppilot_llm_multi_tool_calls_total` / `shoppilot_llm_write_nudge_total`）。
- 已知不达成：F3 行与 EVIDENCE 都登记该步在 local 档不达成、判据不改。
- 本票无代码改动：`git status --short` 只出现 `.scratch/` 与 `docs/` 下的文件（`git diff --check` 干净）。

**你需要能当场回答的三个追问**

1. *Q：`plansteps` 一直红，你凭什么说它不是缺陷？* A：因为判据要的两步链**在当前口径下不可达**，而可达性由模型能力决定、不由代码决定。证据是硬读数 `shoppilot_plan_steps_total{steps="2"}` 恒 0 与 `{steps="rejected"}` 恒 0——不是"代码吞了第二步"，是**从来没产生过完整两步链**。代码侧的语义（前序依赖取值、注入形态拒收、前步失败中止）由 `PlanExecutionTest` 5 项在确定性桩下钉住，那些是绿的。**所以红的是"本地 3B 能不能链式调用"，这不该由一份判据来承担。**
2. *Q：那为什么不把判据改成只在 dev 跑？* A：那正是 ADR 0043 明令禁止的"把判据改窄去适配实现"。改完你会在报告里看到 `plansteps` 绿，但那个绿只说明"我没测"——本仓宁可留一条带解释的红。要真让它绿，得换到 dev/云端口径重跑，或另开一轮讨论"local 档的 Plan 判据该长什么样"（那是判据决策，不是修 bug）。
3. *Q：这次更正的教训是什么？* A：**取证结论也要核对，尤其当它要变成"更正"的时候。** 取证报告说"两处都引 `PlanExpressionTest`"，我如果照抄，就会在三个文件里删掉一个正确的引用——那等于用一次更正制造三处新错误。动手前 grep 一遍全仓（`grep -rn PlanExpressionTest`），成本几秒。
