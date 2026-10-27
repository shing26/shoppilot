# 飞书 L2 跨租户活体验证（2026-10-28）

**结论：** 同一个飞书应用、同一个单聊，可以通过映射文件落到 T004（云栖酒店），不再固定 T001。

## 映射

测试用本地映射（不入库）：

    {
      "default": { "tenant": "T001", "customer": "C001" },
      "chats": {
        "oc_d32195b8fb14e1e6f71121b3b65099f1": { "tenant": "T004", "customer": "C001" }
      }
    }

## 身份日志

    01:25:43 INFO FeishuLongConnectionClient - 飞书消息身份
      chatId=oc_d32195b8fb14e1e6f71121b3b65099f1 tenant=T004 customer=C001

    01:26:02 INFO FeishuLongConnectionClient - 飞书消息身份
      chatId=oc_d32195b8fb14e1e6f71121b3b65099f1 tenant=T004 customer=C001

## 会话隔离

Redis 只有 T004 键：

    shoppilot:session:T004:C001:feishu:chat:oc_d32195b8fb14e1e6f71121b3b65099f1

同一 chat 没有 T001 会话键，说明映射没有复用旧租户的会话。

## 回复口径

用户发：“酒店几点可以入住”

助手回复：

> 本酒店的标准入住时间是当天 14:00 以后，退房时间为次日 12:00 以前……
> 若您是会员且订单中已勾选“提前入住”权益，可享 12:00 入住……

这是 T004 的酒店口径，不是 T001 电商口径。

## 边界

- 测试用的是本地临时映射文件，验证后已恢复默认；
- 映射文件是启动时加载，改完要重启；
- 没有管理后台、数据库热更新、多飞书应用；
- 其他未映射会话在测试映射中仍走 default T001。
