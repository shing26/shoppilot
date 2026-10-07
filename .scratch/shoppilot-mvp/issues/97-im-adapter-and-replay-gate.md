# 97 一个平台的适配器与录放门

**Status:** implemented（2026-10-07）

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

**关键决策**：
- **`Channel.FEISHU` 渠道标签 + `fromPath` 显式排除**：飞书只走长连接收事件（票 113），
  没有 HTTP 入站路径——`Channel.fromPath("feishu")` 返回 null，`/webhook/feishu` 一律 400。
  零暴露红线（ADR 0065 决策 2）从解析层就成立，有测试钉住。
- **`FeishuAdapter` 不读 `ChannelContext` / `TenantContext`**（与 webhook/email 的结构差异）：
  长连接线程没有 HTTP 请求、没有 AuthFilter——渠道标签取自 `channel()`，聊天维度取事件里
  的 `chat_id`（平台侧天然隔离；平台 id 与 customerId 的映射是 R3，明确不做）。
- **范围收紧到单聊文本**（round31 spec §5）：`chat_type != p2p` 或 `message_type != text`
  当场拒（IllegalArgumentException → 400 语义），不静默吞；`content` 是字符串化 JSON，
  二次解析取 `text`，解析失败拒。
- **夹具**：`eval/im-events/` 新建（append-only），5 份按**飞书公开文档 v2.0 事件格式**
  构造的真实形状样本（3 文本 + 1 拒收样本 + 1 webhook 对照事件）；header.token 用明显
  占位（凭据不进仓库红线）。
- **录放门是 JVM 测试**（`FeishuAdapterReplayTest`，6 条），进 CI 既有 test 步——
  **CI 九步不变**，不写 mjs 脚本（归一逻辑只有一份，在 Java 里；脚本复刻等于造第二份判据）。

**验证落点**：
- JVM 全量 `10 + 66 + 364 + 45 = 485` 绿（gateway 358 → 364，+6 全为录放门）。
- **哈希钉**：夹具清单 + 内容组合 sha256（`5f2cda76…b7b08`）钉在 `fixturesAreAppendOnly`，
  改夹具必红——append-only 的机器断言，新增夹具要显式更新哈希（diff 可见）。
- 三样断言：query 逐字、`feishu:chat:oc_chat0001`、`feishu:msg:om_case000001`。
- **spec §3 两条**：跨渠道会话不串（同人同参数飞书 vs webhook 会话键不同 + 飞书内部
  不同 chat 不同段）；幂等不靠参数猜（同参数不同 message id → 不同 clientToken，
  幂等层语义既有锚 `IdempotencyServiceRequestReplayTest`）。
- **变异对照两次演练各红后还原**：C1（`deriveConversationId` 只用平台名 → 会话不串红，
  两个 chat 同键）；C2（`deriveClientToken` 置空 → 幂等断言红）。票 96 的同款变异
  （A/B1/B2）继续被 `ChannelDerivedSessionJvmTest` 钉住。
- 单元直调 `WebhookAdapter` 时补 `ChannelContext.set(WEBHOOK)`（真实链路形态：渠道标签
  由入站端点设置）——顺带暴露出「webhook 适配器依赖 ChannelContext、飞书不依赖」这个
  结构差异，已写进 CODE_MAP。

**三个现场追问**：
1. 夹具是**按飞书公开文档 v2.0 事件格式构造的样本**，不是真事件流的录制（无真凭据）——
   票 115 凭据到位后应把真实事件补录进 `eval/im-events/`（append-only + 更新哈希）；
   若真实事件与文档有出入（多字段/缺字段），归一器与夹具以真事件为准修订。
2. `FeishuAdapter.canFollowUp()` 返回 true（单聊同段会话可追问）——真实链路上追问的
   体验（出站回复是否有输入状态指示等）在票 113 活体里验证，本门只钉契约值。
3. 长连接线程（113）调 `normalize` 时 `EventSink`/trace 坐标从哪来（无 HTTP 请求 →
   `RequestTrace.start()` 谁调）——这是 113 的接线问题，A 段不预设。