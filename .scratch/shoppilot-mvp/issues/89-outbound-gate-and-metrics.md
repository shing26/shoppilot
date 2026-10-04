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

### 清场日补记（2026-10-04，perf 档）：4/7，且暴露一条更大的缺陷
**门禁首次实跑：4/7。生产端真跑通了，投递端一条没送出去。**

- 生产端 ✅：工单服务侧 `shoppilot_outbound_published_total{channel="webhook"} = 1.0`，
  Redis 里 `shoppilot:channel-outbound` 确实收到 1 条事件（ticketId 指得也对）。
- 投递端 ❌：网关侧 `delivered_total` / `failed_total` **一个都没有**，回声端点收到 0 次。
- 计数断言 ❌：门禁自己去读**网关**的 `/actuator/prometheus` 拿 `published_total`——
  而那个计数器长在**工单服务**上。这条断言结构上恒为 0，是门禁自己的错（还没改，记在这里）。

**更大的发现（不属于本票，属于 ADR 0054 本身）**：真 Redis 上**事件骨干没有跑通**。
`shoppilot:audit` 流里有 3 条事件，而**消费组根本不存在**、drain 返回空；
出站消费者每 5 秒一次 `NOGROUP`。而**用 redis-cli 手工执行完全相同的 `XREADGROUP`
（同组名、同消费者名、同流 key）却成功取到了事件**。

也就是说：round23 的 G8 事件对账门禁是在**内存通道**上绿的，它守的那条链在真 Redis 上是断的。
这是 round23 自己登记的「真实 Redis 行为零验证」——今天验到了，**结果是未通过**。

**根因未定位，照登**：已排除的假设（都有证据）：配置指向正确（`spring.data.redis` → 127.0.0.1:16379，
问过生效配置）、db 索引一致（三个 db 都查了）、同一台 Redis（宿主端口 INFO 与容器内一致）、
组名/消费者名/流 key 正确（手工同命令成功）、`Consumer.from(key, group)` 参数顺序正确
（javap 看过签名，Redis 报错里点名的组名与手工命令一致）。

**档位**：perf 档。

### 2026-10-05 补记：三个缺陷全部定位并修掉，门禁 10/10

**取证的关键一步是 Redis `MONITOR`**——它把客户端真正发出的命令原样打出来。
在此之前我做的全是排除性证据（javap 看签名、INFO server 比对、手工 XREADGROUP），
它们只能缩小范围，最后是 MONITOR 一击定案。

**抓到的原文（修前）**：

```
"XGROUP"     "CREATE" "shoppilot:channel-outbound" "gateway-outbound" "0-0" "MKSTREAM"
"XREADGROUP" "GROUP"  "shoppilot:channel-outbound" "gateway-outbound" ...
                         ↑ 这是「组名」的位置
```

`XREADGROUP GROUP <组名> <消费者名>`：代码里 `createGroup` 建的是名叫 `gateway-outbound` 的组，
而 `read` 去找一个叫 `shoppilot:channel-outbound` 的组——**永远不存在**。
根因是 `Consumer.from(a, b)` 的**第一个参数是组名**，我当成流名了。

### 三个缺陷

| # | 缺陷 | 藏在哪儿 | 为什么没人发现 |
|---|---|---|---|
| 1 | 出站消费端把流名当组名 | round26 我自己引入 | JVM 用例把整条通道换成内存实现，**参数顺序错误在那一层根本不存在** |
| 2 | **`shoppilot-biz-mock` 根本没有 `spring.data.redis`** | round23 起就缺 | 它用默认 `localhost:6379`，而 Redis 在 16379；**全程静默**——软依赖的代价是「连错地方」不会让服务起不来，只会让它安静地退化成单机模式 |
| 3 | **`shoppilot-ticket` 把 Redis 写死不认占位符** | 容器档下一直连 localhost | compose 注入的 `SHOPPILOT_REDIS_HOST=redis` 对它无效 |

**②③ 由新判据 `RedisConfigConsistencyTest` 第一次运行就抓到 ③**——它比对三个服务的 yml，
并单独断「端口默认值必须是 16379 而不是被别的项目占着的 6379」。变异对照实测：把 biz-mock 的配置删回事故形态 → **2 条红**。

### 一条我自己的错误更正

昨天我在这份文件里写下「`StreamOperations.createGroup` 没有 mkStream 重载，所以「一次把组与流建好」走不通」——
**那是错的**。MONITOR 显示它发出去的就是 `XGROUP CREATE ... MKSTREAM`：**三参默认实现自带 MKSTREAM**，
javap 只列了签名，没列实现里加的那个参数。已在代码注释里更正。

### 修后读数

- **`verify-outbound` 10/10**：真发 webhook POST 1 次、内容指回工单号、重投不产生第二次、
  两个计数分别在**正确的进程**上读到（第一版两个都去网关读，结构上恒为 0）。
- **审计骨干**：真 Redis 上 `/api/audit` 返回真实事件（`TICKET_RESOLVED` / actor `outbound-gate` /
  `actorAuthenticated: false`），消费组 `shoppilot-audit` 存在且 **pending=0**。
- JVM **`5 + 10 + 57 + 342 + 39 = 454`**（+2 新判据），覆盖率四模块全过。

**round23 登记的「真实 Redis 行为零验证」今天验到了，结果是修完之后通过。**
