# 票 218：/actuator/prometheus 500 修复（指标重名）

Round: 50 后续（缺陷修复，非新功能；开轮依据 = 所有者 2026-11-12 指令「立票修 /actuator/prometheus 500（shoppilot_handoff_decision_seconds_max 重名，round47 遗留），走验收链路」）
ADR: 0082（仅追加换代指针，正文不动）　Status: ready-for-agent（2026-11-12）　Blocked by: 无
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

（待实现方填写：关键决策 / 验证落点 / 三个现场追问。）
