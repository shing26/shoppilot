# plans/218 — /actuator/prometheus 500 修复（指标重名）

冻结时间：2026-11-12　冻结者：本会话（裁决方）
实现方：独立 worktree subagent（分支 `fix/218-prometheus-duplicate`；生成与裁决分离，验收链路硬规则 1）
契约冻结时 HEAD：`2ab8c6b`

## 缺陷复述（冻结，不许改写）

- 现场：gateway 运行中 `GET /actuator/prometheus` 返回 **HTTP 500**，异常
  `IllegalStateException: shoppilot_handoff_decision_seconds_max: duplicate metric name`
  （票 217 的独立 Verifier 在 2026-11-11 实测；`main` 上同样存在）。
- 根因：`agent/TurnServices` 注册了 `Timer.builder("shoppilot_handoff_decision_seconds")`，
  Micrometer 的 Prometheus 注册表会为它导出 `_seconds_count` / `_seconds_sum` / `_seconds_bucket`
  **以及 `_seconds_max`**；同一构造器又显式注册了
  `Gauge.builder("shoppilot_handoff_decision_seconds_max", …)`（round47 票 198 / ADR 0082）。
  两者导出名相同、类型不同 → 抓取时整个注册表组装失败。
- 影响：单指标端点 `/actuator/metrics/{name}` 正常，所以此前无人发现；但**整个 `/actuator/prometheus`
  不可用**——任何依赖抓取的告警、看板、Prometheus 服务发现都拿不到这个进程的任何指标。
- 为什么测试没抓到：`QualitySidecarServiceTest` 只按名字查 meter，不做 scrape；仓库里没有任何
  对 Prometheus 注册表做整体导出的用例。

## Allowed paths

- `shoppilot-gateway/src/main/java/com/shoppilot/gateway/agent/TurnServices.java`
- `shoppilot-gateway/src/main/java/com/shoppilot/gateway/quality/QualitySidecarService.java`
- `shoppilot-gateway/src/test/java/**`
- `README.md`（仅该指标名文本）、`docs/CODE_MAP.md`（同一处指标名）、`docs/TUNING_PLAN.md`（若列名）、
  `docs/STATUS.md`（仅 `gen_status.py` 重生成）、`docs/EVIDENCE.md`
- `docs/adr/0082-round47-ux-p2-fixes.md`（**仅允许追加**一条换代指针 blockquote，正文一字不动）
- `.scratch/shoppilot-mvp/issues/218-*.md`、`.scratch/shoppilot-mvp/README.md`（票 218 行）
- `eval/results/**` 或 `docs/`（活体抓取证据）

## Acceptance criteria

1. **消除重名，不掩盖**：Gauge 改名为 **`shoppilot_handoff_decision_max_seconds`**（去掉与 Timer
   自动导出后缀相同的 `_seconds_max`）。**不许**通过关闭 prometheus 端点、删除 Gauge、或关闭
   Timer 的 `_max` 导出来绕过——那些都只是让 500 消失而让读数失真。
2. **回归测试必须能复现缺陷**：新增 JVM 用例，用**真实的 `PrometheusMeterRegistry`**（不是 mock）
   注册与生产同源的 meter 后调用 `scrape()`，断言不抛异常、且正文同时包含
   `shoppilot_handoff_decision_seconds_count` 与 `shoppilot_handoff_decision_max_seconds`。
   实现方须贴出「修复前该用例红、修复后绿」的证据（红→绿，不许只贴绿）。
   若可行，优先用 Spring 上下文 + 打 `/actuator/prometheus` 的集成用例（更贴近现场）；两者择一，
   但必须真的走注册表导出路径。
3. **全注册表无重名**：再加一条通用断言——遍历注册表中所有 meter，按 Prometheus 导出名检查唯一性
   （防的不只是这一个指标，而是同类问题复发）。
4. **质量报表不退化**：`QualitySidecarService` 对累计最大值的读取改为**显式常量**
   （不再用 `name + "_max"` 拼名），保持「优先读 Gauge、缺失回退 `Timer.max()`」的既有语义；
   `/ops/quality/report` 的 `handoff.decisionSeconds.maxSeconds` 仍是进程内累计最大
   （不得退回会随时间窗衰减的 `Timer.max()`——那是票 198 修掉的缺陷）。现有用例保持绿。
5. **不回归**：`bash ./mvnw -B -ntp verify` 全绿，JVM 基线 **803 tests / 134 suites** 不降；
   新增用例 ≥2；gateway 覆盖率不低于现状。
6. **指标口径**：唯一指标名计数**不变**（这是改名不是新增，README 仍为 76 / 78）；
   ADR 0082 追加换代指针说明改名原因与新旧名，正文不动。
7. **活体**：起栈后 `curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8082/actuator/prometheus`
   = **200**，正文含新旧名中的新名、且不再出现 500 异常；证据落档并绑定 HEAD SHA。
8. 证据绑定 HEAD SHA + `git status --short`。

## Verification commands

```bash
bash ./mvnw -B -ntp verify
python -I scripts/verify_doc_truth.py
git diff --check && git status --short
# 活体（按 RUNBOOK 起 dev 档网关后）
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8082/actuator/prometheus
curl -s http://127.0.0.1:8082/actuator/prometheus | grep -c "shoppilot_handoff_decision"
```

## Forbidden

- 删除 Gauge 或退回 `Timer.max()`；关闭 prometheus 端点；关闭 Timer 的 `_max` 导出；
  改任何其它指标名/标签/分母；改既有 gold、判据、`judge()`；改 ADR 0082 正文（只许追加指针）；
  改阈值或判据来让用例变绿。
- 提交 `logs/`、`target/`、`.env` 或密钥。
