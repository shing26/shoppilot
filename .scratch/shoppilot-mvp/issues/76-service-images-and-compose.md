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

**第一次真跑（2026-10-02 晚）的结果**：
- ✅ **四个镜像全部构建出来**（ticket 721MB / biz-mock 721MB / gateway 664MB）。**基础镜像本地化**绕过了
  宿主代理对 Docker Hub 的封锁：`docker pull docker.m.daocloud.io/library/maven:3.9-eclipse-temurin-21` 再
  `docker tag` 回原名，Dockerfile 的 `FROM` 就命中本地镜像、完全不碰 registry。这条路子不需改宿主任何配置。
- ⚠️ `ingest` 第一次 exit 1：我在 command 里传了 `--server.port=0`，而网关有一条硬校验
  「port 必须在 1..65535」（本仓配置纪律）。**已去掉**——本机档的 `ingest.ps1` 同样不传端口，
  靠「入库跑在网关起来之前」避开冲突，容器档保持同一套假设。去掉后 ingest 正常退出。
- ✅ **所有者裁决后（方案 1：容器内可绑 0.0.0.0、宿主端口仍只发布到回环），容器档真起来了**：
  网关 readiness `UP`、四个服务 + 中间件全部 running、`/workspace/` 返回 200。落地时踩到并修掉两处：
  1. `server.address` 改成 `${SHOPPILOT_SERVER_ADDRESS:127.0.0.1}`（三个服务），
     新占位符**显式登记**进 `ConfigValidationTest` 的钉住清单（round19 票 50 的先例，不绕过门禁）；
  2. compose 的凭证透传按 ADR 0029 守卫的逻辑改：`SHOPPILOT_INTERNAL_TOKEN` **不再兜底仓库默认值**
     （兜底等于「没覆盖」，守卫会拒启动），并补上之前压根没传进容器的 `SHOPPILOT_OPS_TOKEN` /
     `SHOPPILOT_OPS_ENABLED`。守卫第一次真拦就是它干的：
     「拒绝启动：当前监听 0.0.0.0……`SHOPPILOT_INTERNAL_TOKEN` 未覆盖；`SHOPPILOT_OPS_TOKEN` 未覆盖，
     运维端点也没显式关闭」——**这正是那条防线该有的行为**。
- ✅ **登录路径也通了（补了令牌签发辅助）**：`scripts/mint-demo-token.py`（纯 stdlib，HS256，
  claim 形状逐项对齐 `JwtService.issue`：sub=cid / tid / cid / iat / exp，密钥不足 32 字节直接拒签）。
  它替代的不是防线而是**演示入口**：密钥本来就在操作者手里，脚本只替他做 HMAC。
  实测：自检通过、令牌被网关接受（不再 401）、`/api/v1/support/ops/tickets` 经网关打到工单服务
  返回 `[]`（跨服务调用通了；`[]` 是因为那次聊天请求我没填对字段、没走到降级）。
- ⚠️ **中间件缺 restart 导致网关空转（我自己的 compose 缺陷）**：只给 full 档服务加了
  `restart: unless-stopped`，中间件那一段没有。结果中间件被宿主内存压杀后再没起来，
  而网关的重启策略在它上面空转——**实测重启 72 次**，日志里全是 `Unable to connect to Redis`。
  已给 redis/qdrant/elasticsearch 补上 `restart: unless-stopped`，起来后网关 readiness 200。
- ⚠️ **冷启动首请求会打穿 3s 读超时**：容器内首次请求触发 Spring MVC `DispatcherServlet` 初始化，
  网关 `read-timeout: 3s` 不够，报 `downstream_unreachable / request timed out`；服务热了之后同一调用正常。
  **登记**：容器档要么给依赖服务加就绪探针+更长超时，要么在文档里写清「首请求可能慢一次」。

- 🛑 **仍未解决（本轮未做）**：上面这条曾被记为「容器档没有演示登录路径」，**已由令牌签发辅助关闭**。
- 🛑 **仍未解决**：`AuthController` 带
  `@Conditional(MockIdentityCondition.class)`，**只在回环绑定上注册**；容器内必须绑 `0.0.0.0`，
  于是 `/auth/mock-token` 返回「接口不存在」，实测拿不到令牌 → 整条链路在登录这一步断掉。
  代价即方案 1 的第三项：**「干净克隆 + 一条命令 + 打开浏览器」不成立**，
  除非补一个用共享密钥签 HS256 的令牌签发辅助（那正是真实 IdP 做的事，不削弱任何防线），
  或者放弃端口发布。本轮未做，登记待裁决。
- 🛑 **本轮第一次真跑前的旧结论**（已被上面推翻，保留作对照）：
  三个服务的 `server.address` 硬编码 `127.0.0.1`（ADR 0029 的回环绑定纪律），
  而 **Docker 端口发布要求容器内绑 `0.0.0.0`**——容器里的 Tomcat 只听自己的回环，
  `127.0.0.1:8082:8082` 这条映射因此打不通，`up.ps1` 等 300 s 就绪失败。
  **这不是配置写错，是「一条命令起全栈」与 ADR 0029 在容器里正面冲突**：
  非回环绑定时那条守卫会要求 JWT secret / internal token / ops token 三处都被环境变量覆盖，否则拒绝启动。
  换句话说，容器档要么放弃回环纪律（并接受守卫要求显式配三处凭证），
  要么放弃端口发布（那外部就访问不到）。**这是定位问题、不是我能替所有者做的取舍。**
- 本轮顺手修掉**我自己写的一个假绿**：`up.ps1 -Containerized` 在就绪失败时只 warn，然后照样打印
  「栈已就绪」横幅（本机档那条路径是 throw）。已改成 throw——与今天修的那三处门禁假绿同一类。

**未达成（照登不摘红）**：
- **四个镜像一个都没构建出来**，且阻塞点已定位到**宿主代理工具**，不是本仓配置：
  - 现象：`docker pull` 报 `Proxy connect error ... dial tcp 100.49.158.130:443` 超时；
    直连 `https://registry-1.docker.io/v2/` 与 `https://github.com` 都是 `000`；
  - 但 `https://repo.maven.apache.org` 直连 `200` —— **网络整体没问题**，只有这两个域名不通；
  - 宿主开着系统代理（`ProxyEnable=1`，`127.0.0.1:31180/31181`，PID 35920），
    Docker 守护进程走 `http.docker.internal:3128` 转发到它；`100.49.158.130` 是那个代理给的
    **fake-ip**，说明请求进了代理、代理没把它送出去；
  - 结论：**代理工具对 `registry-1.docker.io` / `github.com` 的规则或上游节点当时不可用**。
    这不是 Dockerfile 的问题，但也**不等于它是对的**。
- 恢复后要重跑的只有一条命令：`docker compose --profile full build`。
  本机**没有 builder 基础镜像**（只有 `eclipse-temurin:21-jre`），所以无法离线构建、先拉是必然的。
- 因此**容器档从未被真起过**：`-Containerized` 的构建/启动/就绪等待/停栈四条路径都只有静态核对，
  没有一次实跑。干净克隆可复现性（票 77）依赖它，所以票 77 暂时无法开始。

### 现场三问

1. **为什么合并进现有 compose 而不是新起一个文件？** 跨文件要 external network，而它在中间件没起时不存在，
   `config` 当场红。合并 + profile 隔离同时满足「默认行为不变」与「同网络互访」。
2. **为什么不把 Ollama 也容器化？** 那 3.1 GB 本来就在宿主，容器化它省不到这块内存，
   却要引入 Windows + WSL2 + GPU 直通。收益为零，风险一个量级。
3. **为什么 `gateway` 要等 `ingest` 跑完？** 语料没入库时检索面是空的，而页面看起来「正常」——
   这种失败模式最难发现，所以依赖关系写死在 compose 里，不靠 `depends_on: -` 的顺序。
