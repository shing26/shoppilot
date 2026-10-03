# 79 容器名与宿主端口按项目作用域化

**Status:** implemented（2026-10-03；并存已证，一处未查清）

## What to build

让「在第二份克隆里起一份栈」成为可能——这既是 round24 的初衷，也是票 77 唯一未达成的项。

**两个阻塞，都要解**：

1. **容器名是 Docker 全局名**：中间件写死 `container_name: shoppilot-redis/qdrant/es`，
   第二份克隆必然 `Conflict: ... already in use`。
   改法：`${COMPOSE_PROJECT_NAME}-redis` 这类形式。compose 从目录名派生项目名，
   所以**主仓的名字一个字都不变**（`D:\ShopPilot` → 项目名 `shoppilot` → 仍是 `shoppilot-redis`）。
2. **宿主端口写死**：`16379/16333/19200` 与 `8082`。名字解开了，端口照样撞。
   改法：端口映射写成 `${SHOPPIOT_REDIS_PORT:-16379}:6379` 之类，第二份克隆用环境变量整体右移。

**连带要改的**（漏了就是假通）：
- `scripts/up.ps1` 等待中间件与网关就绪时用的端口，要读同一批变量；
- `scripts/down.ps1` 停的端口同理；
- 文档里「默认端口」那一行要写明「可用环境变量整体偏移」。

## Blocked by

[77](77-clean-clone-reproducibility.md)。

## 口径

- **默认档行为逐字不变**：不带任何环境变量时，容器名与端口与本票之前完全一致。
- 不改服务之间的内部地址（它们走 compose 网络内的服务名，与宿主端口无关）。
- 不删固定名约定的**理由**：`up.ps1` 注释写明 ES 镜像自带容器名会撞——所以是「按项目加前缀」，不是「取消固定」。

## 验收

- `docker compose config`（无环境变量）渲染出的容器名与端口，与本票之前**逐字相同**；
- 在**第二份克隆**里用右移后的端口起一份栈，与主仓那份**并存**（两个网关可同时用浏览器打开）；
- 主仓那份栈在改动后照常起（默认档未受影响）。

## Verify

```powershell
docker compose config
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
```

## Handoff notes

### 关键决策

1. **容器名改成 `${COMPOSE_PROJECT_NAME}-redis` 这类形式，不是取消固定**。compose 从目录名派生项目名，
   所以主仓（目录名 `ShopPilot`）渲染出来**一字不变**，而第二份克隆拿到自己的一套。
   `up.ps1` 注释里写过「固定名是有意的」（ES 镜像自带容器名会撞），所以方向是**加前缀**而不是**去固定**。
2. **宿主端口留默认值、整体可右移**——只解名字不解端口等于没解，第二份克隆照样撞端口。
3. **偏移量的落点定为 `.env` 而不是 shell 环境变量**（见下，这条是被实测逼出来的）。

### 实测读数

| 项 | 结果 |
|---|---|
| 默认档渲染 | `shoppilot-es/qdrant/redis` + 19200/16333/16379 —— **与改动前逐字相同** |
| `--project-name cleanclone` 渲染 | `cleanclone-es/qdrant/redis` —— 名字真正分开 |
| **两份克隆并存** | 主仓网关 **8082** readiness 200；第二份克隆网关 **18082** readiness 200；两个容器同时在跑 |
| 经第二份克隆的功能调用 | 令牌被接受（不再 401），`/api/v1/support/ops/tickets` 返回 **HTTP 200 + `[]`** |

**这就是票 77 那条「从第二份克隆完整起一份栈」的未达成项——现已达成**。

### 一条被实测逼出来的决定：偏移量走 `.env`，不走 shell 环境

现象：同一个 compose 文件里 `${COMPOSE_PROJECT_NAME}`（container_name）**生效**，
而 `${SHOPPIOT_QDRANT_PORT:-16333}`（ports）**不生效**——用 `export` 和 `VAR=x cmd` 两种写法都试过，
渲染出来恒为 `16333`。

但 `docker compose config --environment` **能看到** `SHOPPIOT_QDRANT_PORT=26333`。
也就是说：变量读到了、文件里有占位符、却没替换进 ports。这一层的具体成因我没有查清，
**不写成「大概是版本问题」那种推测**。

落点改成 `.env` 之后立刻成立：给第二份克隆放一份 `.env`（端口 + ADR 0029 要的三处凭证），
**不给任何 shell 环境变量**，`config` 渲染出 `29200 / 26333 / 26379`，`up` 退出码 0，
两份克隆并存。

顺带的好处：ADR 0029 要求的那三个凭证本来就要显式给，现在和端口写在同一处，
第二份克隆的 `.env` 就是它自己的全部容器配置。

### 未查清 / 未验证（照登）

- **ports 段不吃 shell 环境变量这件事的成因没查清**。现在靠 `.env` 绕开了，但那是绕过、不是解释。
  下一个碰到「compose 变量在某些段不替换」的人应该从这里接着查。
- **curl 打同步问答一直返回 `invalid_request`**（带不带 `charset=utf-8` 都一样）。
  我读到的 `ChatRequest(query, idempotencyToken)` 与我拼的 payload 对得上，
  但**端点路径是我猜的**（`/api/v1/support/chat`）。仓里跑通过的是 `demo.ps1` / `verify-*.ps1` 那些
  PowerShell 脚本。所以这条**不能判定为缺陷**，只能说「我这条 curl 没打对」。
  票面验收里的功能链路，仍以脚本路径为准。

### 现场三问

1. **为什么固定名不直接去掉？** `up.ps1` 写明 ES 镜像自带容器名会撞；正确方向是按项目加前缀。
2. **为什么偏移走 `.env`？** 因为 shell 环境在 ports 段这条路径上不生效（实测），而 `.env` 是 compose 原生读的。
3. **默认档真的没变吗？** 变的是**写法**，不是**渲染结果**——默认渲染与改动前逐字相同，这一格是核对过的。
