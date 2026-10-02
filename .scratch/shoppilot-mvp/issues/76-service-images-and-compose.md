# 76 四个服务的镜像 + 可选 compose 档

**Status:** implemented（2026-10-02；**镜像构建未验证**，见 Handoff）

## What to build

给四个服务各一个镜像，并把「容器起全栈」做成 `up.ps1 -Containerized` 的**可选档**。

- **`Dockerfile`**（仓根，**一份** + `ARG MODULE`）：多阶段构建——builder 用
  `maven:3.9-eclipse-temurin-21` 并走**仓库自己的 `./mvnw`**（版本因此被 wrapper 钉住，
  与本仓「用 wrapper 不用本机 Maven」的一致性同源），runtime 用 `eclipse-temurin:21-jre`。
  **不含 `.env`、不含 `logs/`、不含 `target/`**（`.dockerignore` 写死这条）。
- **`docker-compose.services.yml`**：四个服务（`gateway` / `biz-mock` / `ticket`，外加一个
  一次性 `ingest` 作业）+ 复用现有中间件 compose 的网络与依赖。
  - 每个服务 `mem_limit`：gateway 768m / biz-mock 512m / ticket 512m / ingest 640m；
  - `restart: unless-stopped`（替代只守 biz-mock 的 watchdog）；
  - JVM 堆用 `-XX:MaxRAMPercentage` **按容器上限**给（不是仓库默认的 2%——那是按 16 GB 宿主算的）；
  - 配置全走既有 `SHOPPILOT_*` 占位符：`SHOPPILOT_REDIS_HOST=redis`、`SHOPPILOT_QDRANT_URL=http://qdrant:6333`、
    `SHOPPILOT_ES_URL=http://elasticsearch:9200`、`SHOPPILOT_BIZMOCK_URL=http://biz-mock:8091`、
    `SHOPPILOT_TICKET_URL=http://ticket:8092`、`SHOPPILOT_OLLAMA_URL=http://host.docker.internal:11434`。
    **注意端口换算**：compose 网络内用容器内端口（6379/6333/9200），不是宿主机映射的 16379/16333/19200。
  - `X-Internal-Token` 走同一个环境变量，**`.env` 不进镜像**。
- **`scripts/up.ps1 -Containerized`**：与 `-SkipBuild` 互斥（容器档自己构建镜像）；
  先起中间件、跑一次性 ingest（挂 `knowledge/` 只读）、再起三个服务，逐个等 readiness。
  **`down.ps1` 加 `-Containerized`**，只停 compose.services 的服务与一次性作业，不碰别的项目的容器。

## Blocked by

无。依据 ADR 0053（四域服务）、ADR 0020（冷启动可复现性）。

## 口径

- **默认路径逐字不变**：不带 `-Containerized` 的 `up.ps1` / `down.ps1` 行为与本票之前一致。
- **不进 CI**：`.github/workflows/ci-subset.yml` 本轮一行不动（九步保持）。
- 不删任何现有脚本与端口；容器档是并行的一条路。

## 验收

- `docker compose -f docker-compose.services.yml config` 通过。
- 四个服务镜像都能构建（至少一个走完两阶段，其余同一 Dockerfile 同参数）。
- 每个服务容器**有** `mem_limit` 与 `restart`；缺任一项即判红（compose 里被无声丢掉是这一档最容易丢的收益）。
- 不带 `-Containerized` 起栈，端口/日志/行为与本轮之前一致。
- 容器内互相以**服务名**访问（不依赖宿主机映射端口）。

## Verify

```powershell
docker compose -f docker-compose.services.yml config
pwsh -NoProfile -File scripts/check-ps-syntax.ps1
```

## Handoff notes

### 关键决策

1. **合并进 `docker-compose.yml`，不起第二个文件**（与票面初稿不同）。服务必须与中间件在**同一个
   compose 网络**里按服务名互访（`biz-mock:8091` 而非 `127.0.0.1:8091`）；分两个文件就要声明
   external network，而 external 网络在中间件还没起时**不存在**——票面初稿的 `config` 就是这么红的。
   合并后用 profile 隔离：`docker compose up -d`（默认，只有中间件，**行为与本票之前逐字一致**）
   与 `docker compose --profile full up -d --build`（全栈）。
2. **一份 Dockerfile + `ARG MODULE`**，不是四份。四个服务同构（同 JDK、同 Spring Boot、同 logback），
   复制四份只会在某次改动里漏掉另外三个。
3. **构建走仓库自己的 `./mvnw`**：wrapper 版本因此被钉住，与本仓「用 wrapper 不用本机 Maven」同源；
   容器档也因此**不需要宿主装 JDK**。
4. **不容器化 Ollama**：服务走 `host.docker.internal:11434`。理由写在 spec §0——模型那 3.1 GB 本来就在宿主上，
   容器化它要处理 Windows + WSL2 + GPU 直通，收益为零、风险一个量级。
5. **`gateway` 等 `ingest` 用 `service_completed_successfully`**：语料没入库就起网关，检索面是空的，
   而那种情况下页面看起来「正常」——所以依赖关系必须写死，不靠运气。

### 验证落点（哪些成立、哪些没成立）

**成立**：
- `docker compose config`（默认档）通过，服务列表**只有**三个中间件 → 默认路径未被污染；
- `docker compose --profile full config` 通过，服务列表 = 三个中间件 + `ingest`/`biz-mock`/`ticket`/`gateway`；
- **四个服务的 `mem_limit` 与 `restart` 逐项核对在位**（ticket 640m/`no`，其余 512–768m/`unless-stopped`）——
  这一项票面要求「缺任一项即判红」，所以它是自检出来的，不是「写上就算」；
- `check-ps-syntax.ps1` 32 文件 0 错（`up.ps1` / `down.ps1` 的新开关在这条里）。

**未达成（照登不摘红）**：
- **四个镜像一个都没构建出来**：Docker Hub 从这台机器不通（`registry-1.docker.io:443` 超时，
  与 GitHub 同时段同症状），本地也没有 builder 基础镜像（只有 `eclipse-temurin:21-jre`）。
  票面「四个服务镜像都能构建」这一条**没有验证**。这不是 Dockerfile 的问题，但也**不等于它对**。
- 因此**容器档从未被真起过**：`-Containerized` 的构建/启动/就绪等待/停栈四条路径都只有静态核对，
  没有一次实跑。干净克隆可复现性（票 77）依赖它，所以票 77 暂时无法开始。

### 现场三问

1. **为什么合并进现有 compose 而不是新起一个文件？** 跨文件要 external network，而它在中间件没起时不存在，
   `config` 当场红。合并 + profile 隔离同时满足「默认行为不变」与「同网络互访」。
2. **为什么不把 Ollama 也容器化？** 那 3.1 GB 本来就在宿主，容器化它省不到这块内存，
   却要引入 Windows + WSL2 + GPU 直通。收益为零，风险一个量级。
3. **为什么 `gateway` 要等 `ingest` 跑完？** 语料没入库时检索面是空的，而页面看起来「正常」——
   这种失败模式最难发现，所以依赖关系写死在 compose 里，不靠 `depends_on: -` 的顺序。
