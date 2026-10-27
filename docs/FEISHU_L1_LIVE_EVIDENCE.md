# 飞书 L1 活体验证证据（2026-10-27）

**结论：** 单租户飞书真实渠道闭环已跑通。范围仅限一个飞书应用、一个配置租户/买家（T001 / C001）。

## 1. 长连接

网关日志：

    20:55:10  FeishuLongConnectionClient - 飞书长连接已启动
    20:55:11  com.lark.oapi.ws.Listener - connected to wss://msg-frontier.feishu.cn/ws/v2
              [conn_id=7701330353419603220]

access_key 已脱敏，不写入证据。

## 2. 第一次失败与修复

用户发送后，飞书事件已到达，但适配器只接受 text：

    20:12:53 ERROR FeishuLongConnectionClient
    IllegalArgumentException: round31 仅支持 text 消息，收到 message_type=post

20:19、20:29 同样错误。修复：FeishuAdapter 支持 post 富文本，从二维 content 提取 text 节点拼接；新增回归用例，飞书 27 条测试通过。重启网关后长连接重建。

## 3. 消息与上下文

Redis 会话键：

    shoppilot:session:T001:C001:feishu:chat:oc_d32195b8fb14e1e6f71121b3b65099f1

会话内可见：

- 用户：帮我查订单 90001 的物流
- 助手：订单 90001 尚未发货，暂无物流轨迹
- 用户：那90002呢
- 助手：订单 90002 已签收，顺丰速运 SF877021968

证明：入站归一、T001/C001 身份、出站回复、同会话追问上下文。

## 4. 转人工工单

ticket 库查询：

    id                | tenant | channel | status | source  | reason         | query
    T1793103363180-1  | T001   | feishu  | OPEN   | DEGRADE | USER_REQUESTED | 转人工
    T1793103345700-0  | T001   | feishu  | OPEN   | DEGRADE | USER_REQUESTED | 转人工

证明：飞书渠道的转人工会落 T001 工单，渠道标签为 feishu。

## 5. 边界（照登）

- 一个飞书应用绑定一个租户/买家，所有飞书会话共用 C001；
- 未做多租户映射、渠道管理后台、坐席飞书内回复；
- 群聊、图片、文件、卡片仍未支持；
- 未做重复 message id 的活体幂等验证。

所以对外只能说“飞书单租户 L1 已跑通”，不能说“已接入飞书平台/多平台”。
