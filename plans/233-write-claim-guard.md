# 票 233 契约：动作意图忠实性守卫

- Round: 62
- 类型: 产品行为回归修复（ADR 0008 不猜不骗；ADR 0031 允许的回归修复）
- 开票依据: 所有者 2026-11-27 指令「给动作意图加『未派发写工具不得声称已办理』的收尾检查」
- 证据: 2026-11-26 round61 全量矩阵 `refund` / `console` 红
  （`logs/acceptance-run-20261126-043649.log`，本机）；本地 `qwen2.5:3b`
  对「订单 90001 我要申请退款，商品有质量问题」的 `applyRefund`
  **5 次采样仅 1 次真正派发**，其余 4 次输出「我已为您发起退款申请」这类
  **无 tool 事件的完成声明**（trace 只有 `corrective=applyRefund`，无 `applyRefund=`）。
- Implementer: 独立分支 `codex/233-write-claim-guard`
- Verifier: 独立 subagent；命令由 Verifier 亲跑
- Blocked by: 票 231（验收工装收口）合并，保证基线绿与门禁可判
- 编号说明: 原拟 232，与并发任务 `232-commerce-kernel-promotion` 冲突，改用 233

## 问题

`PlanExecuteHandler.execute()` 对写意图只做**一次**文字纠偏：
`nudged=true` 后模型若仍不调用工具，代码直接 `break`，把模型正文当最终答案。
`unresolvedToolFailure` 只覆盖读工具③类失败（R14-A），**写动作未派发**没有收尾检查。
结果是：买家收到「已为您发起退款申请」，但业务库没有任何退款单——违反 ADR 0008。

## 目标

在写意图（`expectedWrite != null`）且**文字纠偏后仍未派发写工具**
（`!expectedWriteDone`）时，收尾前判一次：

- 模型在**追问缺失参数**（合法）→ 原样返回，不落工单。
- 模型在**声称已办理**（完成声明）→ 不得作为正文返回；走既有降级出口
  （`TOOL_ROUNDS_EXHAUSTED`，用户文案「需要人工为您整体跟进，已把已查到的
  信息交给人工客服」）并落可查工单。
- 模型既没追问、也没有完成声明（例如如实说办不了）→ 原样返回。

**不新增降级枚举、不改降级计数口径**（沿用 `TOOL_ROUNDS_EXHAUSTED`），
只在 trace 增加一条 `write-claim-blocked` 便于取证。若后续要单独分账，
另开票（需同步 README 的枚举/指标计数）。

## Allowed paths

- `plans/233-write-claim-guard.md`
- `.scratch/shoppilot-mvp/issues/233-write-claim-guard.md`
- `.scratch/shoppilot-mvp/README.md`
- `docs/EVIDENCE.md`
- `docs/STATUS.md`（契约修订 1：`gen_status.py` 生成，测试数随新增用例变化）
- `README.md`（契约修订 1：仅同步 badge / 复现段 / CI 段的测试数）
- `docs/TUNING_PLAN.md`（契约修订 1：仅同步测试数）
- `shoppilot-gateway/src/main/java/com/shoppilot/gateway/agent/PlanExecuteHandler.java`
- `shoppilot-gateway/src/test/java/com/shoppilot/gateway/agent/PlanExecutionTest.java`
- 如需纯函数与词表：`shoppilot-gateway/src/main/java/com/shoppilot/gateway/agent/WriteClaimGuard.java`
  与 `shoppilot-gateway/src/test/java/com/shoppilot/gateway/agent/WriteClaimGuardTest.java`

## Acceptance criteria

- **AC1（正例，0 token）**：写意图 + 纠偏后模型仍不调工具 + 正文含完成声明
  （如「已为您发起退款申请」）→ 终态为 `TOOL_ROUNDS_EXHAUSTED` 降级、
  `degraded=true`、生成可查工单；trace 含 `write-claim-blocked`；
  正文不得进入正常答案。
- **AC2（负例：追问）**：同一路径但正文是「请提供订单号 / 需要您确认收货地址」
  → 原样返回，不降级、不落工单。
- **AC3（负例：非写意图）**：政策问句里出现「已为您办理」字样 → 守卫不触发。
- **AC4（负例：真的办成）**：写工具成功派发（含 `PENDING_APPROVAL` 受理）
  → 既有路径逐字不变，守卫不触发。
- **AC5（边界）**：纠偏仍只发一次（`shoppilot_write_nudge_total` +1），
  工具轮上限、反思配额、`TOOL_ROUNDS_EXHAUSTED` 的既有分支不变。
- **AC6（回归）**：`.\mvnw.cmd -B -ntp verify` BUILD SUCCESS；
  gold 180 条、阈值、分母、降级枚举数（10）与降级原因数（9）零改动；
  既有测试断言零修改。
- **AC7（活体，模型方差下仍忠实）**：本地 3B 对退款问句连跑 10 次，
  **每一次**要么 `PENDING_APPROVAL`、要么降级 + 工单；
  出现「无 tool 事件却声称已办理」计 0 次（脚本可复用
  `verify-refund-approval.ps1` 的流式解析，不新增依赖）。
- **AC8（流程）**：独立 Verifier 亲跑 AC1–AC7，P0/P1 清零；所有者书面签收后
  合并 `main`，`ci-subset` 对合并后 HEAD success，再删分支。

## Forbidden

- 不新增/删除降级枚举值，不改 `FallbackReason` 文案，不改 gold / 阈值 /
  分母 / `judge()`。
- 不把「追问缺失参数」误判成完成声明；不以正则命中替代状态机事实
  （`expectedWriteDone` 仍是唯一「办成了」的判据）。
- 不删除、跳过或弱化任何测试；不把活体红写成 PASS。
- 不提交 `.env`、`logs/`、崩溃日志、截图或临时产物。

## 契约修订

1. **2026-11-27（新增 0-token 用例后的生成读数）**：AC1–AC5 需要新增
   `WriteClaimGuardTest` 与 `PlanExecutionTest` 用例，JVM 测试总数会变化。
   允许同步 `docs/STATUS.md`、`README.md` 的 badge / 复现段 / CI 段、
   `docs/TUNING_PLAN.md` 的测试数；**只改计数，不改阈值、分母、gold、
   `judge()` 或任何既有断言**。`docs/STATUS.md` 仍由 `python scripts/gen_status.py`
   生成，不手改。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
python scripts/gen_status.py --check
python scripts/verify_doc_truth.py
python scripts/check_doc_links.py
git diff --check
git status --short
```

活体：

```powershell
pwsh -NoProfile -File scripts/verify-refund-approval.ps1
# 10 次采样：AC7 的零谎报断言
```
