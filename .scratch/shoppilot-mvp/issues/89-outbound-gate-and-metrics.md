# 89 出站的门禁与三个计数

**Status:** implemented（2026-10-04；**门禁本轮未实跑，照登**）

## What to build

让「回流了没有」**能被机器读到**，而不是靠人点。

- **三个计数**（命名沿用仓内既有分法）：
  - `shoppilot_outbound_published_total`（工单服务侧发出，带 `channel` 标签）
  - `shoppilot_outbound_delivered_total`（网关侧 2xx，带 `channel` 标签）
  - `shoppilot_outbound_failed_total`（重试耗尽，带 `channel` 标签）
  - 外加一个「**因为没有目标而没发**」的计数——否则「发出 0 条」与「都成功了」在读数上长得一样（票 87 已埋）。
- **投递门禁** `scripts/verify-outbound.mjs`（或并进既有的 `verify-channel.ps1`）：
  起本地回声端点 → 走一遍「落单 → 坐席领取 → 处理完成」→ 断言**回声端点真的收到过**。
- **接进验收矩阵**：`run-acceptance.ps1` add-only 一步（不动既有 26 步的判据）。

## Blocked by

[88](88-outbound-consumer-and-delivery.md)。

## 口径

- **加标签不加名**：本轮指标名若新增，`docs/EVIDENCE.md` 与 README 的指标计数**同一次换代**，
  不留「名字加了但计数没换」的中间态（round22 票 66 的教训）。
- **门禁自己先造工单**，不依赖前一步的残留（round23 票 73 门禁那条「不依赖残留」的同款）。
- **队列/事件为空判红**：这是本仓反复吃过的假绿。

## 验收

- `run-acceptance.ps1` 的新步在**清场日**实跑一次；在此之前**按未达成登记**，不得声称已验证；
- 三个计数在 `/actuator/prometheus` 上可见且带 `channel` 标签；
- **变异对照**：把 `delivered` 计数改成在「发出去了」时就 +1 → 门禁必须红
  （那条断言就是钉「送达 ≠ 发出」）。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
node --check scripts/verify-outbound.mjs
```

## Handoff notes

### 落点

- `scripts/verify-outbound.mjs`（新增）：起本地回声端点 → 自己造一张带 webhook 目标的工单 →
  坐席领取 → 结单 → **轮询等回声端点真的收到** → 断内容与「只收到一次」→ 再断两个计数。
  接进 `run-acceptance.ps1` 为 **full 档**一步（add-only，既有 26 步的判据一字未动）。
- 四个计数在票 87/88 已落地：`published_total{channel}` / `no_target_total`（生产端）、
  `delivered_total{channel}` / `failed_total{channel}`（消费端）。
- **README 指标名换代**：53 → **55**（+`delivered_total` +`failed_total`），数法一字未改。

### 补上一个漏实现（否则整条 webhook 路径是死的）

写门禁时才发现：`WebhookAdapter` 把 `contact` 写死成 `null`，
于是 **webhook 来源的工单永远没有投递目标**，出站对 webhook 渠道根本不可能发生——
ADR 0059 第 4 条那个「可选 `callbackUrl`」我此前一直没做。

补上：入站 payload 可选 `callbackUrl`，**只接受带主机名的 http/https 绝对地址、上限 255**；
没有它不影响请求（绝大多数调用方只是来问一句）。新增 `WebhookCallbackUrlTest` 5 条。

**顺带登记一条安全边界**：这个字段让网关能对调用方给的地址发一次 POST。本仓的防线是
「网关只绑回环 + 目标必须绝对 + scheme 限 http/https」，**挡得住顺手填的 `file:///etc/passwd`，
挡不住有意的 SSRF**——那需要一层出站地址白名单，本轮不做。

### 门禁的承重部分与它为什么这么写

承重的是「**回声端点真的收到了那一次 POST**」，不是「计数变大了」。
把 HTTP mock 掉之后「调用过 send」与「送到了」就分不开了——
那正是 round23 抓到的「队列空也判过」「未触发也 PASS」同族假绿。
所以门禁用 `node:http` 起一个**真的**端点，并轮询等它（消费循环默认 5 s 一跳）。

**门禁自己造那张工单**（走 webhook 渠道的显式转人工），不依赖前一步的残留——
工单服务的库是内存库，靠别处留下就等于在别的顺序下变成假绿（round23 票 73 的同款纪律）。

### 未达成照登

**门禁本轮未实跑**：要起四服务栈（≈6.6 GB）而本机只剩约 1.9 GB（裁定 A）。
只过了 `node --check`，读数按未达成登记，**不得声称已验证**。

### 读数

| 项 | 值 |
|---|---|
| JVM 全仓 | `5 + 10 + 57 + 336 + 32 = 440`（gateway 331 → **336**，+5 `callbackUrl`） |
| 覆盖率 | 四模块全过（`COVERAGE OK modules=4`） |
| 矩阵 | full 档 **26 步 → 27 步**（add-only；daily 档不变） |
| 脚本语法 | `verify-outbound.mjs` `node --check` 绿；`check-ps-syntax.ps1` **32 文件 0 错** |

**一条判据都没动。**

### 现场三问

1. **为什么门禁用 mock 令牌而不是坐席账号？** 门禁不依赖演示账号口令，那本机就要额外汇给一份凭证。
   坐席动作走的是 ops token 那条线——票 82 已把它与角色守卫的关系写清（ops token 守运维面，不守「谁」）。
2. **为什么计数断言排在投递断言后面？** 投递是事实、计数是观测。
   先断事实再断观测，顺序反过来就会变成「计数涨了就算送到了」。
3. **为什么不把门禁放进 daily 档？** 它要活体栈，daily 档的约定是不起栈。放进 daily 档等于让日常档起栈。