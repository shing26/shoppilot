# 182 固定 VAPID 密钥 + 推送重试/送达指标

**Status:** implemented（2026-10-29）

**Claimed by:** zcode session 2026-10-29

> 票 181 的 VAPID 未配置时每次启动生成临时密钥，重启后旧订阅失效。本票固定本机 VAPID，并给 Web Push 加重试与 Micrometer 指标。

## 目标

- 生成 P-256 VAPID 密钥，写入本机 `.env`（私钥不提交）；
- `.env.example` 补占位；
- Web Push 发送：404/410 删除订阅；429/5xx/异常指数退避重试 2 次；
- 指标：`shoppilot_push_send_total{result=sent|retried|gone|failed}`、`shoppilot_push_latency_seconds`；
- 日志不打印完整 endpoint（只打印 host）。

## 验收

- 网关启动时使用固定公钥，不打印“临时密钥”；
- 公钥接口返回值与 `.env` 一致；
- 重试/指标单测或全量测试通过；
- 全量 JVM 全绿。

## 已完成

- 本机 `.env` 写入固定 VAPID 公钥/私钥/`mailto` subject；私钥不提交，`.env.example` 补注释占位；
- 网关启动公钥接口返回值与 `.env` 完全一致；启动日志不再出现“临时密钥”；
- Web Push：404/410 删订阅；429/5xx/异常退避 100ms、200ms 重试两次；
- Prometheus 暴露 `shoppilot_push_send_total{result=sent|retried|gone|failed}`、`shoppilot_push_latency_seconds`；
- 日志只打印 endpoint host，不打印完整 endpoint；
- 订阅保存失败返回 503，不再谎报 `subscribed=true`。

## 验证

- 公钥接口 matches_env=True；
- Prometheus 已看到 send/latency 指标；
- 全量 733 tests / 124 suites（verify182b）；
- 额外：用持久化 Chrome 成功拿到真实 FCM endpoint，证明浏览器订阅链路成立；直接 Java 发送受本机 MITM 代理证书阻断（PKIX），未完成 FCM 送达。

## Handoff notes

**关键决策**

1. 固定 VAPID 用环境变量，不把私钥放进仓库；
2. 订阅保存失败必须返回 503，前端才能回退页面通知；
3. 重试只针对 429/5xx/异常，404/410 直接删除订阅；4xx 不重试；
4. endpoint 日志脱敏到 host。

**三个现场追问**

1. 怎么证明固定密钥生效？公钥接口与 `.env` 字符串相等；
2. 为什么真实 FCM 没送达？本机 Java 走 MITM 代理时证书链 PKIX 失败；这是环境边界，不是 Web Push 代码；
3. 为什么订阅接口要 503？否则 Redis 失败时前端会误以为已订阅，页面通知也关掉。

## Non-goals

- VAPID 轮换流程；
- 推送限流；
- 真实 FCM 送达（票 183）。