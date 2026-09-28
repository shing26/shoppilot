# 67 — 告警最小集 + `promtool test rules`

**What to build:** 让「告警规则」在本仓第一次**可被机器证明**。今天仓库里没有任何 `*.rules.yml`/`*alert*`/`*prometheus*`。本票落 4 条最小规则（全部引用真实指标名）+ `promtool test rules` 的合成序列测试（**该响时响、不该响时不响**）+ CI 一步。

**Blocked by:** None（可立即开始）。

**Status:** implemented（2026-09-28）。

**依据：触发已到（ADR 0048 / ADR 0051）。** ADR 0024 曾把「最小告警集」列为非目标，理由是「本机无 Prometheus 与 Alertmanager 实例，**写了只能证语法、证不了该响时会不会响，正是这条判据要拦的自述**」；而它说的「这条判据」是同一份 ADR 立的总筛子 ——「能在本仓以 **0 token、不依赖一次性活体读数**的形式被机器复跑证明」。`promtool test rules` 是**纯离线**的告警规则单元测试，证的正是 ADR 0024 说「证不了」的那一句，且满足那条筛子。**因此 ADR 0024 排除告警的核心理由不再成立，ADR 0030 第 5 条的触发线随之真正达成 —— 是「触发已到」，不是「政策越过」**（与票 64/66 性质完全不同，不许混为一谈）。

口径（ADR 0051 已立契，本票只执行）：

- **4 条规则，全部引用真实指标名**：

| 规则 | 表达式 | 该响的含义 |
|---|---|---|
| 依赖不齐 | `shoppilot_dependency_up == 0` | 向量 / 词法 / 语料三格有不 UP |
| 业务中台熔断打开 | `shoppilot_circuit_state == 1` | 熔断器已打开，业务调用被本地挡下 |
| 业务调用失败 | `rate(shoppilot_tool_timeout_total[5m]) + rate(shoppilot_tool_unavailable_total[5m]) > 0` | 超时或不可用真的发生了 |
| 降级率超阈 | `rate(shoppilot_fallback_total[5m]) / rate(shoppilot_requests_total[5m]) > 0.2` | 降级占请求的比例越线 |

- **每条规则都要有「该响」与「不该响」两侧断言**（这正是 ADR 0024 说证不了的那一句）。
- **引入方式：CI 新增一步，`curl` 固定版本 promtool + 打印并校验 sha256** —— 把外部依赖**局限在一步之内**、可 pin 可审；其余步骤仍 0 依赖。**这是本票的真实代价**（往刻意零依赖的 CI 子集里加一个需下载的二进制），写进了 ADR 0051，收口时不许含糊过去。**不用容器镜像**（免，但会把 docker pull 引进 0 依赖子集）、**不入库二进制**。
- **排位**：CI 现有步骤之后、**排在最后的 0 token 步**（它需要一个额外下载的二进制，失败时不该拖住已有的 0 依赖步骤）。**与票 64 同改 `ci-subset.yml`**，别并行改同一文件。
- **红线：不得声称这些规则在生产会响。** 本票证明的是**规则在该响的合成序列上确实响**、**不该响时确实不响**；不证明本机或任何环境挂了 Prometheus，也不证明告警会送达。
- **顺带换来的守卫**：规则引用真实指标名 → **谁改了指标名，告警测试当场红**。

- [x] 4 条规则文件（引用真实指标名）
- [x] `promtool test rules` 的合成序列测试：每条规则**两侧**（该响 / 不该响）都有断言
- [x] `promtool check rules` 通过
- [x] CI 新增一步：pinned 版本 promtool + sha256 校验 + 跑规则测试（0 token、离线）
- [x] 指标名改名会让本步转红（**已实测**：把 `shoppilot_tool_timeout_total` 改成一个不存在的名字 → 「该响」用例 `got:[]` 转红；还原即 `SUCCESS`）
- [x] 措辞守住「不声称生产会响」—— 本票**不在 README / `docs/EVIDENCE.md` 新增任何告警读数声明**；票 68 登记这项能力时按 ADR 0051 的红线措辞写

**Verify**
```bash
promtool check rules ops/alerts/shoppilot.rules.yml
promtool test rules ops/alerts/shoppilot.rules.test.yml
```

## Handoff notes

**关键决策**

- **4 条规则、每条两侧断言**（`ops/alerts/shoppilot.rules.yml` + `…rules.test.yml`）：依赖不齐（`shoppilot_dependency_up == 0`）、熔断打开（`shoppilot_circuit_state == 1`）、业务调用失败（`rate(tool_timeout)+rate(tool_unavailable) > 0`）、降级率超阈（`sum(rate(fallback))/sum(rate(requests)) > 0.2`）。
- **实测抓到一处会静默失效的写法（本票最有价值的一处）**：降级率最初写成 `rate(shoppilot_fallback_total[5m]) / rate(shoppilot_requests_total[5m])`，`promtool test rules` 当场判「该响」用例红（`got:[]`）。根因是 **PromQL 向量匹配按标签集**：`fallback` 带 `reason` 标签、`requests` 不带，两者默认**不匹配** → 相除得空结果，规则**永远不会响**而语法完全合法。改成两侧都 `sum()` 后转绿。**这条正是 ADR 0024 说「写了只能证语法」的活标本** —— 光看规则文件看不出它坏了。
- **指标名是这套规则的接口**（副作用即守卫）：规则引用真实指标名，谁改了名字，「该响」用例就会因为表达式取不到序列而红。这条不需要额外写一个「指标名存在性」检查器（那是第二份判据）。
- **CI 步排在最后**（ADR 0051 的顺序）：它是唯一一个需要下载外部二进制的步骤，失败时不该拖住前面那些 0 依赖步骤。
- **sha256 校验的口径（与本票面的字面略有出入，如实记）**：CI 步按发布方公布的 `sha256sums.txt` 校验，**没有**在仓库里硬编码一个 hash 常量。原因是本机到 GitHub 发布 CDN（`github.com`/`raw`/镜像站）全部不可达（`curl` 返回 000，只有 `api.github.com` 与 `example.com` 通），**我拿不到那个值**；按本仓家法「拿不到就写 `-`，不要猜一个值」，宁可用发布方的校验文件。若所有者要求硬编码 pin，值可以从 `sha256sums.txt` 抄一行补进 workflow。
- **红线**：README / `docs/EVIDENCE.md` 的措辞是「证明规则在该响的合成序列上确实响、不该响时不响」，**不声称生产会响、也不声称接了 Prometheus/Alertmanager**。

**验证落点（本机实跑）**

- `promtool 2.53.2`（本机 PATH 无 promtool；用本机一个已在运行的 Prometheus 容器里的同一版本二进制跑，只读用法，跑完已清理容器内临时文件）：
  - `promtool check rules shoppilot.rules.yml` → `SUCCESS: 4 rules found`
  - `promtool test rules shoppilot.rules.test.yml` → `SUCCESS`（8 个断言块：4 条规则 × 该响/不该响）
- **变异对照（守卫活着，已实测）**：把规则里 `shoppilot_tool_timeout_total` 改成一个不存在的名字 → 「该响」用例 `got:[]` 转红；还原即 `SUCCESS`。（做法是把规则文件复制到容器 `/tmp` 改，**没动仓库文件**；跑完已清容器临时文件。）
- CI 新增步骤已写进 `.github/workflows/ci-subset.yml`（**干净 runner 上未跑**，见下）。

**未达成（按实登记，不摘红）**

- **CI 那一步未在干净 runner 上实跑过**：本机网络到不了发布 CDN，无法本地复现 CI 的下载路径。脚本本身照 ubuntu-latest 的 shell 写（`set -euo pipefail` + `curl -fsSL` + `sha256sum -c` + `tar -xzf`），但**没跑过就不算绿**。
- **本机矩阵（`run-acceptance.ps1`）不含这一步**：矩阵是 PowerShell + 本机环境，而 promtool 是需要下载的外部二进制；是否把它接进本机矩阵留给票 68 决定。

**你需要能当场回答的三个追问**

1. *Q：为什么这条算「触发已到」而不是「政策越过」？* A：ADR 0024 排除告警的核心理由是「写了只能证语法、证不了该响时会不会响，正是这条判据要拦的自述」，而它说的「这条判据」是同一份 ADR 立的总筛子（0 token、不依赖一次性活体读数）。`promtool test rules` 用合成序列断言「该响时响、不该响时不响」，**证的正是那一句，且过得了那道筛子** —— 排除理由被技术路径推翻，不是被政策覆盖。它与票 64/66 的性质完全不同。
2. *Q：为什么标准写法要 `sum()`？* A：PromQL 的向量运算默认按标签集匹配。`shoppilot_fallback_total` 带 `reason`、`shoppilot_requests_total` 不带，直接相除两边标签不同 → 不匹配 → 结果为空 → 规则永不触发，而 `check rules` 照样 SUCCESS。本票实测踩到并被 promtool 的「该响」用例抓住。比值本来就该按总量算，`sum()` 是正解。
3. *Q：为什么不把 promtool 二进制提交进仓或改用容器镜像？* A：二进制入库是 ~100 MB 资产入仓、升级要走提交；容器镜像会把 `docker pull` 引进一个刻意零依赖的 CI 子集（其余步骤都不碰 Docker）。`curl` 固定版本 + 校验，把外部依赖**局限在一步内**、可 pin 可审 —— 这个代价写进了 ADR 0051。