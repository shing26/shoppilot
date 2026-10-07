# 97 一个平台的适配器与录放门

**Status:** ready-for-agent

## What to build

A 段的承重部分：接**一个**平台的入站适配器，并给它一道 0 token 的录放门。

- **选平台的标准是「入站事件 JSON 能否录制成夹具 + 归一结果能否断言」**，不是哪个平台最流行
  （ADR 0060 决策 3）。各家的入站事件都是公开的结构化格式，所以 A 段选谁成本差别很小；
  **真正的选择发生在票 98**。
  **→ 2026-10-07 已裁定：飞书**（所有者指示，票 98 考察 + ADR 0065）——本票录制对象为
  **飞书单聊文本事件 JSON**，夹具名 `eval/im-events/feishu-<case>.json`。
- **录制夹具** `eval/im-events/<platform>-<case>.json`：append-only，录的是**平台原样事件**，
  不是我们归一后的样子（录归一后的就变成自己给自己判分）。
- **录放门**（同 ADR 0049 的检索录放门）：录的事件喂进适配器，断言
  归一结果、派生 `clientToken`、派生 `conversationId` 三样。**0 token、干净 runner 可复现。**

## Blocked by

[96](96-inbound-contract-session-and-client-token.md)。

## 口径

- **不引该平台的官方 SDK**：A 段处理的是事件 JSON 的形状，不是 SDK 的调用。
  引 SDK 会在 B 段真正调 API 时才有用，而那时才需要它的凭据与网络。
- **夹具 append-only**：与 `eval/retrieval-fixture-*.json` 同一处置。
- **门禁自己断两条跨平台形态**（spec §3）：会话不串、幂等不靠参数猜。

## 验收

- 录放门 0 token、可进 CI；CI 仍是**九步**（本门禁可作为既有 0 token 步骤的一部分或新加一步，
  但**不增加需要外部服务的步骤**——那是 round15 起那条纪律）；
- 用例覆盖 spec §3 那两条；
- **变异对照**：把 `conversationId` 派生改成只用平台名 → 「会话不串」红；
  把 `clientToken` 置空 → 「幂等不靠参数猜」红。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
node --check scripts/verify-im-replay.mjs   # 若门禁写成脚本
```

## Handoff notes

（收口时补）