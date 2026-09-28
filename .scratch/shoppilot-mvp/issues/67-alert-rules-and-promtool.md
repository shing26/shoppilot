# 67 — 告警最小集 + `promtool test rules`

**What to build:** 让「告警规则」在本仓第一次**可被机器证明**。今天仓库里没有任何 `*.rules.yml`/`*alert*`/`*prometheus*`。本票落 4 条最小规则（全部引用真实指标名）+ `promtool test rules` 的合成序列测试（**该响时响、不该响时不响**）+ CI 一步。

**Blocked by:** None（可立即开始）。

**Status:** ready-for-agent

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

- [ ] 4 条规则文件（引用真实指标名）
- [ ] `promtool test rules` 的合成序列测试：每条规则**两侧**（该响 / 不该响）都有断言
- [ ] `promtool check rules` 通过
- [ ] CI 新增一步：`curl` 固定版本 promtool + sha256 校验 + 跑规则测试（0 token、离线）
- [ ] 指标名改名会让本步转红（用一个改名的临时实验证明这条守卫活着，然后还原）
- [ ] README / `docs/EVIDENCE.md` 措辞守住「不声称生产会响」

**Verify**
```bash
promtool check rules <rules-file>
promtool test rules <test-file>
```