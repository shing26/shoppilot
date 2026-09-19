# 35 — Prompt 版本化：外置 v1.0.0.md + meta.json + SSE 与评测报告携带版本

**What to build:** 按 ADR 0037 把 `AgentStateMachine` 内的 `SYSTEM_PROMPT` 常量外置为 `prompts/agent-system/v1.0.0.md`（正文与现行 11 条规则逐字一致），同目录 `meta.json` 记 `{"current": "v1.0.0"}`；新增 `PromptCatalog`（agent 包）在启动期加载并打印版本号，`meta.json` 指向不存在的版本即启动失败（fail-fast，与 ADR 0029 同取向）；SSE `meta` 事件新增 `promptVersion` 字段（sink 构造器注入，接口零改动，旧客户端不读不受影响）；同步响应与评测 meta.json 携带版本号。迁移即不改语义，验证判据 = 迁移前后 `mvnw verify` 全绿 + 11 条规则逐字 diff 为空。

**Blocked by:** None（round17 顺序位：34 之后、36-39 之前；风格引擎 0038 以本票为硬前置）。

**Status:** implemented（2026-09-19；迁移逐字 diff 为空，全量 JVM `3 + 12 + 218 = 233` 绿，量具 40 PASS，CI 见 Handoff）

- [x] `prompts/agent-system/v1.0.0.md` + `meta.json` 入库；`PromptCatalog` 启动加载、缺 current/指向缺失版本/解析失败三种形态全部 fail-fast
- [x] `AgentStateMachine` 删除 `SYSTEM_PROMPT` 常量，改用 `catalog.systemPrompt()`；7 处 `AgentResult` 构造带 `promptVersion`（缓存命中路径如实报告当前服务版本，缓存条目本身不区分 prompt 版本的口径照 ADR 0003 由 kb_epoch 与登记承载）
- [x] `SseEventSink` 构造器注入版本，meta 事件新增 `promptVersion`；`ChatController` 注入 `PromptCatalog` 并传入
- [x] `run_tool_eval.py` 明细行与 meta.json 写入 `promptVersion`（评测报告头，ADR 0037 第 5 条）
- [x] 审计 B7 禁面摘出 `scripts/run_tool_eval.py`（round17 的 judge() 扩 schema 是设计内工作，判据语义防线由票 34 的 CI rescore 门禁承载）
- [x] `PromptCatalogTest`：生产资源加载 v1.0.0、11 条规则结构钉住、三种 fail-fast 形态各自变红
- [x] 迁移逐字 diff 为空：从 HEAD 的 `AgentStateMachine.java` 文本块程序化提取，与资源文件逐字节比对一致
- [x] 全量 `mvnw verify` 绿（`3 + 12 + 218 = 233`）；`verify_eval_judge.py` 40 PASS；rescore 门禁 exit 0

**Verify:**

```powershell
.\mvnw.cmd -B -ntp verify
python scripts\verify_eval_judge.py
python scripts\run_tool_eval.py --rescore eval\results\tool-eval-20260910-080638-dev-budgetfix.csv eval\results\tool-eval-20260910-080732-dev-budgetfix.csv eval\results\tool-eval-20260910-080830-dev-budgetfix.csv eval\results\tool-eval-20260910-080926-dev-budgetfix.csv eval\results\tool-eval-20260910-080942-dev-budgetfix.csv eval\results\tool-eval-20260910-075747-dev.csv --rescore-expected-diff ACT-ORD-09,ACT-ORD-11,ACT-ORD-16,ACT-ORD-17
```

预期：全量三模块 `3 + 12 + 218 = 233` 绿；量具 40 PASS；rescore `tool_diff=4` exit 0（本票不改 judge 语义）。

**验收项**

1. 迁移逐字 diff 为空：资源文件与迁移前常量逐字节一致（程序化比对，非肉眼）。
2. `meta.json` 指向缺失版本、缺 current、JSON 损坏 → 启动失败且异常信息指明文件与原因。
3. SSE meta 事件与同步响应都携带 `promptVersion`；评测 meta.json 头含版本号。
4. 已知口径照登：缓存命中路径报告的是当前服务版本，而命中答案可能是旧版本 prompt 产出——prompt 版本不进缓存键（改键属 ADR 0003 判据域，不在本票），归因边界由本行登记。

## Handoff notes

**关键决策**

- **版本注入走"AgentResult 字段 + sink 构造器"两条路，不改 `EventSink` 接口**：meta 事件在 TRIAGE 期发出、早于结果存在，所以 SSE 侧由 `ChatController` 把版本传给 `SseEventSink` 构造器；同步响应与评测报告走 `AgentResult` 新字段。两条路都是加字段，旧客户端不读不受影响（ADR 0037 第 4 条原话）。
- **`PromptCatalog` 放 agent 包**：prompt 是状态机的资产（CODE_MAP 的 agent 行），风格引擎（0038）将直接以它的 `systemPrompt()` 为基座拼接注入段；config 包只管 fail-fast 的风格先例，不放具体资产。
- **缓存命中路径的报告口径**：命中答案由历史 prompt 版本产出，但缓存键不含 prompt 版本（改键=动 ADR 0003 判据域，本票不碰），命中时如实报告"当前服务版本"并在票面登记该边界——宁可口径显式，不给一个假的"逐条归因"。

**验证落点**

- 逐字 diff：`git show 9b49d25:...AgentStateMachine.java` 的文本块内容程序化提取后与资源文件 `sha256` 一致。
- 全量 `mvnw verify`：`3 + 12 + 218 = 233` 绿；`PromptCatalogTest` 5 项（含三种 fail-fast）。
- 量具 40 PASS、rescore `tool_diff=4` exit 0（本票不改判据）；CI run 见 Handoff 末行。

**现场追问**

1. "为什么版本文件只增不删、meta 指向缺失就炸启动？" —— prompt 是行为语义的载体：静默回退到旧版等于让"改一句 prompt"重新变成不可追踪（ADR 0037 立票的理由）。启动期炸掉把"版本管理纪律"变成机器可执行的门，与票 21/28 的 fail-fast 同取向。
2. "改提示词的 PR 现在必须动什么？" —— 新建版本文件 + 改 `meta.json` 的 current + （若走门禁比对）重新基线化评测。"顺手改一句 prompt"从不可追踪变成三处留痕，git 历史即版本历史。
3. "缓存命中为什么不报告"命中时的历史版本"？" —— 缓存条目没有记录产出时的版本（本票未给 CacheEntry 加字段——那是缓存判据域的改动）。登记的口径是"命中路径报告当前服务版本"，其归因边界写进了验收项 4；未来若要逐条归因，触发条件是"prompt 变更后需要区分新旧命中"成为真实需求。
