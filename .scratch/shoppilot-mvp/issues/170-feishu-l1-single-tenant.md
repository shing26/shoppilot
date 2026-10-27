# 170 飞书 L1：单租户真实渠道跑通

**Status:** in_progress（代码就绪；真实飞书活体 blocked on credentials，2026-10-27）

**Claimed by:** zcode session 2026-10-27

> 所有者选择飞书作为 L1 渠道。先跑通一个租户、一个飞书机器人、一条真实消息闭环，再考虑多租户/多渠道复用。

## 跑通的定义

不是“配置里 enabled=true”，而是：

1. 真实飞书用户给机器人发一条业务消息；
2. 网关按配置租户/买家身份执行 Agent；
3. 飞书收到回复；
4. 需要转人工时，工单能落到该租户、坐席能在现有工作台看到并处理。

## 本轮范围（最小闭环）

### 1. 去掉硬编码 tenantId=feishu

- GatewayProperties.Feishu 增加 tenant / customer；
- .env：
  - SHOPPILOT_IM_FEISHU_TENANT（默认 T001）
  - SHOPPILOT_IM_FEISHU_CUSTOMER（默认 C001）
- FeishuLongConnectionClient.handleEvent 使用配置身份，不再写死。

> L1 限制：一个飞书机器人对应一个配置租户/买家；多租户映射放 L2，不在本轮做。

### 2. 联调材料

- 飞书自建应用：开启机器人、订阅 im.message.receive_v1、开通发消息权限；
- .env 填 APP_ID/APP_SECRET，SHOPPILOT_IM_FEISHU_ENABLED=true；
- 网关非回环绑定下空凭据仍拒启（保留现有安全门）。

### 3. 验收

- JVM：handleEvent 捕获 TenantContext，tenant=T001 / customer=C001，conversation=feishu:chat:<chatId>；
- 活体（需真实凭据）：
  - 飞书发“帮我查订单 90001 的物流” → 收到业务回复；
  - 飞书发“转人工” → 收到工单号，T001 工作台能看到该工单；
  - 同一飞书会话追问不丢上下文。

## Non-goals

- 多飞书应用/多租户路由；
- 渠道管理后台、坐席飞书内回复；
- 企业微信、Web Widget；
- 场景包。

## Blocked by

**真实飞书自建应用凭据**：APP_ID / APP_SECRET / 机器人已启用 / 事件订阅已配置。
没有凭据时只能完成代码与 JVM 验收，不能声称“已跑通飞书”。

## 本轮已完成（代码侧）

- GatewayProperties.Feishu 增加 tenant / customer，默认 T001 / C001；
- FeishuLongConnectionClient 使用配置身份，不再写死 tenantId=feishu；
- application.yml / .env.example 增加 SHOPPILOT_IM_FEISHU_TENANT / CUSTOMER；
- JVM：Feishu 26 条测试通过，新增“配置身份不写死”用例；
- 全量 verify：703 tests / 117 suites 全绿；
- 联调步骤：[FEISHU_L1_RUNBOOK.md](../../../docs/FEISHU_L1_RUNBOOK.md)。

## 还未完成（必须等凭据）

- 真实飞书长连接启动；
- 真实消息收发；
- 转人工工单在工作台可见；
- 同会话追问上下文。

## Handoff notes

**关键决策**

1. L1 只做一个机器人绑定一个租户/买家，多租户映射放 L2；
2. 不把 tenant 写死成 feishu，也不引入复杂映射表；配置默认值 + 显式 env 覆盖；
3. 真实活体未跑，不把 JVM 通过写成“已接入飞书”。

**验证落点**

- 单测：FeishuLongConnectionClientJvmTest#handleEventUsesConfiguredTenantAndCustomer；
- 全量：logs/verify170b.log；
- Runbook：docs/FEISHU_L1_RUNBOOK.md。

**三个现场追问**

1. 为什么所有飞书会话共用 C001？L1 先把链路跑通；真实买家映射要接订单域，属 L2。
2. 为什么不做渠道管理后台？先证明一条真实消息闭环，管理后台是复用性需求。
3. 怎么判断真跑通？飞书发消息收到回复、转人工落单、工作台可见、追问不丢上下文，四项都有证据。
