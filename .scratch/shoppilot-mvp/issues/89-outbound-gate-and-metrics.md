# 89 出站的门禁与三个计数

**Status:** ready-for-agent

## What to build

让「回流了没有」**能被机器读到**，而不是靠人点。

- **三个计数**（命名沿用仓内既有分法）：
  - `shoppilot_outbound_published_total`（工单服务侧发出，带 `channel` 标签）
  - `shoppilot_outbound_delivered_total`（网关侧 2xx，带 `channel` 标签）
  - `shoppilot_outbound_failed_total`（重试耗尽，带 `channel` 标签）
  - 外加一个「**因为没有目标而没发**」的计数——否则「发出 0 条」与「都成功了」在读数上长得一样（票 87 已埋）。
- **投递门禁** `scripts/verify-outbound.mjs`（或并进既有的 `verify-channel.ps1`）：
  起本地回声端点 → 走一遍「落单 → 坐席领取 → 处理完成」→ 断言**回声端点真的收到过**。
- **接进验收矩阵**：`run-acceptance.ps1` add-only 一步（不动既有 26 步的判据）。

## Blocked by

[88](88-outbound-consumer-and-delivery.md)。

## 口径

- **加标签不加名**：本轮指标名若新增，`docs/EVIDENCE.md` 与 README 的指标计数**同一次换代**，
  不留「名字加了但计数没换」的中间态（round22 票 66 的教训）。
- **门禁自己先造工单**，不依赖前一步的残留（round23 票 73 门禁那条「不依赖残留」的同款）。
- **队列/事件为空判红**：这是本仓反复吃过的假绿。

## 验收

- `run-acceptance.ps1` 的新步在**清场日**实跑一次；在此之前**按未达成登记**，不得声称已验证；
- 三个计数在 `/actuator/prometheus` 上可见且带 `channel` 标签；
- **变异对照**：把 `delivered` 计数改成在「发出去了」时就 +1 → 门禁必须红
  （那条断言就是钉「送达 ≠ 发出」）。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
node --check scripts/verify-outbound.mjs
```

## Handoff notes

（收口时补）