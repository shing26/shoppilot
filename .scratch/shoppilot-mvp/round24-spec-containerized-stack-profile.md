# round24 Spec：可选容器档（干净克隆一条命令起全栈）

> 状态：**已开轮（2026-10-02）**，票 76-78。
> 触发：所有者裁定「想给别人一条命令起全栈」，且必须是**可选档**——默认仍是本机 JVM。
> 依据：ADR 0053（四域服务）、ADR 0020（冷启动可复现性）、ADR 0024 的「CI 不依赖外部服务」红线。

## 0. 先说清它不解决什么

**容器化不省内存。** 三个 JVM 跑在容器里还是本机，占同一份 RAM。全栈约 6.6 GB 的账不变，
除非把 Ollama 也塞进容器——而模型那 3.1 GB 恰恰是最大的一块，Windows + WSL2 + GPU 直通是个已知的坑。

它真正买到的是三条：① `mem_limit` 让内存尖峰时**被杀掉的是容器**，不是另外三个项目的容器或桌面应用
（round23 清场日的失败模式正是「进程没有内存上限，谁申请谁被杀」）；② `restart: unless-stopped`
替代只守 biz-mock 的 `watchdog.ps1`（ticket 31 登记过：gateway 的 native OOM 至今无守护）；
③ 别人 clone 下来一条命令起全栈——这是 ADR 0020 那条可复现性判据第一次真正兑现。

## 1. 硬边界（本轮最重要的一段）

- **默认路径不变**：`scripts/up.ps1` 不加参数时仍然起本机 JVM。容器化是 `-Containerized` 下的另一条路，
  不是替换。理由：日常开发要的是热改代码即重启，容器化那条路反而更慢。
- **不进 CI**：round15 当年明写 CI 八步不依赖 Ollama/ES/Qdrant（runner 上跑不起来），
  票 73 加第九步时又强调一遍。**本轮的 compose 只服务本机与演示机**，CI 子集保持九步不变。
- **不把 Ollama 容器化**：服务通过 `host.docker.internal:11434` 走宿主 Ollama。
  代价是多一层间接，收益是避开 GPU 直通这个 Windows 上的老大难。
- **不删本机档**：`up.ps1` / `down.ps1` 的现有行为、端口、日志位置全部保留。

## 2. 票序

| 票 | 范围 | Blocked by |
|---|---|---|
| [76](issues/76-service-images-and-compose.md) | 四个服务的镜像（一份 Dockerfile + build arg）+ `docker-compose.services.yml` + `up.ps1 -Containerized` | — |
| [77](issues/77-clean-clone-reproducibility.md) | 干净克隆复跑：新克隆 + `up.ps1 -Containerized` 能起、能过调试台与工作台 | 76 |
| [78](issues/78-round24-closeout.md) | 收口（EVIDENCE / tracker / CODE_MAP / 审计常数） | 76-77 |

## 3. 验收里必须有的两条「不成立也算数」

- **内存上限**：服务容器必须有 `mem_limit`；写一条用例/断言说明「上限缺失等于没做」——
  否则这项收益在 compose 里会被无声地丢掉。
- **默认路径未被污染**：不带 `-Containerized` 跑 `up.ps1`，行为与本轮之前逐字一致。