# 171 飞书 L2：多租户身份映射

**Status:** implemented（2026-10-27，代码与 JVM 验收；真实跨租户活体待映射）

**Claimed by:** zcode session 2026-10-27

> L1 已跑通，但所有飞书会话共用 T001/C001。L2 第一刀：同一飞书应用按用户/会话映射到不同租户与买家。

## 目标

- 无映射文件时保持 L1 行为（默认 T001/C001）；
- 配置映射文件后，按 chat_id / open_id 解析 tenant/customer；
- 未知用户/会话**拒绝服务并回复绑定提示**，不静默落到默认租户；
- 会话键仍按 channel + chat_id 派生，不跨租户共用。

## 映射文件

.env：

    SHOPPILOT_IM_FEISHU_IDENTITY_FILE=./config/feishu-identities.json

JSON：

    {
      "default": { "tenant": "T001", "customer": "C001" },
      "chats": {
        "oc_xxx": { "tenant": "T002", "customer": "C001" }
      },
      "users": {
        "ou_xxx": { "tenant": "T004", "customer": "C001" }
      }
    }

解析优先级：chat_id > open_id > default。文件存在但缺少对应条目且没有 default → 拒绝。

## 验收

- 无文件：L1 默认身份，现有 27 条飞书测试不回归；
- 有文件：chat 映射命中、user 映射命中、default 命中、未知拒绝；
- 未知用户收到“尚未绑定店铺”的飞书回复，不执行 Agent；
- 不改 gold / API / 工具语义。

## Non-goals

- 渠道管理后台、数据库映射表；
- 多飞书应用/多租户凭证；
- 场景包与坐席飞书内回复。

## 已实现

- FeishuIdentityResolver：读取 JSON 映射文件，chat_id → open_id → default；
- 无文件时保持 L1 默认身份；有文件时进入严格模式，未知身份拒绝；
- FeishuLongConnectionClient 从事件取 sender open_id，解析后注入 TenantContext；未知身份回复“尚未绑定店铺”，不调用 Agent；
- GatewayProperties.Feishu / application.yml / .env.example 增加 identity-file；
- 示例配置 config/feishu-identities.example.json；
- 文档 docs/FEISHU_L2_IDENTITY_MAPPING.md。

## 验证

- JVM：FeishuIdentityResolverTest 3 条 + 客户端未知身份拒绝 1 条；飞书相关 56 条通过；
- 全量 verify：708 tests / 118 suites 全绿；
- 未做：真实跨租户活体（需要把某个 chat_id/open_id 映射到 T002/T004 后发消息验证会话键）。

## Handoff notes

**关键决策**

1. 映射文件而不是数据库表：L2 先证明路由正确，管理后台是后续产品化；
2. 严格模式默认拒绝，不把未知飞书身份塞进 T001——跨店看到别人订单是事故；
3. 解析器首次解析时读配置，避免启动时把测试/动态配置冻结。

**验证落点**

- FeishuIdentityResolverTest：无文件/优先级/拒绝；
- FeishuLongConnectionClientJvmTest#handleEventRejectsUnmappedIdentity；
- 全量 logs/verify171.log；
- 配置样例 config/feishu-identities.example.json。

**三个现场追问**

1. 为什么 chat 优先于 user？同一群/单聊的归属应稳定，个人身份只在没有 chat 映射时兜底。
2. 为什么未知身份不落 default？default 只用于显式配置的兜底；没有 default 时必须拒绝，否则等于把所有人塞进一个店。
3. 活体怎么验？把当前飞书 chat_id 映射到 T004，重启后发“酒店几点入住”，Redis 会话键应变成 T004:C001:feishu:chat:<id>。
