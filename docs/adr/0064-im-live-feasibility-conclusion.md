# IM 真连可行性考察结论（票 98）：免公网长连接形态成立

Date: 2026-10-07　Status: Accepted　Ticket: 票 98（考察，产出是结论不是代码）

Context: ADR 0060 把 B 段（真连）的开关交给这次考察，并在 Context 第 3 条写下了当时的
前提：「企业微信 / 钉钉 / 飞书的收消息都要配公网 HTTPS 回调……Telegram long polling 与
Slack Socket Mode 免公网，但在这台机器上能否连通未实测」。本 ADR 用 2026-10-07 的实测
读数回答这个前提，四问逐条给出读数与出处。**结论比 ADR 0060 预设的形态更好**：
免公网的不再只有 Telegram/Slack——国内三家如今都有长连接形态，B 段**不需要隧道、
不需要公网地址、不需要备案域名**，ADR 0060 决策 5 的暴露面红线在长连接形态下天然满足。

## 四问读数（2026-10-07 实测）

**环境**：curl 8.18.0（Schannel TLS）、Windows 10、无 `http_proxy`/`https_proxy` 环境变量
（curl 不读 Windows 系统代理，以下全部是**直连**读数）。复现命令见票 98 Handoff。

### 问 1 · 网络：免公网形态能不能连通

| 目标 | 读数 | 判定 |
| --- | --- | --- |
| `api.telegram.org`（假 bot 调 getMe） | 20s 超时，TCP 未建立 | ✗ 不通 |
| 同上，DNS 解析（114.114.114.114） | IPv4 落在 **199.16.158.190（Twitter 段）**，IPv6 落在 **2a03:2880:f11c::/48（Meta 段，`face:b00c` 为其标志）** | DNS 污染实锤 |
| 绕过 DNS，`--resolve` 直连官方 DC `149.154.167.220:443` | 12s 超时 | ✗ TCP 层阻断，「换个 DNS 就能通」不成立 |
| `slack.com/api/api.test`（官方免凭据端点） | HTTP 200 `{"ok":true}`，1.85s | ✓ 通 |
| `sockets.slack.com`（Socket Mode 的 wss 主机） | TCP/TLS 握手成功，HTTP 403（无凭证时预期拒绝） | ✓ 网络层通 |
| `api.dingtalk.com` | HTTP 200，0.43s | ✓ 通 |
| `open.feishu.cn` | HTTP 404（根路径无内容，TLS 正常），0.21s | ✓ 通 |
| `qyapi.weixin.qq.com` | HTTP 403（TLS 正常），0.23s | ✓ 通 |
| `openws.work.weixin.qq.com`（企微长连接 wss 主机） | HTTP 404（根路径无内容，TLS 正常），3.16s | ✓ 通 |

判定：**Slack Socket Mode 成立**（两个主机网络层全通，剩凭据）；**Telegram long polling
在本机直连不成立**（DNS 污染 + 绕 DNS 仍 TCP 阻断双重证据；若要用必须配代理，本机无代理
环境，代理路径未测——按票面口径如实登记「没测」，不写推测）。**国内三家 API 主机全部
直连可用**（0.2–0.4s 级）。

### 问 2 · 国内三家的硬前置（点名文档口径）

**考察的关键发现**：ADR 0060 写「国内主流要公网回调」时，三家的长连接形态要么未上线
要么不普及；2026-10-07 核对官方文档，**三家都有了免公网长连接**：

- **企业微信 · 智能机器人长连接**（[官方文档 101463](https://developer.work.weixin.qq.com/document/path/101463)、
  [获取 Bot ID/Secret](https://open.work.weixin.qq.com/help2/pc/21677)）：
  管理后台创建智能机器人 → 开「API 模式」选「长连接」→ 拿 **BotID + Secret**（长连接专用，
  与回调模式的 Token/EncodingAESKey 是两套）→ 服务端连 `wss://openws.work.weixin.qq.com`
  → 发 `aibot_subscribe` 认证帧（bot_id + secret）→ 保持长连接收 `aibot_msg_callback` /
  `aibot_event_callback`。**无需公网回调、消息帧免加解密**。硬限制：**每个机器人同时只允许
  一条长连接**（新连接踢旧连接，官方建议主备而非多连）；30s 心跳；欢迎语 5s 内回复；
  流式消息 10 分钟封顶；媒体文件另有 AES-256-CBC 单独解密（aeskey 每链接唯一）。
  回调形态对比（若走它）：URL + Token + EncodingAESKey、`msg_signature`（sha1 对
  token/timestamp/nonce/密文排序拼接）、echostr 解密回显握手、可信域名（归属校验文件）、
  企业可信 IP，URL 形态还需**备案域名**（腾讯云发布文档口径）。
- **钉钉 · Stream 模式**（[配置 Stream 推送](https://open.dingtalk.com)、
  [Stream 协议描述](https://open-dingtalk.github.io)、help.dingtalk.io 三种推送模式对比）：
  开发者后台建应用 → 拿 **AppKey（Client ID）+ AppSecret（Client Secret）** →
  「开发配置 > 事件订阅」选 Stream 模式推送 → 协议为 POST `api.dingtalk.com` 注册连接凭证
  换 **endpoint + ticket** → 建 WebSocket → 服务端完成后点「验证 Stream 模式通道」。
  无公网回调。
- **飞书 · 长连接接收回调**（[官方文档](https://open.feishu.cn/document/event-subscription-guide/callback-subscription/step-1-choose-a-subscription-mode/configure-callback-request-address?lang=zh-CN)）：
  **仅自建应用**（商店应用不支持，旧版消息卡片回调不兼容）→ **APP_ID + APP_SECRET** →
  必须用官方 SDK（Java oapi-sdk 2.4.0+ / Python / Go / Node）建 WebSocket 全双工通道。
  **无需公网 IP/域名/内网穿透、无需验签加密配置**（事件处理器两个参数填空字符串）。
  硬限制：**每应用最多 50 条连接，多 client 是集群语义（同一消息随机只有一个 client 收到，
  不是广播）**；保存订阅方式前程序必须在线；事件须 **3 秒内**处理完。
- **审核维度（三家共同）**：自建应用在企业内创建即用，**不走上架审核**（审核只针对商店/
  市场应用）；长连接形态免公网、免备案。

### 问 3 · 暴露面

本仓网关是单进程，承载三个静态入口（console / workspace / buyer）+ `/api/v1/chat`
（SSE 与同步）+ `/api/v1/channel/*`（webhook/email 入站）+ `/api/v1/support/ops/*`
约 19 个端点（工单队列/领取/解决、退款队列/审核、**故障注入 fault/llm-fault、
negative-cache、epoch/bump、cache/flush、demo/reset**、stats、circuit、switches、
retrieval、tenants）。隧道若指向整个网关，最后那一串全是破坏性运维动作——这就是
ADR 0060 决策 5 红线的由来。

- **长连接形态下这一问从「要设计」变成「不存在」**：没有入站 HTTP 回调，网关**无需向公网
  暴露任何端口**，ops 端点根本不在暴露面上。
- 若走回调形态，「只放行回调路径 + 强制平台签名校验」的具体形状：反代/隧道层只转发
  专用回调端点（`/api/v1/channel/im/**`），逐平台强制验签（企微 `msg_signature`、
  钉钉签名头、飞书 verification token / encrypt key），ops 路径在反代层直接 403；
  残余风险四条：① 验签的时间戳重放窗口（各平台容差不同）；② 回调路径本身是匿名可打
  的 DoS 面；③ **ADR 0029 明文登记过的「反向代理场景上游打的是 127.0.0.1，绑定地址
  判定失真」**——dev 默认值的回环合法性在反代后面不再可信，须配合 PostureGuard 显式
  收紧（B2 已下沉原语）；④ 静态入口误配置随隧道一并暴露。

### 问 4 · 凭据

- 家法是现成的：`.env` 不入库（ADR 0020 要求干净克隆无 `.env` 也能起栈）；ADR 0029
  以**绑定地址**为收紧触发器（回环默认值合法打 WARN，非回环拒启）；B2 已把原语下沉到
  `tool/config/PostureGuard`（仓库默认内部令牌 / 空数据库口令在非回环时拒启）。
- 平台凭据照搬同一家法：`.env` 持有（如 `SHOPPILOT_IM_<PLATFORM>_APPKEY/SECRET`），
  compose/启动脚本用 `:?` 必需语法（B2 的 PG 密码先例）；适配器配置读取绑定 PostureGuard——
  回环时空凭据 WARN（演示口径），非回环时空凭据**拒启**。
- **不进 CI**：CI 九步不要求任何 secret；票 97 的录放门吃的是录制的事件 JSON 夹具，
  天然免凭据。
- 本机持有：允许。凭据申请本身是运营动作（要注册企业/应用、人工配置后台），不属于本
  考察能完成的范围——这一格照登为边界。

## 结论

1. **B 段可行性判定：成立**，且形态优于 ADR 0060 预设——免公网长连接在国内三家（企微
   智能机器人长连接 / 钉钉 Stream / 飞书长连接）与 Slack Socket Mode 共四条路，本机
   连通性全部实测通过；Telegram 一家不通（直连）。
2. **ADR 0060 Context 第 3 条就「收消息」这一格已过时**：国内主流不再必须公网回调。本
   ADR 不改写 ADR 0060 原文（它是当时的决策记录），以此处读数为准；其决策 5（暴露面
   红线）在长连接形态下天然满足，决策 6（身份模型不动，平台 id ↔ customerId 无映射表）
   不受本次考察影响，继续有效。
3. **平台选择**（票 97 录制对象 + B4 第一平台）是三选一的裁定项，全部在免公网档内，
   取舍点是连接模型：企微**单连接**（主备）／钉钉 endpoint 动态分配（凭证注册协议）／
   飞书 **50 连接集群语义**（随机一个收到——对本仓单实例网关无碍，多实例部署时语义要
   重看）。裁定归票 99 与所有者。
4. **边界照登**：带真凭据的 wss 应用层握手（`aibot_subscribe` / ticket 注册 / 长连接
   建立）未验——无真凭据，网络层读数止步于 TCP/TLS；Telegram 的代理路径未测（本机无
   代理环境）。两格都不得写成「已验证」。

## Consequences

- R2（IM 真连）的触发条件「票 98 考察结论可开 + B1 完成」**已满足**，B4 进入裁定
  （票 99 / 所有者）；裁定通过前不写任何生产代码。
- A 段（票 96/97）继续有效且顺序提前的事实更强了：四家的入站事件 JSON 都是公开结构化
  格式，录放门不吃网络也不吃凭据，与本次结论独立。
- 对外表述纪律（ADR 0060）沿用：没接入就说「适配器契约可复现」，不说「已接入 XX 平台」；
  wss 应用层握手验完之前，「真连已验证」同样不能说。
- 考察的环境读数（DNS 污染形态、TCP 阻断、四家主机连通性）是 2026-10-07 的本机快照，
  网络环境变化后以重测为准；复现命令在票 98 Handoff。
