# ShopPilot Runbook

> 当前状态看 [`STATUS.md`](STATUS.md)；架构与缺口看 [`ARCHITECTURE.md`](ARCHITECTURE.md)。

## 1. 前置

- Docker（Compose v2）
- Python 3.11+（脚本与 demo）
- Ollama + `bge-m3`（约 1.2GB；容器档 MockLLM 只要求 embedding）
- 可选：JDK 21 + Maven wrapper（本机 JVM 开发路径）
- 可选：Node 22+（前端构建/浏览器门禁）

## 2. 起栈

```bash
python scripts/demo.py --up        # 生成 .env、起 full 档、等就绪、跑三场景
docker compose --profile full ps   # 看服务
curl http://127.0.0.1:8082/actuator/health
```

停：

```bash
python scripts/demo.py --down
# 或
docker compose --profile full down
```

Windows 本机 JVM：

```powershell
pwsh -NoProfile -File scripts/up.ps1
pwsh -NoProfile -File scripts/stop.ps1
```

容器内 Maven 默认走 `docker/maven-settings.xml`（Aliyun public）；内网要求 Nexus 时改该文件的 `<url>` 即可。若镜像站也不通，先在本机构建 jar，再走预构建运行时镜像：

```bash
mvnw -B -ntp -DskipTests package
python scripts/demo.py --prebuilt
```

## 3. 重置

- 演示数据：`docker compose --profile full down -v` 会删卷，下次 seed 重新来（**会丢工单/账号**）。
- 只清缓存：`POST /api/v1/support/ops/cache/flush`，带 `X-Ops-Token` 与任意已登录买家 JWT。
- 推进知识库纪元：`POST /api/v1/support/ops/epoch/bump`，之后必须重跑 ingest。
- ingest：full 档的一次性服务；本机路径 `pwsh -File scripts/ingest.ps1`。

## 4. 常见失败

| 现象 | 先查 |
|---|---|
| `docker compose` 报 required variable | 没有 `.env` 或占位符未替换：`python scripts/bootstrap_env.py --force` |
| Docker Hub 401 / `failed to authorize` / 拉 `eclipse-temurin` 超时 | 基础镜像走镜像站，不改 Dockerfile：在 `.env` 设 `SHOPPILOT_MAVEN_IMAGE=docker.1ms.run/library/maven:3.9-eclipse-temurin-21`、`SHOPPILOT_JRE_IMAGE=docker.1ms.run/library/eclipse-temurin:21-jre`；或先 `docker pull` 再 `docker tag` 成默认名 |
| 容器 build 时 Maven Central 握手失败 / 卡在 `dependency:go-offline` | Dockerfile 已把 central 指到 `docker/maven-settings.xml`（Aliyun）；换内网改 `<url>`；仍不通就 `mvnw -DskipTests package` + `python scripts/demo.py --prebuilt` |
| 端口被占（8082/8091/8092/16379/16333/19200） | 停旧栈；Windows 上还有 wslrelay 占标准端口，本项目用偏移端口 |
| 起不来且日志说非回环 + 默认凭证 | 占位符或仓库默认值仍在：`python scripts/bootstrap_env.py --force`，或绑定 127.0.0.1 |
| 容器名冲突（第二份克隆） | `COMPOSE_PROJECT_NAME` 换一个；容器名是全局唯一的 |
| 容器里 ingest 报 `request timed out` / `cudaMalloc failed` | Ollama 要能被容器访问：`OLLAMA_HOST=0.0.0.0:11434`；GPU 显存不足时用 `CUDA_VISIBLE_DEVICES=-1 OLLAMA_NUM_GPU=0`；CPU 慢时容器档设 `SHOPPILOT_EMBEDDING_TIMEOUT=30s` |
| 容器档 T001 的 90001 是 `NOT_FOUND` | 持久档默认不播演示段：容器 biz-mock 需 `SHOPPILOT_BIZMOCK_DEMO_SEED=true`（compose full 已默认给 true） |
| `mvnw package` 失败、jar 变成瘦包 | 服务还在跑，fat jar 被锁：先 `scripts/stop.ps1` / `demo.py --down` 再构建 |
| readiness 503 | ApplicationRunner 未跑完（seed/ingest/预热）；看 `docker compose logs gateway` |
| 问答恒“未在本店找到”/检索空 | 知识库未入库或纪元不一致：看 `/actuator/health/deps`，重跑 ingest |
| Ollama 相关 500/超时 | `ollama list` 是否有 `bge-m3`；`SHOPPILOT_LLM_MODE=local` 时才需要生成模型 |
| 调试台读数为 `-` | 未填 ops token；页面右上角输入 `SHOPPILOT_OPS_TOKEN` |
| 退款审核 403 | 必须用 `AGENT/ADMIN` 账号（`/auth/login`），ops token 不能审资金 |
| 并发退款只成功一笔 | 这是预期：DB 唯一索引 + 网关订单锁；另一路 `STATE_NOT_ALLOWED` |

## 5. 事故取证顺序

1. `docker compose --profile full ps`：谁在跑、谁 unhealthy。
2. 网关/业务/工单日志：`docker compose logs --since 10m gateway biz-mock ticket`。
3. `/actuator/health`（能不能放流量）与 `/actuator/health/deps`（这一轮齐不齐）分开看。
4. Redis 事件断流：`redis-cli MONITOR` 看客户端真实命令；不要只信健康检查。
5. 需要回滚：先 `down`，再 `git checkout <last-green>` 重建；数据库迁移回滚见 V9 的 `U9` 文件头。

## 6. 安全姿势

- `.env` 由 `bootstrap_env.py` 生成；`replace-with-*` 会被启动守卫拒绝。
- mock 身份签发只在回环注册，且请求期再验 remoteAddr/Host；容器档不注册。
- `/ops/**` 默认拒绝：需 `X-Ops-Token` 或 `AGENT/ADMIN`；`/ops/tickets/mine` 是买家白名单。
- 容器档一键体验买家/坐席：`SHOPPILOT_IDENTITY_DEMO_LOGIN_ENABLED=true`；只在网关端口绑 `127.0.0.1` 的演示栈使用，生产保持 false。
- 网关与中间件端口只发布到宿主回环；不要改成 0.0.0.0 对外。
- 资金动作（退款放行/驳回）只接受已验签坐席账号。
