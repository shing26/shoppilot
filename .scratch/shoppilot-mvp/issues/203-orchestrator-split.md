# 票 203：编排器拆分（TurnOrchestrator + 四个 handler）

Round: 48（spec：[round48-spec-quality-closure.md](../round48-spec-quality-closure.md)）
ADR: 0083　Status: implemented　Blocked by: 201（已收口，行为安全网）

## What to build

`AgentStateMachine` 已长到 1,768 行，同时承担入口编排、检索、工具循环、答案合成、会话与缓存写回。
本票只做**结构拆分，行为零变化**：

1. **`TurnOrchestrator`**：接管 `run` 的入口编排——INTAKE/情绪门/请求级幂等/待办续办/TRIAGE/Handoff/
   缓存查找与 singleflight/确定性复合任务，并把模型路径串成 `Retrieve → PlanExecute → Compose → Persist`。
2. **四个 handler**，每个只负责一段：
   - `RetrieveHandler`：政策形状判定、检索、空召回出口、`【政策条款】` 用户消息拼装；
   - `PlanExecuteHandler`：工具循环、工具改绑/派生、反思配额、槽位追问、计划步骤；
   - `ComposeHandler`：最终答案（直出/流式）、local 答案保真守卫、空答案出口、引用与上下文组成、
     回放与受理两个确定性出口；
   - `PersistHandler`：会话落库、缓存写回判定与异步提交、负缓存抑制计数。
3. **共享运行态**：`TurnServices`（依赖、指标、`fallback`、`step`、反思配额、`TokenUsage` 等）与
   `SlotSupport`（`extractSlots` / `confirmedSlots` / 指纹 / 对话文本，以及槽位追问、网关派生、续办），
   避免 handler 之间互传十几个参数。
4. **`AgentStateMachine` 保留现有 public `run` 与两个构造签名**，降为组装根/薄门面；Spring 注入面不变。
5. **每类 < 500 行**（`wc -l` 实测写进 Handoff）。

## 边界（写进 Handoff 与 EVIDENCE，不许扩写）

- 不改 10 状态、SSE 事件顺序、trace 文本、指标名与标签、降级枚举、缓存判定、提示词、工具策略。
- 不动 `ToolDispatcher`、`BizMockClient`、检索融合、gold、阈值、`judge()` 与任何分母。
- 不新增 Spring bean、配置项或对外接口；不把 handler 拆成独立服务。
- 允许的测试改动只有一处：异步写回失败日志的监听从 `AgentStateMachine.class` 改到新的日志 owner
  `PersistHandler.class`（断言语义不变）。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify -Dspring-boot.repackage.skip=true
python scripts/verify_llm_replay.py
python scripts/verify_answer_eval.py
python scripts/verify_eval_judge.py
git diff --check
git status --short
```

`scripts/eval_suites.py` 是 `run_tool_eval.py --suite` 的库模块（要活体网关），不能单跑；
本票是行为零变化的结构拆分，活体验收与活体评测不在范围内，由 201 的卡与 202 的答案门充当安全网。

行数核对：

```bash
wc -l shoppilot-gateway/src/main/java/com/shoppilot/gateway/agent/{TurnOrchestrator,RetrieveHandler,PlanExecuteHandler,ComposeHandler,PersistHandler,AgentStateMachine}.java
```

## 验收项

- `AgentStateMachine` ≤ 150 行；`TurnOrchestrator` 与四个 handler 各自 < 500 行。
- JVM 用例数不减少、全绿；录放门与答案级门禁读数不变（6 张卡 / 56/60）。
- 工单、handoff、缓存写回、槽位追问、反思与复合任务的既有用例一条不改判据。
- `docs/CODE_MAP.md` 的编排入口按新 owner 更新；`docs/EVIDENCE.md` 记录拆分后行数与验证。

## Handoff notes

**关键决策**

- **门面保留、依赖注入面不动**：`AgentStateMachine` 仍是 `@Component`，两个构造签名逐字保留（17 参无店铺人格
  重载委托到 18 参），`run` 只转调 `TurnOrchestrator`；`mapLlmFailure` 留静态委托给 `TurnServices`，因为
  `FallbackReasonTest` 钉着它。测试仍可直接 `new AgentStateMachine(...)`，不起 Spring 上下文。
- **handler 全部包级可见、无新 bean**：`TurnOrchestrator` / 四个 handler / `TurnServices` / `SlotSupport` /
  `TurnContext` / `TokenUsage` 都是包内类，由编排器在构造时组装；没有新增 `@Component`、配置项或对外方法。
- **`SlotSupport` 承担两半职责**：静态解析（能正则定死的绝不打模型）+ 实例状态机（追问/重复追问分相/网关
  派生/续办）。续办与派生放在这里而不是编排器，是因为它们与槽位计数、追问话术同源；`TurnOrchestrator`
  只保留入口编排与 `resumePending` 的调度点。
- **反思配额归 `TurnServices`**：`reflectionEligible` / `noteReflection` / `noteReflectionExhausted` 依赖
  配置上限与两个 counter，放在共享运行态里让 `PlanExecuteHandler`、`SlotSupport`（续办）共用同一套判据，
  避免复制一份。
- **异步写回日志的 owner 随提交点搬家**：日志现在由 `PersistHandler` 自己的 logger 打出。`PersistHandler`
  是包级类，`TraceCorrelationAcrossAsyncTest` 在 `web` 包，故按 logger 名
  `com.shoppilot.gateway.agent.PersistHandler` 取 appender——这是本票唯一允许的测试改动，MDC 断言原样未动。
- **一行判定都没改**：SSE 事件顺序、trace 文本、指标名/标签、降级枚举、缓存判定、负缓存抑制、提示词、
  工具策略、幂等与 Handoff 全部逐行搬运；拆分前后没有"顺手修"的改动。

**验证落点**

- `./mvnw.cmd -B -ntp verify -Dspring-boot.repackage.skip=true` → **BUILD SUCCESS**；surefire 汇总四模块
  **12 / 92 / 626 / 50 = 780 tests / 131 suites**，0 失败 0 跳过（与票 201 基线一致，用例一条未删）。
- `python scripts/verify_llm_replay.py` → PASS（6 张卡、judge `tool_ok 5/6` 不变）；0 token。
- `python scripts/verify_answer_eval.py` → PASS（60 条 cassette，政策 36/40、动作 20/20，合计 56/60 不变）。
- `python scripts/verify_eval_judge.py` → 40/40。
- `wc -l` 实测：`AgentStateMachine` 73（≤150）；`TurnOrchestrator` 481、`RetrieveHandler` 148、
  `PlanExecuteHandler` 456、`ComposeHandler` 321、`PersistHandler` 92（各自 <500）；共享类
  `TurnServices` 271、`SlotSupport` 289、`TokenUsage` 41、`TurnContext` 22。
- `git diff --check` 干净；`git status --short` 只含本票 Java 文件、测试监听改动与 tracker/文档。

**三个现场追问**

1. **"handler 真的没改行为吗？"** 看 `git diff` 里 `AgentStateMachine` 的删除行与 handler 的新增行是否逐行对应；
   再用 `verify_llm_replay.py`（钉 6 张真卡的请求/回复）与 `verify_answer_eval.py`（钉 60 条答案与事实判分）
   各跑一次——两门都是 0 token，读数与 201/202 收口时逐字相同。
2. **"以后改一段该去哪个文件？"** 入口/幂等/续办/Handoff/缓存看 `TurnOrchestrator`；检索与空召回看
   `RetrieveHandler`；工具循环/工具改绑/反思/计划步骤看 `PlanExecuteHandler`；最终答案与引用看
   `ComposeHandler`；会话落库与缓存写回看 `PersistHandler`；跨段共享的出口（fallback、step、token 账本、
   反思配额）看 `TurnServices`。
3. **"为什么不是四个独立 Spring bean？"** 票的边界明确"不新增 Spring bean"：拆的是行为边界，不是部署单元。
   handler 之间是构造注入的普通对象，`AgentStateMachine` 仍是唯一被注入的入口，注入面与测试构造方式不变。
  如果将来要把 handler 变 bean，需要另立票并同步改所有直接 `new AgentStateMachine(...)` 的用例。

**未做/边界**：未跑全量活体验收（需 Ollama/容器/浏览器）与活体评测套件；未改任何 gold、阈值、`judge()`、
分母；未清理本机目录；未提交（等所有者指令）。
