# 多渠道接入做契约级 ChannelAdapter，不做真实渠道集成

Context: 对标架构要求多渠道归一接入（App/小程序/邮件/Web）。ShopPilot 现状是单一 SSE 通道（`ChatController`）。真实渠道集成需要平台账号、回调验签、外网可达——全部超出单机验证件边界；但"渠道"作为一个维度，影响会话键、限流维度、风格选择与指标切分，契约本身值得做实。

Decision: 新增 `channel` 包，定义入站归一契约：

- `ChannelAdapter` 接口：把各来源报文归一为统一 `ChatRequest`（含 `channel` 字段、原文、发起者身份线索），并声明该渠道的回包能力（能否流式、能否追问）；
- 实现三个：`WebSseAdapter`（现状 SSE 通道搬运，行为零变更）、`WebhookAdapter`（通用 HTTP JSON 入口，模拟 App/小程序回调用）、`EmailAdapter`（同步收件 → 全链路 → 结果落工单/回执，异步化交给降级链路的既有语义，不做邮件服务器）；
- `channel` 进入：会话键组成、`RateLimitService` 维度、SSE `meta` 回显、新指标 `shoppilot_channel_requests_total{channel}`。

所有渠道共享同一条状态机、同一套缓存防线与归属校验——渠道只是入站标签，不是隔离边界；跨渠道会话归属仍由"店铺 + 买家"二元组判定（ADR 0025），渠道不参与身份推导。

Considered Options:

- 接入真实平台（微信客服/邮件 IMAP）：否决。外部依赖与凭据管理违反 ADR 0024 的单机边界；Webhook 形态已覆盖"非浏览器入站"的契约验证需求。
- 为每条渠道建独立状态机实例/端口：否决。多一份编排逻辑就多一处漂移面；渠道差异应收敛在适配层，链路本体必须唯一。
- 邮件渠道做异步队列（Redis Streams）：否决，属 ADR 0040 非目标。EmailAdapter 的"收件即办、结果落工单"已能走通端到端且可验收。
- channel 纳入缓存 key：否决。同一买家跨渠道问同一句得到同样答案不是串号，是正确；缓存分区的既有四维（tenant/scope/intent/kb_epoch）不动。

Consequences:

- `ChatController` 拆出归一层，SSE 行为零变更（现有调试台与验收脚本不受影响）。
- 新增验收判据：同一句话从 3 个渠道进入，答案一致、会话不互串、限流按渠道维度可查——复用 `verify_l2_filters.py` 的归属断言思路。
- WebhookAdapter 无流式能力，回包为整段 JSON；SLO 只承诺"完成"，不承诺"流式"，照实写进 README。
- 评测集新增 `cases-part5-channel.jsonl` 10 条（含跨渠道同问、跨渠道会话续接、渠道间不串号 3 类）。
