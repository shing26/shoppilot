# 79 容器名与宿主端口按项目作用域化

**Status:** ready-for-agent

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

（收口时补。）