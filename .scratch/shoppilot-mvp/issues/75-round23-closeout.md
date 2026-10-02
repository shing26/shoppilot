# 75 round23 收口：工单域竖切

**Status:** implemented（2026-10-02）

## What to build

- **`docs/EVIDENCE.md`**：新读数（工单三来源行数对账、事件对账门禁读数、CI 九步、两档资源读数）；**未达成照登**（真实 ACK/pending 若只在清场日跑过一次、门控缺口、买家端未做）。
- **`docs/CODE_MAP.md`**：新增 `shoppilot-ticket` 模块所有权与请求链路落点；四服务边界一节。
- **`CONTEXT.md`**：工单域新术语（工单、来源、队列、优先级、坐席、领取、SLA）——**只加术语，不改既有结论**。
- **`README.md` / `DELIVERY.md` / `CHANGELOG.md` / `RELEASE.md`**：定位按 ADR 0052 更新（可运行的系统雏形），但**性能与判据数字不得因定位变化而重算**；端口表加 8092。
- **收口审计**：常数换代（`G6_EXPECT` surefire 计数、CI 步数、模块数）。
- **tracker**：票 69-75 状态与 Handoff notes。

## Blocked by

[69](69-unified-ticket-entity.md) … [74](74-verification-tiering.md) 全部收口。

## 口径

- **一条判据都没动**：gold 180、阈值、`judge()`、降级枚举 10 / 降级 9 一个字不改。
- ADR 0024 的「不宣称上线」红线在交付层照写（0052 继承）。
- 未达成项保留实测值、归因与限制，**不摘红**。**按裁定 A，本轮的未达成栏会很长**——四服务起栈、浏览器断言、两档读数、真实 Redis 行为全部照登，并在 EVIDENCE 里写成一句清楚的话：**「这是所有者在知悉资源账的前提下选的结果，不是执行时才发现的困难」**。

## 验收

- 收口审计读数落 `docs/EVIDENCE.md`，本地读数与入仓产物一致（或按本机限定 SKIP 照登）。
- 干净克隆的 CI 九步全绿。
- program 路线图更新：下一轮（身份域 ADR 0056）标为 ready。

## Verify

```powershell
git diff --check && git status --short
.\mvnw.cmd -B -ntp verify
python .scratch/shoppilot-mvp/round3-closeout-audit.py
gh run list --workflow ci-subset.yml --limit 1
```

## Handoff notes

### 清场日做了什么（这是 round23 与前几轮最大的不同）

所有者安排了一次清场日：停掉另外三套项目的容器（nexus 11 个、opspilot 3 个、moa-gateway 2 个，**docker stop 不删，可逆**），
然后跑 `run-acceptance.ps1 -Tier full` 拿真实读数。**四轮下来，清场日抓到的全是 JVM 抓不到的洞**——
这就是 0052 那句「只交 JVM 层证据」的代价第一次被真正兑现。

### 清场日抓到的缺陷（六个，全部已修）

| # | 缺陷 | 为什么 JVM 全绿也没发现 |
|---|---|---|
| 1 | `shoppilot.ticket.internal-token` 漏在 yml 里，服务起不来 | **每一个测试都传了同名覆盖**，测试替生产配置打了掩护 |
| 2 | biz-mock 仍在查已被 V6 删掉的 `tickets` 表 → `/api/admin/stats` 与 `/api/admin/demo/reset` 一起 500 | JVM 用例从不打这两个运维端点 |
| 3 | 验收矩阵里 workspace 那步的路径混进不可见字符 → 报 missing file | 只在真跑那一步时才解析路径 |
| 4 | 网关过滤器只按扩展名放行静态资源，`/workspace/`（目录路径）401 | 调试台在 `/`，单独放行了；新入口没人放行 |
| 5 | 工单服务没有 `/status` 端点 → 调试台的工单状态按钮 404，`verify-console` 整条红 | 搬迁时只搬了动作端点，忘了运维页那条 |
| 6 | 工作台前端只发了 ops 凭证、没发买家 JWT → 队列永远空 | **而且我第一版门禁写成「队列为空也判过」**，把假绿放过去了 |

第 6 条引出另外两条门禁自身的毛病，也一并修了：

- **「队列空判过」是假绿**：改成队列空即判红。
- **「未触发」也是假绿**：领取/确认框两条断言在没触发时输出 PASS，改成必须真触发；顺带把「门禁依赖前一步残留」改成**自己先造一张降级工单**（工单服务的库是内存库，重启即清空）。
- **拿队列名去推优先级**的断言不成立（一个队列里混着不同优先级）——改成读页面上的优先级列，并**给工作台补了那一列**（坐席本来就需要知道这张为什么排前面）。

### 三个防退化的补充

1. `ProductionConfigSelfCheckTest`（票 75 新增）：**不带任何属性覆盖**地起生产上下文，专门守第 1 条那类洞。变异对照实测：去掉那个键 → 转红。
2. 工单数从「查本地表」改成「问工单服务」，且是**平台级跨租户口径**（同 biz-mock 的 `/api/admin/stats`）——
   第一版按租户去数，在没有租户上下文的运维路径上恒为 0，那是我自己写错的，已修。
3. 前端构建产物入库这条现在有 CI 兜底：重新构建后 `git diff --exit-code`，产物漂了就红。

### 读数（清场日，full 档）

见 `docs/EVIDENCE.md` 的 round23 行。四轮矩阵的收敛过程：

| 轮次 | 结果 | 那轮修掉了什么 |
|---|---|---|
| 第 1 轮 884 s | 18 绿 / 8 红 | 抓到缺陷 1、2、3 |
| 第 2 轮 629 s | 20 绿 / 5 红 | 缺陷 2 的回指修完，action/idem/refund 转绿 |
| 第 3 轮 650 s | 22 绿 / 3 红 | 缺陷 4、5 修完，console/workspace 转绿 |
| 第 4 轮 935 s | 23 绿 / 4 红 | workspace 暴露出门禁自身的假绿（队列空判过） |
| 第 5 轮（终读数） | 见 EVIDENCE | 门禁改真后重跑 |

### 剩下两条红（照登不摘红，**都不是产品代码缺陷**）

- `feedback`：隐式信号③ 幂等重放计数没有增长 —— **round20 已登记的间歇红**（同一形态在 round20 记为「三次连跑两红一绿」）。
- `plansteps`：local 3B 不产生两步链 —— **round20 已登记的已知不达成**，判据一字未动（ADR 0043 明令禁止改窄判据适配实现）。

### 一条新登记的缺陷（**不在本轮修**）

`plan` 步失败：**验收脚本自身的脆弱性**，不是产品代码。它在 ticket 14 里把真 `.env` 挪走、只留一个
`SHOPPILOT_OLLAMA_URL` 占位（**没有 internal token**），结束时恢复并重启网关——但重启前**没有先停掉
用占位配置起的那一个**，端口被占，新进程起不来，旧网关带着空 internal token 继续服务，
于是后续 ops 调用全被 biz-mock 拒（`missing or invalid internal token`）。

根因有两层：脚本的恢复顺序；以及**网关与 biz-mock 的 internal-token 默认值不一致**（网关默认空、
biz-mock 默认 `dev-internal-token-change-me`），只有 `.env` 被换成缺该键的占位时才暴露。
**本轮不修**：它属于验收脚本，不属于系统，而且改它要动另一个脚本的既有语义——登记为下一张票。
