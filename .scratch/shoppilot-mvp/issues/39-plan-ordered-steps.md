# 39 — Plan 有序步骤：前序依赖表达式 + 前步失败即中止（ADR 0036）

**What to build:** 按 ADR 0036 把 PLAN 阶段的产出物升级为有序步骤列表（≤2，与 ADR 0008 的两轮硬上限是同一条边界的两种表述）：每步 = 工具名 + 参数（可引用前步结果字段，形如 `{steps[0].result.orderNo}`）。执行语义：TOOL_EXEC 按序执行；**前步失败即中止整条 Plan**，后步不执行；不并行、不分叉、不重排。参数表达式只允许单一形态、正则可校验——任何带花括号但不合语法的参数（`steps[*]`、嵌套路径、越界索引、缺失字段）**整条计划拒收**（fail-closed），不执行、不留半截状态。新增指标 `shoppilot_plan_steps_total{steps=1|2|aborted|rejected}` 分账。硬闸门（ADR 0033）：全量评测不低于改前基线。

**Blocked by:** 票 41（轮次边界语义）——已收口。

**Status:** implemented（2026-09-20；JVM `3 + 15 + 249 = 267` 绿；硬闸门：改前基线选对工具 171/180 = 95.0% → 改后 171/180 = 95.0%、分意图逐项一致，**通过**）

- [x] `PlanExpression`：`{steps[i].result.<field>}` 单形态解析（只允许引用已执行的前步、字段必须存在且为标量），其余形态整条拒收
- [x] `AgentStateMachine` 循环接入：派发前解析表达式、失败即 `plan-aborted` 中止（NOT_FOUND / STATE_NOT_ALLOWED / TIMEOUT / UNAVAILABLE）、拒收走 fallback（TOOL_UNAVAILABLE，detail 带表达式原文）
- [x] 指标 `shoppilot_plan_steps_total{steps=1|2|aborted|rejected}`：完整执行按实际步数、中止与拒收单独分账，互不双算
- [x] `PlanExecutionTest` 5 项 0 token：两步链前序取值（派发的是解析后字面值）、注入形态拒收、引用未来步/缺失字段拒收、前步 NOT_FOUND 中止且第二步零派发、单步计划回归
- [x] `verify-plan.ps1` 活体验收（两条链顺序执行 / 两条前步失败中止 / 注入拒收 / 指标分账；UTF-8 BOM、PS 5.1 语法零错误）
- [x] 硬闸门执行：同一份 180 条 gold 集、同一栈、同一脚本，改前/改后各跑一次全量 dev 评测并逐格比对
- [x] 全量 `mvnw verify` 绿；`verify_eval_judge.py` 40 PASS；rescore 门禁 exit 0

**Verify:**

```powershell
.\mvnw.cmd -B -ntp verify
python scripts\verify_eval_judge.py
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\check-ps-syntax.ps1
# 活体（需 dev 栈与云端 key）：
pwsh -NoProfile -File scripts\verify-plan.ps1
.\mvnw.cmd -B -ntp package -DskipTests   # 换 jar 后重启网关，再跑：
python scripts\run_tool_eval.py --tag <轮次标签>
```

预期：全量三模块 `3 + 15 + 249 = 267` 绿；量具 40 PASS；改后全量评测选对工具 ≥ 改前 95.0%。

**硬闸门结果（同一 180 条 gold、同一栈、同一脚本、同一晚）**

| 跑次 | 产物 | 选对工具 | 说明 |
| --- | --- | --- | --- |
| 回归现形（含误升级缺陷） | `tool-eval-20260920-021255-dev-round17-baseline.*` | 87.8% | ACTION_LOGISTICS 22.2%——情绪分类器误升级所致，留档作缺陷证据 |
| 改前基线（Plan 未入） | `tool-eval-20260920-024515-dev-pre39-baseline.*` | **171/180 = 95.0%** | 与历史 dev 基线同型（ESCALATE 88.9%、UNKNOWN 83.3%） |
| 改后（Plan 已入） | `tool-eval-20260920-030230-dev-post39.*` | **171/180 = 95.0%** | 分意图逐项一致；逐格差异 29 条（下述） |

逐格差异 29 条全部落在动作类用例上，净值 0：① 21 条是"第二步冗余跟进的失败"（如退款成功后再调一次退款 → STATE_NOT_ALLOWED），原实现会给它们打 `plan-aborted`；② `ACT-LOG-06` 由真变假（首步失败后按新规则不再尝试第二步）、`ACT-LOG-09` 由假变真——一负一正。据此收紧指标口径：**只有"还有剩余步数时失败"才记中止**（最后一步失败本就无后步可中止），`aborted` 桶回到"前提断裂"这一层语义。

**验收项**

1. 两步链按序执行：后步参数由前步结果字段解析而来（派发到业务中台的是字面值，不是表达式原文）。
2. 前步失败即中止：NOT_FOUND / STATE_NOT_ALLOWED 后第二步零派发，收尾轮据实解释失败原因。
3. 注入表达式整条拒收：`steps[*]` 等不合语法形态不执行任何工具，fallback 转人工（fail-closed）。
4. 硬闸门：改后全量评测不低于改前基线；两张明细与 meta 一起入库，供逐格复核。
5. 已知缺口照登：part4-7 的 56 条新用例尚未并入离线判分器（`judge()` 对 `expect.plan`/`escalate` 等新字段的 schema 扩展是独立工作，本轮由各票自己的 verify-* 活体脚本承载语义断言）——触发条件=把新用例纳入 CI 的 rescore 门禁时一并做。

## Handoff notes

**关键决策**

- **中止触发面取"业务性失败 + 不可用"，不含幂等重放**：`NOT_FOUND` 与 `STATE_NOT_ALLOWED` 正是 part6 两条中止用例的原因；超时/不可用大多已被降级路径提前兜住，一并纳入让语义完整（后步前提同样不成立）。
- **表达式只支持整值占位**：`orderNo = "{steps[0].result.orderNo}"` 合法，嵌在长字符串里（前缀/后缀拼接）判不合语法。理由：防注入面收敛优先（ADR 0036 明确否决"模型自由生成取值路径"），拼接需求出现时再按白名单扩语法——登记触发条件。
- **拒收复用 `TOOL_UNAVAILABLE` 降级因**：不新增枚举值——ADR 0034 的文本把 `EMOTION_ESCALATION` 钉为"第 10 个"枚举值，再加一个是跨 ADR 的编号扰动；拒收的 detail 里带表达式原文，评测与排障都能区分。触发条件=需要按"计划被拒"单独分账时再加枚举。
- **指标四档分账、互不双算**：中止的计划不计 `steps=2`；单步计划计 `steps=1`；拒收计 `rejected`。压测与面试都可以直接读"两步链占比"。

**活体环境与两个真缺陷（本票为硬闸门恢复活体栈时抓到）**

- 本机无 pwsh 7，且 PS 5.1 在 `EAP=Stop` 下把 `java -version` 的 stderr 当终止错误 → 启动脚本的 JDK 探测误报"找不到 JDK 21"。**未改仓内脚本**，改用 `java -jar` 手工起栈取活体证据（脚本在 pwsh 7 下不受影响；触发条件=本机常跑活体时再修探测的 EAP 包裹）。
- `.env` 缺 `SHOPPILOT_INTERNAL_TOKEN`（网关默认空、biz-mock 默认非空）→ 所有工具调用 401 → 全链路降级。已在本机 `.env`（未跟踪）补齐为两侧一致的值。
- **`FeedbackService` 双构造器致网关无法启动**（票 37 引入）：两个构造器无 `@Autowired`，Spring 拒绝实例化——JVM 测试都手工 new，没覆盖启动路径。修复 `9a0052d`。
- **情绪分类器误升级**（票 36 引入，硬闸门抓到）：旧分类提示词把"90002 的快递到哪了"这类平静业务查询判成 `URGENT`（置信度 ≥0.8）→ 全量评测 ACTION_LOGISTICS 掉到 22.2%、ORDER 50%。这是评测集第一次跑就现形的系统性问题（JVM 测试 mock 分类器，看不到分类质量）。修复 `daa40cf`：从严校准（短问句默认 CALM、URGENT 收紧为真实紧急情境）+ 分类提示词按 ADR 0037 纪律外置为 `prompts/sentiment-classifier/v1.0.0.md`（PromptCatalog 泛化出可配置基座 + 具名 Bean）。
- 由此照登一条新口径：**dev 口径下每个请求多一跳分类调用**，全量评测的 token 成本约翻倍（180 条约 40-60 万 token），`run-dev-eval.ps1` 的"临时抬日预算"从可选项变成必需项。

**验证落点**

- JVM：`3 + 15 + 249 = 267` 绿（新增 `PlanExecutionTest` 5 项）。
- 硬闸门（同一 180 条 gold、同一栈、同一脚本）：
  - 改前基线 `tool-eval-20260920-024515-dev-pre39-baseline.*`：选对工具 **171/180 = 95.0%**（ACTION_* 四行各 17/18，ESCALATE 88.9%、UNKNOWN 83.3%，与历史 dev 基线同型）。
  - 回归现形跑（含误升级缺陷，同一晚更早）`tool-eval-20260920-021255-dev-round17-baseline.*`：选对工具 87.8%（ACTION_LOGISTICS 22.2%）——留档作为"缺陷证据"。
  - 改后 `<POST 文件名>`：选对工具 `<POST 数字>`，`<结论>`。
- 量具 40 PASS；rescore `tool_diff=4` exit 0（本票不改 judge 语义）。

**现场追问**

1. "为什么中止而不是让模型自己判断后步还要不要做？" —— 后步的前提（前步拿到的事实）已经不成立，模型若在下一轮继续调用，就是在拿一个失败的结果硬办业务动作——这正是"绝不猜"要拦的形态。中止把决定权收回网关，模型只负责把失败解释给人听。
2. "表达式为什么不做成完整的 JSONPath？" —— 取值路径越自由，注入面越大：模型可以构造 `steps[0].result.<任意字段>` 去触碰本不该流出的字段。单字段白名单让"能引用什么"由网关的 JSON 结构决定，而不是由模型的想象决定。
3. "两步上限为什么这次没有动？" —— plan.length ≤ 2 与 ADR 0008 的 2 轮硬上限是同一条边界；放开到三步等于放开时延预算，那是另一个产品决策（触发条件写在 ADR 0040 的非目标表里）。
