# 98 真连可行性考察（产出是结论，不是代码）

**Status:** implemented（2026-10-07）

## What to build

**一次考察任务**：回答「B 段（真连）在这台机器 / 这个环境上到底能不能成立」，
**不写任何生产代码**。它的产出是一份带证据的结论，交票 99 裁定。

## 要回答的四问（每问都要有实测读数，不许推测）

1. **网络**：Telegram（long polling）与 Slack（Socket Mode）这两个**免公网形态**，
   从这台机器**能不能连通**？连不通就说连不通，**不写成「国内网络通常不通」**这种推测。
2. **国内主流形态**：企业微信 / 钉钉 / 飞书的公网回调要求，除了公网地址之外还有哪些硬前置
   （应用注册、审核、签名校验、白名单、回调校验握手）？**逐条列出来并点名各自的文档口径。**
3. **暴露面**：如果要用隧道或公网地址，**哪些路径会被暴露**？本仓的 ops 端点
   （故障注入 / 清缓存 / 队列）在暴露面上意味着什么？给出「只放行回调路径 + 强制签名校验」
   这条方案的具体形状，以及它的残余风险。
4. **凭据**：真平台凭据从哪来、能不能在本机持有、会不会进版本库
   （本仓的红线：`.env` 不入库，ADR 0029 的同一家法）。

## Blocked by

[97](97-im-adapter-and-replay-gate.md)。

## 口径

- **这是考察，不是开发**：不改生产代码，不加依赖。
- **每条结论都要能被别人复现**：命令、环境、读数写进本票的 Handoff。
- **不许用推测代替实测**。本仓已经吃过两次这种亏
  （`OLLAMA_MAX_LOADED_MODELS=1` 那次误诊、compose ports 不吃 shell 环境的成因至今未查清），
  两次的代价都是「一个听起来顺的故事」被当成了结论。

## 验收

- 四问逐条有答案，**「不能」也是答案**；
- 若结论是「B 段不成立」，本票照登并给替代路径（例如只保留 A 段作为交付内容），
  **不因为「目标是接 IM」而强行把它做成能做的样子**；
- 结论落进 ADR 0060 的 Consequences 或一份新 ADR，并更新 tracker。

## Verify

```powershell
git diff --check && git status --short
```

## Handoff notes

**结论（全读数与文档口径见 [ADR 0064](../../../docs/adr/0064-im-live-feasibility-conclusion.md)）**：
**B 段可行性成立，形态优于 ADR 0060 预设**——免公网长连接在国内三家（企微智能机器人
长连接 / 钉钉 Stream / 飞书长连接）与 Slack Socket Mode 共四条路，本机直连连通性全部
实测通过；Telegram 一家不通（DNS 污染 + 绕 DNS 直连官方 DC 仍 TCP 超时）。国内主流
不再必须公网回调 → **不需要隧道、不需要公网地址、不需要备案域名**，ADR 0060 决策 5
暴露面红线在长连接形态下天然满足（没有入站 HTTP 回调，ops 端点不在暴露面上）。

**关键决策**：
- 所有者指示直接考察（2026-10-07）：96/97 仍 ready-for-agent，四问实质不依赖其产出
  （97 的口径本身就写着「真正的选择发生在票 98」）。
- 结论落**新 ADR 0064**，不改 ADR 0060 原文——它是当时的决策记录，Context 过时这一格
  在新 ADR 里说明。
- 平台三选一（企微单连接主备 / 钉钉 endpoint 动态分配 / 飞书 50 连接集群语义）留给
  票 99 与所有者裁定，本票不裁定。

**四问逐条（要点）**：
1. 网络：Slack REST 200（1.85s）+ Socket Mode 主机 TLS 通（403=无凭证预期）；Telegram
   DNS 被解析到 Twitter/Meta IP 段 + 直连官方 DC 149.154.167.220 超时；国内三家 API
   主机 0.2–0.4s 全通（api.dingtalk.com 200 / open.feishu.cn TLS 通 / qyapi 403 /
   openws.work.weixin.qq.com 404=根路径无内容）。
2. 国内三家：都有长连接形态，硬前置逐条点名文档（ADR 0064 问 2 节）；自建应用免上架
   审核、长连接免公网免备案。
3. 暴露面：长连接形态下问题不存在；回调形态的方案形状与四条残余风险见 ADR 0064 问 3
   （含 ADR 0029 登记过的「反代后回环判定失真」）。
4. 凭据：`.env` 不入库 + PostureGuard 非回环拒空凭据（ADR 0029 家法，B2 原语现成）；
   不进 CI（录放门吃录制夹具不吃凭据）；凭据申请是运营动作，照登为边界。

**边界（照登，不得写成「已验证」）**：带真凭据的 wss 应用层握手未验（无凭据，读数止步
TCP/TLS）；Telegram 代理路径未测（本机无代理环境）。

**验证落点（复现命令，2026-10-07 本机直连，curl 8.18.0 无代理环境变量）**：

```bash
# Telegram：直连超时 + DNS 污染证据
curl -m 20 https://api.telegram.org/bot000:fake/getMe          # → 超时 (28)
nslookup api.telegram.org 114.114.114.114                       # → 199.16.158.190 (Twitter 段)
curl -m 12 --resolve api.telegram.org:443:149.154.167.220 \
  https://api.telegram.org/bot000:fake/getMe                    # → 超时 (28)，TCP 阻断实锤

# Slack：REST 免凭据端点 + Socket Mode 主机
curl -m 20 https://slack.com/api/api.test                       # → 200 {"ok":true}
curl -m 20 https://sockets.slack.com/                           # → TLS 通，403（无凭证预期）

# 国内三家 API 主机
curl -m 15 https://api.dingtalk.com/                            # → 200
curl -m 15 https://open.feishu.cn/                              # → 404（TLS 正常）
curl -m 15 https://qyapi.weixin.qq.com/                         # → 403（TLS 正常）
curl -m 15 https://openws.work.weixin.qq.com/                   # → 404（TLS 正常）
```

**三个现场追问**：
1. 平台裁定：企微（单连接主备）/ 钉钉（endpoint 动态分配）/ 飞书（50 连接集群语义）
   三选一，第一平台的连接模型与本仓单实例网关的部署形态怎么配？
2. 真凭据申请走哪家（运营动作：注册企业/应用、后台配置），申请下来后的 wss 应用层
   握手复验用什么形态的读数收口？
3. 飞书长连接「保存订阅方式前程序必须在线」与本仓「干净克隆判据」的演示流程有没有
   冲突（起栈顺序要不要把长连接客户端排进 readiness）？