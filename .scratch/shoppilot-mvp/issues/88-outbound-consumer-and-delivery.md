# 88 出站消费端：按渠道投递 + 幂等 + 失败落回执工单

**Status:** ready-for-agent

## What to build

**消费端**（ADR 0059 第 3、5、6 条），落在**网关进程内**，不为它起第五个 JVM。

- **消费循环**：读 `shoppilot:channel-outbound`，消费组 + ACK，惰性或后台线程均可
  （本仓对审计选了惰性，对出站**建议后台线程**——回流晚几十秒无所谓，但「要有人点一下才投递」不对）。
- **按 channel 分派**：
  - `webhook` → 对 `target` 发一次真实 HTTP POST（**这是本轮唯一「真发出去」的渠道**）；
  - `email` → **不真发 SMTP**，落一张 `CHANNEL_RECEIPT` 工单（ADR 0035 不动，本轮照登）；
  - 其它渠道 → 记一条「无投递方式」的 warn，**不假装投递成功**。
- **幂等**：按 `eventId` 去重（至少一次投递），重投不产生第二次 POST。**去重集合要有界**，否则它是第二本账。
- **失败处置**：重试 **3 次**后落一张 `CHANNEL_RECEIPT` 工单（ADR 0059 第 6 条）。
  工单内容含工单号、渠道、失败原因与最后一次的响应码。
- **`callbackUrl` 入参**：`WebhookAdapter` 的归一契约新增**可选** `callbackUrl`；
  没有它就不发事件（票 86 那两列为空 → 87 不发 → 88 无从投递，三处口径一致）。

## Blocked by

[87](87-outbound-publish-on-resolve.md)。

## 口径

- **「投递成功」的定义必须写死**：目标返回 2xx 才算成功；连接失败、超时、非 2xx 都是失败（不许把「发出去了」当「送到了」）。
- **不引任何新的中间件或邮件库**。
- 判据面零改动。

## 验收

- JVM 用例覆盖（**用 JDK `com.sun.net.httpserver.HttpServer` 起一个本地回声端点**，不需要起全栈）：
  - 投递成功时**下游真的收到了 POST**，且 body 与 `ticketId` 对得上；
  - **重复投递同一条 `eventId` 只 POST 一次**；
  - 目标不可达 → 重试 3 次 → **回执工单存在且可查**，且失败计数 +1；
  - email 渠道 → 落回执工单，**且没有 SMTP 依赖**（构建期可查：全仓无 `javax.mail` / `spring-boot-starter-mail`）；
- **变异对照**：把去重去掉 → 第二条红；把「3 次后落单」改成「3 次后丢弃」→ 第三条红；
  把「2xx 才算成功」改成「发出去了就算成功」→ 第一条与第三条红。
- **这条门禁是本轮最承重的一格**：round23 抓到的假绿（「队列空也判过」「未触发也 PASS」）都在这一格附近。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
```

## Handoff notes

（收口时补）