# 77 干净克隆复跑：别人 clone 下来能不能起

**Status:** implemented（2026-10-03；两项已验证、一项未达成）

## What to build

把**已提交的内容**克隆到仓外一份，在那份干净副本上构建与运行——验证的不是「本机能跑」
（本机一直能跑），而是「别人 clone 下来能跑」。

## Blocked by

[76](76-service-images-and-compose.md)。

## Verify

```bash
git clone <repo> /tmp/clean-clone && cd /tmp/clean-clone
docker compose --profile full build
docker compose --profile full up -d
```

## Handoff notes

做法：克隆到 `/d/clean-clone`，用**本地克隆**（不走 GitHub——那条路今天断续不通），
因为本票要验的是「已提交内容能不能构建」，不是「远端有没有同步」（后者由 CI 证明）。

### 抓到的缺陷（已修，且本机怎么测都测不到）

**`mvnw` 在干净克隆里被转成 CRLF，容器构建 `exit 127`（command not found）。**
- 现象：三个镜像全部构建失败，失败点 `RUN ./mvnw …`；
- 根因：`mvnw` 是 `#!/bin/sh` 脚本，checkout 时被 autocrlf 转成 CRLF 就成了 `#!/bin/sh\r`；
  而 `.gitattributes` **只管了几份产物的 `whitespace=cr-at-eol`**，没有任何一条把 `mvnw` 钉在 LF；
- 为什么本机测不出来：**本机工作树就是 LF、构建照过**。任何在本机重复构建的验证对它都无效，
  只有换一份克隆才会暴露——这正是本票存在的理由；
- 修法：`.gitattributes` 钉死 `mvnw text eol=lf`、`mvnw.cmd text eol=crlf`、`.mvn/wrapper/** text eol=lf`
  （`.cmd` 那支反过来，它要 CRLF 否则 Windows 上跑不动）。重新克隆验证：`file mvnw` 不再报 CRLF。

### 已验证

- ✅ 干净克隆的**三个镜像全部构建成功**（`ticket` / `biz-mock` / `gateway` exit=0）；
- ✅ 克隆件上的**一次性 ingest 跑通**：已入库 90 块、纪元推进至 2、ES/Qdrant 各 96
  ——说明镜像里带的是**已提交的那份语料与配置**，不是本机残留。

### 未达成（照登不摘红）：固定 container_name 挡住第二份克隆

`docker compose up` 在中间件这一步就 `Conflict: The container name "/shoppilot-es" is already in use`。
中间件写死了 `container_name: shoppilot-redis/qdrant/es`——这些是 **Docker 全局名字**，
不是 compose 项目内的。于是「在另一份克隆里起一份」必然撞名，而第一份还占着。

- 那几个固定名是本仓**有意为之**（`up.ps1` 里写过：ES 镜像自带的容器名会与本项目撞），不能简单去掉；
- 可行修法留到下一张票：名字带上 `${COMPOSE_PROJECT_NAME}-` 前缀，而不是取消固定；
- **本轮没修**：它动的是既有 compose 的固定名约定，与「镜像可构建」是两件事，混在一起不好验；
- 连带说明：因此**「从第二份克隆完整起一份栈」本轮未完成**。运行中的服务仍是上一份克隆起的那批，
  用新克隆的密钥打过去会 401——那是环境没被重建，不是令牌签发有问题。

### 现场三问

1. **为什么本地克隆而不是从 GitHub 克隆？** GitHub 今天断续不通，而本票要验的是「已提交内容能不能构建」，
   不是「远端有没有同步」——后者由 CI 证明。
2. **为什么 CRLF 这个洞本机永远测不出？** 工作树是 LF。**只有换一份克隆才会暴露。**
3. **`container_name` 该直接去掉吗？** 不能——`up.ps1` 的注释写明 ES 镜像自带容器名会撞；
   正确方向是让名字带上项目名，而不是取消固定。
