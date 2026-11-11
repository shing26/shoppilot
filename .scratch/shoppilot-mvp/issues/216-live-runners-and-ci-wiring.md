# 票 216：活体跑器、CI 0-token 接线与证据落档

Round: 50（spec：[round50-spec-agent-eval-expansion.md](../round50-spec-agent-eval-expansion.md)，所有者 2026-11-11 开轮批准）
ADR: 无（评测资产）　Status: implemented（2026-11-11）　Blocked by: 票 214、票 215

## What to build

让三份用例集**能跑、能进 CI、读数能落档**：

1. `scripts/run_agent_eval.py`：回归/边界活体跑器。默认零请求（逐条 `SKIP_NO_LLM`）；付费推理必须
   显式 `--include-llm --llm-budget N`。输出 `eval/results/agent-eval-<run>.csv/.meta.json`，
   meta 分开记 `llm_calls` / `inference_tokens` / `judge_tokens`。
2. `scripts/run_security_eval.py`：安全剧本跑器。串行执行步骤，解析 auth（`none` / `forged` /
   `buyer|agent|admin:Txxx` / `minted:Txxx:Cyyy`）、`{{RUN_ID}}` 替换、流中断、`save` 变量、
   `env_skip_if`、正对照失败 → `SKIP_ENV`、429/预算熔断 → `SKIP_ENV`。默认只跑非 llm 步骤。
   输出 `eval/results/security-eval-<run>.csv/.jsonl/.meta.json`；有 FAIL 时退出码 1。
3. `.github/workflows/ci-subset.yml`：在既有 `Retrieval fusion gate (0-token)` 步骤内**追加**两个
   校验器命令，不新增 job、不新增步骤。
4. 活体证据落档：`eval/results/` 下的非 llm 冒烟、全量付费跑与补跑，stdout 用 UTF-8/LF 入库。

## 契约

- Allowed paths：`scripts/run_agent_eval.py`、`scripts/run_security_eval.py`、
  `.github/workflows/ci-subset.yml`（只追加 0-token 校验，不新增 job）、`eval/results/`（新产物）。
  合并登记时另加 tracker/spec（本票登记动作）。
- Acceptance：CI 现有 0-token 步骤内追加两个校验器（不增加步骤数）；活体安全跑的 PASS/FAIL/SKIP
  原文与 meta 落 `eval/results/`；有 FAIL 时退出码 1。
- Verify：`python -I scripts/run_agent_eval.py`（默认 0 请求）；
  `python -I scripts/run_security_eval.py`（默认只跑非 llm 步骤）。
- Forbidden：把 dev 档付费推理设成默认、把预算熔断去掉、把 `SKIP_ENV` 改写成 PASS、
  把推理 token 记成判分 token。

## 验收项

- 两个跑器默认 0 请求 / 0 token；付费路径必须显式开关 + 预算。
- CI 步骤数不变，两个校验器在其中执行。
- 活体证据三件落档（`smoke-nollm`、`live-r50-final2`、`live-r50e`），meta 含成本分账。
- 有 FAIL 时退出码 1（`SEC-INJ-04` 活体红即由该路径暴露）。

## Handoff notes

（2026-11-11 实现，随 round50 分支 `claude/inspiring-bose-af1eee` 交付。）

**关键决策**

- **默认零成本**：跑器默认不发任何聊天请求，付费推理必须两个开关同时给。理由是本机 8082 是 dev 档
  （qwen-plus），一次全量就是真金白银；「不小心跑了一次」不能靠自觉避免。
- **推理 token ≠ 判分 token**：meta 把 `judge_tokens` 固定为 0，`inference_tokens` 只作成本记录，
  绝不参与判定。这防止把「模型花了多少 token」误写成「判分有多贵」。
- **`SKIP_ENV` 是一等状态**：正对照失败、mock-token 回环 200、429/预算熔断、旧种子越过业务窗口，
  全部记 `SKIP_ENV` 并保留原文，不折算成 PASS 也不折算成 FAIL。
- **CI 只接 0-token 部分**：活体跑器需要 8082 + 模型预算，不进 CI；CI 里只做结构 + 变异自检，
  沿用 round48 建立的 0-token 门禁形态（不新增步骤）。

**验证落点**

- `python -I scripts/run_agent_eval.py`：默认逐条 `SKIP_NO_LLM`，零请求。
- `python -I scripts/run_security_eval.py`：默认只跑非 llm 步骤，**12 PASS / 1 SKIP_ENV**，0 token
  （`eval/results/security-eval-smoke-nollm.*`）。
- 活体全量：`eval/results/security-eval-live-r50-final2.*` + `security-live-r50-final2.stdout.txt`
  = 26 PASS / 3 SKIP_ENV / 1 FAIL，判分 0 token，推理 21 次 / 53,750 token；
  补跑 `security-eval-live-r50e.*` = CONV-03 PASS，2 次 / 11,244 token。
- 这两份活体读数是实现会话产出的，**本会话未复跑**（LLM 日预算已熔断且复跑是付费推理）；
  合并前本会话复跑的是两个 0-token 校验器与自检，全绿。
- `bash ./mvnw -B -ntp verify` 本轮未跑：零 Java 改动，JVM 基线保持 round49 收口的 790 tests / 132 suites。

**三个现场追问**

1. CI 现在只校验用例集结构，不校验活体行为；要不要在 nightly（已有 Testcontainers nightly）里加
   一条「0 token 安全冒烟」——只跑 13 条非 llm 步骤，不需要模型预算？
2. 网关每日 LLM 预算熔断返回 HTTP 200 + 转人工话术，跑器目前只能靠 429 识别。要不要给
   `/ops/circuit` 加机器可读的 `budgetExhausted`，让跑器把熔断窗口准确记成 `SKIP_ENV`？
3. `SEC-INJ-04` 是活体真红：本票按「有 FAIL 时退出码 1」如实暴露，不修产品。修复另立产品票
   （走验收链路，因为涉及认证授权面）。

**合并签收（验收链路硬规则 4）**

- 验收形态判定：本轮为**评测资产**（用例集、0-token 校验器、活体跑器、CI 追加校验），
  零产品代码、零运行时依赖、零数据库 schema、零既有判据改动，**不触发** `CLAUDE.md` 的链路清单；
  因此未设独立 Verifier，证据 = 分支 4 提交 + 实现会话活体读数 + 合并前本会话在干净 worktree
  复跑的两个 0-token 校验器与自检（全绿）。
- 所有者书面签收：2026-11-11「批准开轮 round50：把 spec 转正、tracker 登记 round50 与票 214-216，
  然后把评测集 4 个 commit 合并到 main」（对话内书面，含 CI 追加校验这一交付流程变更的批准）。
- 分支 `claude/inspiring-bose-af1eee`（`b8f0eb8` → `8a2cb14` → `808bb8c` → `8854956`）fast-forward
  合并至 main；`git diff --check` 干净、工作树干净。
