# 飞书 L1 联调 Runbook

**目标：** 一个飞书自建应用 + 一个店铺租户，跑通“用户发消息 → Agent → 飞书回复 / 转人工工单”。

> 当前限制：一个机器人绑定一个配置租户/买家（默认 T001 / C001）。多租户映射属 L2。

## 1. 飞书侧

在飞书开放平台创建**企业自建应用**：

1. 开启机器人能力；
2. 事件订阅选择**长连接**，订阅：
   - im.message.receive_v1
3. 权限至少：
   - im:message
   - im:message:send_as_bot
4. 发布应用，并把机器人加进测试单聊/群。

## 2. 网关侧 .env

    SHOPPILOT_IM_FEISHU_ENABLED=true
    SHOPPILOT_IM_FEISHU_APP_ID=cli_xxx
    SHOPPILOT_IM_FEISHU_APP_SECRET=xxx
    SHOPPILOT_IM_FEISHU_TENANT=T001
    SHOPPILOT_IM_FEISHU_CUSTOMER=C001

SHOPPILOT_SERVER_ADDRESS 不需要为长连接改成 0.0.0.0；飞书事件走 WebSocket。

## 3. 启动与确认

启动网关后，日志应出现：

    飞书长连接已启动

失败排查：

- 回环绑定 + 空凭据：日志“飞书长连接启用但凭据为空”，不注册；
- 非回环绑定 + 空凭据：启动直接拒绝（保留 ADR 0029 安全门）；
- 有凭据但启动失败：看“飞书长连接启动失败”堆栈，常见是应用未发布/权限未开/网络。

## 4. 活体验收

在飞书单聊给机器人发：

1. 帮我查订单 90001 的物流
   - 预期：飞书收到订单/物流回复；
   - 网关日志：同步问答完成，intent=ACTION_LOGISTICS。
2. 转人工
   - 预期：飞书收到工单号；
   - T001 工作台“工单队列”能看到该工单，状态 OPEN。
3. 同一会话追问“那 90002 呢”
   - 预期：上下文继续，不要求重报身份。

## 5. 当前边界（不要对外说已接入飞书平台）

- 只支持一个飞书应用、一个配置租户/买家；
- 所有飞书会话共用配置的 customerId（默认 C001），不是真实买家映射；
- 没有渠道管理后台、没有坐席在飞书内回复、没有多租户路由；
- 未做重复 message id 的真实幂等验证（JVM 只验编排）。

## 6. 验收记录（2026-10-27 已跑）

| 项 | 结果 | 证据 |
| --- | --- | --- |
| 长连接启动 | PASS | 20:55:10 飞书长连接已启动；20:55:11 connected [conn_id=7701330353419603220] |
| 业务消息回复 | PASS | 订单 90001 物流查询有回复；Redis 会话 T001/C001 |
| 转人工落单 | PASS | ticket 库 T1793103363180-1、T1793103345700-0，channel=feishu、OPEN |
| 会话追问 | PASS | “那90002呢”返回 90002 物流，不重报身份 |

完整证据：[FEISHU_L1_LIVE_EVIDENCE.md](FEISHU_L1_LIVE_EVIDENCE.md)。

首次实测还暴露了 `message_type=post` 被旧适配器拒绝的问题，已修复并补用例；这不是验收项之外的新功能，是让真实飞书输入可用。
