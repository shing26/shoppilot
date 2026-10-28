# 176 渠道会话：真实接管与回复活体

**Status:** implemented（2026-10-28，真实入站/接管/回复/释放已跑通）

**Claimed by:** zcode session 2026-10-28

> 票 175 用种子会话验证了工作台接管 UI，但没有真的把坐席回复发回飞书。本票补真实闭环。

## 步骤

1. 起栈与网关，确认飞书长连接；
2. 真实飞书用户发一条消息，确认收件箱出现会话；
3. 坐席接管，状态变 AGENT；
4. 工作台发送一条坐席回复；
5. 飞书侧确认收到该回复；
6. 释放回 BOT，清理测试数据。

## 验收

- 真实入站消息进收件箱；
- 接管后 Agent 不再自动回复；
- 坐席回复真的发到飞书；
- 会话记录追加坐席消息；
- 释放后状态回 BOT。

## Non-goals

- 实时推送；
- 多渠道路由；
- 归档持久化。

## 结果

真实飞书会话 `oc_d321...f1`：

1. 14:30 用户发“你好，我在测试人工接管”，收件箱出现会话，Agent 回复“已为您转接人工客服。”；
2. 坐席 API 接管：status=AGENT、assignee=agent、noticeSent=true；飞书收到“人工坐席 agent 已接入，请稍候。”；
3. 工作台回复：飞书读接口确认收到“您好，我是人工坐席，已收到您的测试消息。”；会话记录追加 role=agent；
4. 释放：status=BOT、assignee=null、noticeSent=true；飞书收到“已切回智能助手”；
5. 释放后用户再发一条，Agent 自动回复并带工单号，证明 BOT 恢复。

飞书读接口 `im/v1/messages` 返回 code=0，消息序列与上述一致。

**编码插曲**：第一次回复用 PowerShell 拼 JSON，中文被折成 `??` 发到飞书；改用 UTF-8 JSON 后发送正确。这是调用方式问题，不是服务端编码问题。

**缺口**：接管期间没有让用户再发一条，所以“AGENT 状态跳过 Agent 自动回复”由代码与状态机测试覆盖，未单独做活体对照。

截图：[workspace-conversations-live.png](../../../docs/workspace-conversations-live.png)。

## Handoff notes

**关键决策**

1. 用真实飞书读接口核对出站，不靠“API 返回 200”自证；
2. 接管/回复/释放全部走 HTTP API，工作台截图只做界面证据；
3. 验完删除 Redis 收件箱键，避免测试会话留在演示环境。

**验证落点**

- 飞书消息读接口：code=0，含接管通知、坐席回复、释放通知；
- Redis 会话记录：BOT → AGENT → BOT，消息含 role=agent；
- 截图 docs/workspace-conversations-live.png。

**三个现场追问**

1. 为什么先出现 `??`？PowerShell 默认编码把中文折了；改用 Python UTF-8 后正常，产品链路没改；
2. 怎么证明回复到了买家？不是看发送 API 返回，而是用飞书 `im/v1/messages` 读回消息列表；
3. 为什么释放后自动回复能证明？用户下一条消息收到了带工单号的 Agent 回复，状态已回 BOT。
