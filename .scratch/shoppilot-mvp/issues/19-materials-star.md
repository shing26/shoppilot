# 19 — 材料固化：README、演示脚本与 STAR 话术

**What to build:** 一个面试官十分钟内能跑起来、且读完就知道每个数字怎么来的仓库首页。

**Blocked by:** 16 — Tool Calling 标注评测集；17 — 语义缓存阈值标定实验；18 — 大促压测

**Status:** ready-for-agent

**Verify:** 让一个没参与过的人只照 README 操作 -> 十分钟内起栈并跑通三条演示；抽查指标表任意一行，都能指到一个数据来源文件。

- [x] README 顶部：一条命令启动（含 `ollama pull bge-m3` 等前置）、一段 30 秒演示路径、一张架构图
- [x] 指标表逐条标注测量口径与数据来源文件，吞吐类注明"衡量网关编排层，不含模型推理"（ADR 0001）
- [x] 已知限制章节照 `PLAN.md` 写，不藏：H2 写密集瓶颈、跨实例合并仅单实例验证、身份提供方 mock、政策语料为生成数据、ES 够用级与不做 rerank 是判断而非未完成
- [x] 演示脚本三条：串号防线（A 店 token 查 B 店订单）、降级转人工（`failRate=1.0` 现场注入）、缓存拦截（同一问题二次提问看 `meta.cacheHit`）
- [x] STAR 话术按"矛盾-决策-数据"组织，每条都指向一个 ADR，而不是罗列技术名词
- [x] 把本轮 grilling 抓出的六个设计矛盾写成"我考虑过但否决的方案"一节：缓存前置串号、QPS 与 embedding 吞吐、同进程无法演示降级、EventSource 不支持 POST、租户未定义、裸 Header 越权
- [x] 收集 18 个 ticket 的 Handoff notes 里"三个追问"，汇总成面试问答清单，逐条自己答一遍

## Handoff notes

**关键决策**

1. README 的顺序按"跑起来 → 看得见 → 数字有出处 → 限制与否决项"排，不按模块排。十分钟读法是：快速开始（一条命令）→ 三条演示 → 架构与请求主链路 → 指标与口径 → 验收对照 → 已知限制 → STAR。
2. 指标表逐行带"口径 + 数据来源文件名"，吞吐类统一标注"衡量网关编排层，不含模型推理"（ADR 0001）。任务书两个数字没有照抄：成本下降按实测 62.4% 写并给三臂分解，拦截率按 74%（L1 主导）与 78%（80% 热点塑形）两个口径分开写。
3. 三条演示选"能证伪"的而不是"好看"的：串号防线（A 店 token 查 B 店订单）、`failRate=1.0` 现场注入降级、同一问题二次提问看 `meta.cacheHit`。每条都是"你注入故障、你看到防御"。
4. 面试问答清单由脚本生成（`scripts/collect_interview_questions.py`），不手抄。手抄会在复制过程中悄悄改掉措辞，而面试里最重要的就是我当场说的话和当时记的话是同一份；缺收尾记录的 ticket 会在清单末尾显式列名，不藏。
5. "考虑过但否决的方案"写了 10 条（票面要 6 条），多出的 4 条是实施中真发生的方向纠正：embedding 冷缓存踩踏、"降到 0.90 换召回"、接厂商 SDK、Redis 不可用时拒绝写操作。
6. `up.ps1` / `demo.ps1` 按"没参与过的人只照 README 操作"写：中间件 → 入库 → 两个服务 → 冒烟，脚本内按依赖等 health 端点，端口全部走 `shoppilot.*` 段避开本机其他项目。
7. 可复现性那条承诺不靠"我这台跑过"充数：加 `scripts/clean_clone_check.ps1`，`git clone` 出 HEAD、在克隆目录里照 README 起栈（空数据卷，走完整入库）再跑三条演示，结论与全量输出落 `logs/clean-clone-check-*.log`。它第一晚就抓到三件事，其中两件是检查器自己的：`container_name` 全局唯一让第二个检出撞死在起栈第一步（见 ticket 01 决策 8）；`up.ps1`/`demo.ps1` 的成功行是 `Write-Host` 打的，走 information 流，而 `Run-Step` 只并 `2>&1`，于是"栈已就绪 + 三条演示全过"被判成 FAIL——量具先修（`*>&1`）再谈结论，这和 ticket 19 的 TTFT 先修量具是同一条纪律；剩下那件是真前提：克隆起来的第二套全栈要 1.5 GB 起步，机上空闲 1.5 GB 时 `mvn` 的 JVM 会被弄死、`[4/6]` 找不到 fat jar 退回 `mvn spring-boot:run`、biz-mock 300 s 起不来，所以前置检查打印空闲内存、`up` 允许重试一次（每步可重入，重试不是把红洗成绿，日志里两次尝试都在）。

8. 门禁自己也要留证据：`run-acceptance.ps1` 现在把矩阵写成 `logs/acceptance-run-<时间戳>.log`（拿 `$results` 生成，不回抓 `Write-Host`）。起因是 run2..run8 那几份日志全靠人手工 Tee，run9 漏了，于是"16 步全绿"在机器上找不到落点。同一轮还揪出两个门禁毛病：`verify-plan-actions.ps1` 里重启 biz-mock 后那一等仍是 180 s，而 `up.ps1` 里同一个等待早就因为同样的资源争抢提到 300 s——**同一个常量写两份，改就只改对一半**；以及这一步失败之后健康门立刻跑 `up.ps1 -SkipIngest`，用 `>` 重开同一个 `bizmock.out`，把"为什么没起来"的现场覆盖掉。现在等待失败会先打印端口在不在听、launcher 进程活不活、日志最后 8 行再返回失败。run10 那一次到底是慢还是被弄死不假装归因（现场已毁），run11 同一等 80 s 全绿。

**需要能当场回答的三个追问**

1. "README 上的数字你自己当场能复现吗？" —— 每个数字旁边就是文件名，抽查任意一行能落到 `loadtest/results/` 或 `eval/results/` 的具体 CSV/JSON，同批 `-meta.json` 记了 git commit、容器内存、空闲内存。复现不到同一量级我会先说不达标，而不是先解释。
2. "任务书写 80% 拦截率，你为什么写 74%？" —— 口径冲突不是没做完。74% 是 L1 主导曲线的总拦截率，78% 是 80% 热点塑形口径，两者分母都是准入数；要写 80% 就得把拦截率记到语义缓存头上，而 ticket 17 实测那一层在 0.95 工作点召回为 0。
3. "已知限制里那条进程静默消失，下一步怎么查？" —— 先分诊"是 OS 杀的还是 JVM 自己崩的"：没有 `hs_err_pid*.log`、没有 WER 事件、stderr 为空，这三条同时指向外部终止。下一步是 `-XX:+HeapDumpOnOutOfMemoryError` + WER 本地 dump + `jcmd` 定时采样，并把发压端挪到另一台机器排除同机争抢（本机 16GB、阶梯谷底 free 0.0GB）。在换机复现之前不动业务代码。

**复现**

```
pwsh -File scripts/down.ps1
pwsh -File scripts/up.ps1        # 一条命令起中间件 + 入库 + 两个服务 + 冒烟
pwsh -File scripts/demo.ps1      # 三条演示
pwsh -File scripts/clean_clone_check.ps1 -Teardown   # 可复现性：克隆 HEAD 起栈 + 三条演示，结论落 logs/
python scripts/collect_interview_questions.py
```

`docs/interview-qa.md` 与 `docs/loadtest-report.md` 都是生成物，改内容改 ticket 或原始 CSV，不直接编辑产物。
