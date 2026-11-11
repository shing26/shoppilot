# 票 218：/actuator/prometheus 500 修复（指标重名）

Round: 50 后续（缺陷修复，非新功能；开轮依据 = 所有者 2026-11-12 指令「立票修 /actuator/prometheus 500（shoppilot_handoff_decision_seconds_max 重名，round47 遗留），走验收链路」）
ADR: 0082（仅追加换代指针，正文不动）　Status: implemented（2026-11-12，分支 `fix/218-prometheus-duplicate`；独立 Verifier **PASS**，待所有者书面签收合并）　Blocked by: 无
契约：[plans/218-prometheus-duplicate-metric.md](../../plans/218-prometheus-duplicate-metric.md)（2026-11-12 冻结）

## What to build

gateway 的 `GET /actuator/prometheus` 返回 **500**：`shoppilot_handoff_decision_seconds_max`
重名——`Timer.builder("shoppilot_handoff_decision_seconds")` 由 Micrometer 自动导出 `_seconds_max`，
round47 票 198 又显式注册了同名 Gauge（ADR 0082）。后果是整个抓取端点不可用，单指标端点却正常，
所以一直没被发现（仓库里没有任何对注册表做整体导出的用例）。

1. **改名**：Gauge → `shoppilot_handoff_decision_max_seconds`。这是改名不是新增，唯一指标名计数不变。
2. **回归测试**：用真实 `PrometheusMeterRegistry` 走 `scrape()`（或 Spring 上下文打
   `/actuator/prometheus`），断言不抛异常且两条指标都在；并加一条「全注册表导出名唯一」的通用断言。
   必须给出修复前红、修复后绿的证据。
3. **连带修复**：`QualitySidecarService` 用 `name + "_max"` 拼名读 Gauge，改名后会静默回退到
   会衰减的 `Timer.max()`——必须改成显式常量，保持「优先 Gauge、缺失回退」的语义，
   `/ops/quality/report` 的 `maxSeconds` 仍是累计最大。
4. **文档**：README / CODE_MAP / TUNING_PLAN（若列名）同步；ADR 0082 追加换代指针（正文不动）。
5. **活体**：起栈后 `/actuator/prometheus` 返回 200 且正文含新名。

## 边界

- 不许通过删 Gauge、关端点、关 Timer `_max` 导出等方式「让 500 消失」——那是掩盖不是修复。
- 不改其它指标名/标签/分母；不改既有 gold、判据、`judge()`；不改 ADR 正文。

## Verify

```bash
bash ./mvnw -B -ntp verify
python -I scripts/verify_doc_truth.py
git diff --check && git status --short
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8082/actuator/prometheus
curl -s http://127.0.0.1:8082/actuator/prometheus | grep -c "shoppilot_handoff_decision"
```

## 验收项

- `/actuator/prometheus` 200、正文含 `shoppilot_handoff_decision_seconds_count` 与
  `shoppilot_handoff_decision_max_seconds`；不再有 duplicate 异常。
- 回归用例在修复前红、修复后绿（红→绿证据入票面）。
- 全注册表导出名唯一性断言在册。
- `QualitySidecarService` 仍读累计最大（有 Gauge 时走 Gauge），现有用例绿。
- 803/134 不降、新增 ≥2、指标名计数不变（76/78）。
- 证据绑定 HEAD SHA + `git status --short`。

## Handoff notes

**Status**：implemented（2026-11-12，分支 `fix/218-prometheus-duplicate`，基于 main `b6982e6`；待独立 Verifier）

### 关键决策

- Gauge 改名为 `shoppilot_handoff_decision_max_seconds`；Timer 名与桶不变。唯一指标名计数 **76 / 78 不变**（改名不是新增）。
- 注册逻辑抽成包级静态 `TurnServices.registerHandoffDecisionMeters(MeterRegistry, AtomicLong)`，生产构造器与回归用例走**同一路径**——用例自己注册 meter 只能证明测试环境，证明不了生产代码已修。
- 回归用例直接操作真实 `PrometheusMeterRegistry` + `scrape()`，未起 Spring 上下文：gateway 全上下文需要 Redis 等活体依赖，而 `scrape()` 就是 `/actuator/prometheus` 的同一条导出路径；Spring 上下文方案成本高且不增加对缺陷的覆盖。
- 通用断言遍历 `registry.getMeters()`，按 Prometheus 导出规则展开（Timer / distribution summary → `_count/_sum/_max/_bucket`，其余取命名约定结果）后查重。
- `QualitySidecarService.HANDOFF_DECISION_MAX_GAUGE` 改为 public 显式常量；回归用例断言它与 `TurnServices.HANDOFF_DECISION_MAX_GAUGE_NAME` 相等，钉住两处不漂移。`timer()` 的唯一调用点仍是 handoff timer，语义保持「优先 Gauge、缺失回退 `Timer.max()`」。
- 活体用 perf 档本机 JVM（MockLLM，0 付费 token）：原生 Redis 3.2.100:16379 + biz-mock:8091 + gateway:8082；ES/Qdrant 未起，网关按降级模式启动、readiness 仍 200。

### 验证落点

- **红（修复前源码）**：`bash ./mvnw -B -ntp -pl shoppilot-gateway -am -Dtest=PrometheusScrapeRegressionTest -Dsurefire.failIfNoSpecifiedTests=false test` → `Tests run: 3, Failures: 2`
  - `scrapesWholeRegistryWithHandoffDecisionMeters`：`java.lang.IllegalStateException: shoppilot_handoff_decision_seconds_max: duplicate metric name.`
  - `everyPrometheusExportNameIsUnique`：`Expecting empty but was: ["shoppilot_handoff_decision_seconds_max <- MeterId{name='shoppilot_handoff_decision_seconds_max', tags=[]} / MeterId{name='shoppilot_handoff_decision_seconds', tags=[]}"]`
- **绿（改名后，同一命令 + QualitySidecarServiceTest）**：`Tests run: 8, Failures: 0, Errors: 0`（回归 3 + sidecar 5）。
- **全量**：`bash ./mvnw -B -ntp verify` → BUILD SUCCESS，**806 tests / 135 suites**（基线 803/134，+3 tests / +1 suite）；分模块 tool-api 12/4、biz-mock 92/18、gateway 652/98、ticket 50/15。
- `python -I scripts/verify_doc_truth.py` → **OK**（806/135）。
- `git diff --check` → 退出 0；`git status --short` 见证据文件。
- **活体**：`curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8082/actuator/prometheus` = **200**；`grep -c shoppilot_handoff_decision` = **85**；正文含 `shoppilot_handoff_decision_seconds_count` 与 `shoppilot_handoff_decision_max_seconds`，无 duplicate 异常。证据：[`eval/results/prometheus-scrape-20261112-t218.txt`](../../../eval/results/prometheus-scrape-20261112-t218.txt)，绑定实现 HEAD `6f81864`。

### 三个现场追问

1. **抓取正文里仍有 `shoppilot_handoff_decision_seconds_max`**：那是 Timer 自动导出的**时间窗**最大值，不是累计最大 Gauge。看板/告警应改用新 Gauge `shoppilot_handoff_decision_max_seconds`；旧名保留是 Micrometer 的自动行为，关闭它属于掩盖（契约禁止）。
2. **通用唯一性断言只展开本仓库实际使用的 meter 类型**（COUNTER / GAUGE / TIMER / DISTRIBUTION_SUMMARY）；`LongTaskTimer` 等未展开。未来引入新类型时要扩 `prometheusExportNames`，否则可能漏掉同类重名。
3. **README 的 Allowed paths 写「仅该指标名文本」，但 `verify_doc_truth.py` 强制 badge / 复现段 / CI 段与 surefire 实数一致**。新增 3 条用例后按 Verification commands 机械更新 803→806、134→135、gateway 649→652（TUNING_PLAN 两处同改），未改其他正文。若裁决方认为越界，可回退这些数字，但 doc-truth 会红。

## 独立验证（Verifier，2026-11-12）

- 裁决 **PASS**，绑定 HEAD `1861158`；接手与收尾 `git status --short` 均为空。
- 亲跑：`mvnw verify` BUILD SUCCESS **806/135**（自算 surefire XML 一致）；`verify_doc_truth.py` OK；
  `gen_status.py --check` OK；gold/`scripts/**`/提示词/tool-api 零字节；ADR 0082 为纯追加 5 行；
  唯一指标名亲算 **76/78 不变**；gateway 行覆盖 70.31%（≥ 基线 70.29%）。
- **红→绿由其独立复现**（不采信实现方日志）：在仓库外临时目录用源码同款 Timer 构建链，旧名
  `SCRAPE_THREW IllegalStateException: shoppilot_handoff_decision_seconds_max: duplicate metric name.`，
  新名 `SCRAPE_OK lines=85`。
- 活体由其亲起亲测（perf 档 MockLLM，0 付费 token）：`/actuator/prometheus` 连续两次 **200**，
  109 个 TYPE 声明中**同名异型冲突 = NONE**；Timer 自动导出的 `_seconds_max` 仍在（未掩盖）。
- 判定接受实现方对 README 数字的机械同步：`verify_doc_truth.py:63-129` 从 surefire 现算并强制校 badge /
  复现段 / CI 段，属门禁要求而非改判据。
- **两条非阻断留痕**：① `everyPrometheusExportNameIsUnique` 的注册表范围是**用例自建注册表**，
  不是全应用注册表——AC3 字面达成，但「同类问题在别的模块复发」不被它覆盖（防复发强度弱于字面读感）；
  ② 证据文件原为单行 CRLF，`git diff --check main...HEAD` 报一处 trailing whitespace——
  已在合并前提交 `d59ad85` 统一为 LF，范围 diff-check 转干净。

**合并签收（验收链路硬规则 4）**

- 独立 Verifier 总裁决 **PASS**（2026-11-12，绑定 HEAD `1861158`）：亲跑 806/135、活体
  `/actuator/prometheus` 200、在仓库外独立复现旧名红（异常逐字一致）与新名绿、Forbidden 零违例；
  AC3 的注册表范围局限与证据行尾问题已在上节留痕（后者合并前已修）。
- 所有者书面签收：2026-11-12「书面签收票 218，fast-forward 合并 fix/218-prometheus-duplicate 到
  main，合并后重跑 verify 与文档真值」（对话内书面）。
- 首轮即通过，无返工；与票 217 的差别是这张票的回归用例本来就能复现缺陷（红→绿是设计的一部分）。
