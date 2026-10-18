# 142 轮次切分与开轮依据 ADR（0069）

**Label:** wayfinder:grilling　**Status:** implemented（收敛票：三件产物 = ADR 0069 + spec v2 + 拆票表，零代码改动）　（map：[135](135-wayfinder-agent-autonomy-map.md)）

**Claimed by:** zcode wayfinder session 2026-10-19（第七张/收官：142）

## Question

**收敛票**：把 136-141 的决策收成两件产物——① **ADR 0069 草稿**（开轮依据：覆盖 ADR 0008/0036 数字的依据、
失败三分法语义、spec 决策 1-6 的重锚结论；旧 ADR 原文不动加换代指针，照 ADR 0043 格式）；
② **轮次切分与拆票顺序**（拆几轮/几张实现票、票号从 143 起、每票的 Verify 与判据面）。

## 已核实的事实（不必重查）

- ADR 编号：下一个是 **0069**（0068 已被 round34 占用）；票号：135-142 为本 map，实现票从 **143** 起。
- spec v1 的计划轮廓已过时（「ADR 0052」号位让给了 program、「票号 69 起」「round23 只做 agent 升级」都失效）——
  v2 的定稿在本票完成（作为本票的 linked asset，不粘贴进 body）。
- 开轮本身仍由**所有者政策覆盖**决定（v1.0.0 冻结线；ADR 0030 五条触发未命中）——
  ADR 0069 里要写明「本轮的覆盖依据」，但不预设所有者一定批。
- 一次一票、Handoff 三问、判据红线（gold/阈值/分母/既有验收）照旧。

## 口径

- 本票的 resolution = ADR 0069 草稿 + spec v2 定稿 + 拆票表（三件 linked assets）。
- **不写实现代码**；map 到本票为止（destination 达成）。

## Blocked by

[136](136-autonomy-budget-and-steps.md)、[137](137-safety-gates-non-retryable.md)、[138](138-failure-triage-reclass.md)、
[139](139-reflection-observability-and-enum.md)、[140](140-eval-suite-and-dev-budget.md)、[141](141-prompt-v1-2-0.md)（全部）。

## Verify

```powershell
git diff --check && git status --short
python .scratch/shoppilot-mvp/round3-closeout-audit.py
```

## Resolution（2026-10-19，收敛票 + 三件产物，零代码改动）

**会话记录**：本 session 无所有者在场。137-140 三票的「建议值 + 修正入口 142」**未被触发**——建议值原样
进入 ADR 0069 与 spec v2（修正入口改为：所有者点头时一并修正，改动面 = ADR 0069 对应节 + spec v2 对应节）。
141 已获裁定的两问照录。

### 一、三件产物（linked assets）

| 产物 | 落点 | 一句话 |
| --- | --- | --- |
| ADR 0069 | [`docs/adr/0069-round35-agent-autonomy-upgrade-reopen-basis.md`](../../../docs/adr/0069-round35-agent-autonomy-upgrade-reopen-basis.md) | **开轮依据**：round35 = agent 自主性升级；归类 = 所有者政策覆盖（round23 共识 + wayfinder 指令）；**Status = proposed——执行闸门 = 所有者点头** |
| spec v2（= round35 spec） | [`agent-autonomy-spec-v2.md`](../agent-autonomy-spec-v2.md) | 六决策重锚表 + 机制契约（配置/反思六条/闸三段式/观测面）+ 评测与预算 + **§4 拆票表** + 风险与前置检查 |
| spec v1 | [`agent-autonomy-spec.md`](../agent-autonomy-spec.md) | 原文保留（六决策 §1 不动），顶部加换代指针指向 v2 |

**ADR 0069 的五个要点**：① 六决策按当前结构重锚（每条一行，依据到票）；② 覆盖 ADR 0008/0036 数字的依据 = **三账**
（能力：4 = 三步链 + 1 反思余量；安全：职责在每步守卫与循环外出口、与循环长度解耦；有界：4 仍是硬上限，
超限照旧 `TOOL_ROUNDS_EXHAUSTED`）；③ 失败三分法 7 成员四栏 + 反思规则六条收束；④ 四件外部面
（观测 / 提示词 / 评测 / 预算 600k）；⑤ **本轮内两条登记债 R11（票 146）/ R12（票 145）走各自豁免与触发，
不冒充政策覆盖**；不并入项（U7 gold 重跑、三条真缺陷）明写。

**换代指针的落法（B7 窗口约定）**：既有 ADR（0008/0036 等）**文件一字未动**——指针写在本 ADR、spec v2
与 tracker 三处。依据：round3-closeout-audit.py 的 B7 明写「指针不许追加到既有 ADR 顶上」
（round23 票 69 先例：给 0024 加一行指针当场四条红）；ADR 0043 先例的「原文不动」因此落在**文件级**。

### 二、拆票表（= round35 / 实现票 143-150；票面待所有者点头后由 `/to-tickets` 建）

**机制层（全 JVM 桩、0 token）**：143 配置换代（`max-tool-rounds` 2→4 + 占位符登记 + 两个超限用例重钉）→
144 反思分支（读工具③失败一次、占轮、写工具不反思、不绕闸；JVM 用例 + 变异两条）→
145 观测面（`attempt`/`reflection` + counter + trace 小写行 + **R12 回填** + README:402 55/57→56/58）→
146 写守卫补行业门（R11，依据 ADR 0031:10，与主线依据分开记）。
**面**：147 提示词 v1.2.0（第 4 条一句扩写 + 版本三件 + 四处钉子）；148 part10 集与 `eval_autonomy.py`
（30 条 15-10-5、单独成列、不进 `GOLD_CASE_FILES`、0 token 自检折进 CI 仍九步）；
149 dev 活体跑一次（预算 260000→600000 落地 + 读数登记 EVIDENCE、归因 v1.2.0，实测单跑 ≈29 万）；
150 round35 收口（叙事换代按既有流程 + R11/R12 挪「已关闭」）。
完整表（含每票 Verify / Blocked by / 依据）在 spec v2 §4，ADR 0069 的 Consequences 有一份镜像。

### 三、Verify（本票实跑）

```powershell
git diff --check && git status --short
python .scratch/shoppilot-mvp/round3-closeout-audit.py
```

- `git diff --check`：干净（无空白错误）。
- 审计：**提交前实跑 `PASS 73 / FAIL 13 / SKIP 10`，13 条与打开本票前的基线逐条相同**
  （A1 未推送 + A2 未提交；其余 11 条是先前轮次的既有红——B7 既有窗口红（禁面命中集与基线逐项相同，
  **`docs/adr/0069` 是新 ADR、B7 放行**）、G6 常数 536 ≠ 旧矩阵日志 448、E2/E6b/E6c/F1c/H5b/H8/H8b/H10 文档漂移；
  **本票不新增任何一条红**）。提交后 `A2` 转绿（实读 `PASS 74 / FAIL 12 / SKIP 10`），`A1` 待推送；
  **推送后（2026-10-19）复跑：`PASS 75 / FAIL 11 / SKIP 10`**——`A1` 转绿，11 条红与基线逐条相同（零新增红）。

### 四、给下一步的三件事

1. **所有者点头**是唯一剩余闸门（ADR 0069 Status=proposed）：批 → `/to-tickets` 建 143-150（票面骨架照 spec v2 §4）；
   不批 → ADR 0069 与 spec v2 留作决策记录，frontier 不亮（map 135 的 destination 已达成，这件事不受影响）。
2. **点头时可一并做的修正**：137-140 的建议值（闸清单第 2/3 条形式、行业门进闸清单、反思按工具合计 1 次、
   `FallbackReason` 不 +1、part10 编号与 600k 预算——只批单跑口径则退 400000）。
3. **实现轮的三个钉子不许漂**：gold 180 / 阈值 / 分母 / `judge()` 一字不动；`plan[]` 失败枪不进（`eval_task.py` 不变量）；
   评测读数只按 v1.2.0 归因（与 v1.1.0 不可混比）。

## Handoff notes

**关键决策**：见 Resolution 一、二节。一句话：**三件产物落成**——ADR 0069（开轮依据 = 所有者政策覆盖，
Status = **proposed**，执行闸门 = 所有者点头）+ spec v2（六决策重锚 + 机制契约 + 拆票表；v1 原文保留加指针）+
**拆票表 = round35 / 实现票 143-150**（机制 143-146 → 提示词 147 → 评测 148 → dev 活体 149 → 收口 150）；
既有 ADR 一字未动（指针落 ADR 0069 / spec v2 / tracker 三处）；R11 → 票 146、R12 → 票 145 各自带依据；
map 135 收口（destination 达成，不再 graduation）。

**验证落点**：决策票，文档级验证 = `git diff --check` + `git status --short` + 收口审计实跑（读数见 Resolution 三节，
**不新增任何一条红**）；机器落点全部留给实现轮（每票的 Verify 在 spec v2 §4）；gold / 判据面零触碰。

**三个现场追问**：

1. **为什么 ADR 0069 是 `proposed` 而不是 `Accepted`？**——map 的约定是「开轮本身仍由所有者政策覆盖决定，
   不预设所有者一定批」；本 ADR 记录的是**开轮依据**（覆盖依据 + 六决策重锚 + 拆票表），不代替那次裁定。
   批了之后它就是本轮的依据文档，一个字不用改。
2. **为什么没有直接建 143-150 的票面？**——① 目的地写的是「到可以直接走 `/to-tickets` 拆票为止」，建票是下一步；
   ② 所有者点头之前建票会让 frontier 亮起可领取的假信号（本仓最贵的错误是「看起来已经批了」）。
3. **为什么 141 之前写的「ADR 0008/0036 加换代指针」最后没有动那两个文件？**——B7 的内容级禁面把
   「既有 ADR 出现在本轮窗口」判红，审计里明写指针改落 tracker / 本轮 spec（round23 票 69 实测先例）；
   因此「原文不动」在文件级执行，指针在本 ADR、spec v2、tracker 三处齐备，语义（覆盖关系可追溯）不减。
