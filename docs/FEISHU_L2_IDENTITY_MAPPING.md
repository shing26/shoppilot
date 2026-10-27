# 飞书 L2：多租户身份映射

**状态：** 票 171 实现；L1 默认行为不变。

## 为什么

L1 所有飞书会话共用 `SHOPPILOT_IM_FEISHU_TENANT/CUSTOMER`（默认 T001/C001）。
真实店铺/场景不可能是同一个买家：需要把飞书 chat_id 或 sender open_id 映射到不同租户。

## 配置

`.env`：

    SHOPPILOT_IM_FEISHU_IDENTITY_FILE=./config/feishu-identities.json

JSON（可从 `config/feishu-identities.example.json` 复制）：

    {
      "default": { "tenant": "T001", "customer": "C001" },
      "chats": {
        "oc_xxx": { "tenant": "T004", "customer": "C001" }
      },
      "users": {
        "ou_xxx": { "tenant": "T002", "customer": "C001" }
      }
    }

## 解析规则

1. 先按 `chat_id` 查 `chats`；
2. 再按发送人 `open_id` 查 `users`；
3. 再落 `default`；
4. 文件存在但三者都没有 → **拒绝执行 Agent**，飞书回复“当前飞书账号尚未绑定店铺，请联系管理员完成绑定。”

没有配置 `IDENTITY_FILE` 时保持 L1：所有会话走 `TENANT/CUSTOMER` 默认值。

## 怎么拿到 id

- `chat_id`：飞书事件里的 `event.message.chat_id`；
- `open_id`：飞书事件里的 `event.sender.sender_id.open_id`；
- 未绑定用户触发时，网关日志会打印：

      WARN FeishuLongConnectionClient - 飞书身份未绑定，拒绝执行 chatId=oc_xxx openId=ou_xxx

## 验收

- 无文件：L1 身份不变；
- chat 映射优先级高于 user，user 高于 default；
- 严格模式未知身份拒绝，且不调用 Agent；
- 映射到 T004 的会话键应是 `T004:<customer>:feishu:chat:<chatId>`，不能复用 T001 会话。

## 边界

- 映射文件是启动时加载，改完要重启；
- 不做管理后台、不做数据库热更新；
- 多飞书应用/多套凭据仍不支持。
