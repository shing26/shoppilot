# ticket 20 第三轮收尾计划（2026-09-11）

起点 `HEAD=11a12ac`（未 push）。本票主体已实现并过两轮双轴审查，本轮**只收尾不加功能**：
把 `11a12ac` 之后失效的验收落点重新钉回机器上，再走一轮审查闭环。

## 铁律（任何一步与之冲突即停）

1. 不许为了让 `93.3%` / `95.6%` 变高而改测试或换口径；判据、阈值、`EVAL DONE` 一字不动。
2. 已拍板 6 条决策不重开（重标不合并／放开唯一资格=对偶矛盾／列表 gold 与参数修复绑定／0-token 路线／README 两套读数并列且承诺项仍写「未达成」／`--selfcheck` 内嵌前置断言不新增门禁第 18 步）。
3. 全程零模型额度：`tokensUsedToday` 本轮前后差值必须为 0。
4. 文档里每一句自述，落点必须能在机器上重跑出来；跑不出来就删掉那句话，不是改判据。

## 步骤与验收标准

计数口径：共 **9 步**（P0-P8），每步一条「通过判据」9 条，另有 17 条可逐条打勾的动作项，合计 26 条可验项。

### P0 前置体检（0 token）

- 工作树 `git status --porcelain` 为空；`HEAD=11a12ac`；`origin/main..HEAD` 只含这一笔。
- `C:` 与 `D:` 余量可读；`TMP`/`TEMP` 一律指到 `D:\tmp`（上一轮那条环境例外的处置动作，即使磁盘已恢复也照做，避免把"这次没红"当成"不需要防呆"）。
- **通过判据**：以上四条各打印一条实测值，缺一不进 P1。
- 状态：**Pass**（18:05）
  - 体检时工作树空、`HEAD=11a12ac`，`origin/main..HEAD` 只含这一笔（`11a12ac` 未 push，属实）。随后本计划文件单独成一笔 `5278b83`，
    为的是让门禁在干净工作树上开跑；`5278b83` 相对 `11a12ac` 只多这一个 `.md`，被门禁验的代码与 `11a12ac` 逐字节相同。
  - `C:` 余量 **15.06 GB**、`D:` 余量 **21.25 GB**；`C:\Users\Shing\AppData\Local\Temp\wsl-crashes` **已不存在**——上一轮那 10 个 WSL core dump 已被清掉，那条环境例外的成因消失。`TMP`/`TEMP` 仍一律指 `D:\tmp`，按的正是本票"不把这次没红当成不需要防呆"那句。
  - `JAVA_HOME` 已是 `E:\java\jdk21`；开跑前 `8082/8091/16379/16333/19200` 五个端口全不通、`shoppilot-*` 容器不在 `docker ps` 里（栈是停的，由 `stack` 步自己拉起）；`nexus-ollama` 在跑，`eval` 步的 local 模式要用它。

### P1 离线取证四件套复跑（0 token）

- `python scripts/verify_eval_judge.py` -> `40/40`，逐桶相加 = 40。
- `python scripts/run_tool_eval.py --selfcheck` -> `SCORER SELFCHECK ok=16`。
- `python scripts/build_eval_set.py` -> 退出码 0、无 `FAIL`、无新增 `WARN`；连跑两次 `eval/tool-cases.jsonl` 字节级相同。
- `--rescore`（5 份 budgetfix 在前、`075747-dev` 在后 + `--rescore-expected-diff` 那 4 个 id）-> 退出码 0、差异集合恰好 `{ACT-ORD-09,11,16,17}`、聚合 `168/180=93.3% -> 172/180=95.6%`、产物与 `tool-eval-20260911-042142-rescore.csv` **字节级相同**。
- **通过判据**：四条全绿且第 4 条的 sha256 与既有产物一致；不一致即视为口径漂移，停下来查，不改文档数字。
- 状态：**Pass**（18:11）
  - `verify_eval_judge.py` 合计 **40/40 通过**、退出码 0；`--selfcheck` 打印 `SCORER SELFCHECK ok=16`；`build_eval_set.py` 退出码 0、无 `FAIL`、无新增 `WARN`、`对抗样本 72/180 = 40.0%`、十个意图各 18 条。
  - `--rescore` 退出码 0：`ACTION_ORDER 72.2% -> 94.4%`、聚合 `168/180 = 93.3% -> 172/180 = 95.6%`、判据变动 `['ACT-ORD-09','ACT-ORD-11','ACT-ORD-16','ACT-ORD-17']`、未达 95% 的行 5。
  - 新产物 `tool-eval-20260911-181037-rescore.csv` 与被引用的 `...-042142-rescore.csv` **sha256 逐字节相同**（`BE53815C…5D85`），已删；`git status --porcelain` 复空。
  - **顺手抓到一处文档虚高**：ticket 20 验收第 17 条记的 `eval/tool-cases.jsonl` sha256 前 12 位仍是 `5e315190c46c`，当前实际是 `a8c88525fa7c`——第二轮给 8 条越权样本补满串号标记后生成物就变了，那一格没跟着改。P3 一并修（改的是自述，不是判据）。

### P2 整轮 17 步门禁重跑（0 token，约 9 分钟）

- 命令：`pwsh -NoProfile -File scripts/run-acceptance.ps1`（`profile=local`，要求开跑时工作树 clean）。
- **通过判据**：`logs/acceptance-run-<新ts>.log` 17 步 exit 全 0；头两行的 `commit=` 等于当时 HEAD、`开跑时工作树=clean`；`logs/acceptance/eval.log` 首行 `SCORER SELFCHECK ok=16`、末行 `EVAL DONE`；`build`/`unit` 两步 surefire 合计 104；新冒烟 summary 与 `123109` 那一轮逐格一致。
- 若 `polarity` exit 3：按 README「假红」一节既有口径处置——栈不动单跑 `scripts/verify-polarity.ps1`，exit 0 才算一次性、不算防线失效，且这一轮**不得**当落点写进 README。
- 状态：**第一次尝试卡在 `stack` 步，已定位并修好起栈脚本，待重跑**
  - 18:11:38 起的第一次跑：`syntax`/`stop`/`build`/`unit`/`report` 五步绿（`build` 的 `BUILD SUCCESS` 落在 18:13:48，用例数与上一轮同），
    18:13:49 进 `stack`，此后 20 分钟没有第六步。18:36 手动终止。
  - 卡点在 `scripts/up.ps1` 的 `[2/6] 本地模型`：`logs/acceptance/stack.log` 停在 18:14:04，最后四行是被拉起的 Ollama 自己打的
    `INFO source=...msg="starting Ollama"`；`up.ps1` 那个 pwsh（25976）活着、19 线程、CPU 冻结在 5.546875 不再增长、**没有任何子进程**，
    而 `8082/8091` 始终不通、`java` 进程数 0。也就是说它没在跑，是冻在 `[2/6]` 里第一句 `& ollama list` 上。
  - 成因：本机 Ollama 服务当时**没在跑**（`docker ps` 里只有 `nexus-ollama` 容器，那是另一回事）。`ollama list` 发现服务不在就把 Ollama 的应用与服务进程拉起来，
    那几个进程继承了本步骤的重定向输出句柄，PowerShell 要等句柄 EOF 才认为外部命令结束，于是永远等不到——
    它们的日志出现在 `stack.log` 里就是句柄被继承的实证。更难看的是 `up.ps1:58` 那句 fail-fast
    （`Ollama 未监听 11434，先跑 ollama serve`）在 `ollama list` 之后，**这一轮它根本到不了**：脚本自己把"告诉你怎么修"的那行代码挡在了挂死后面。
  - 修法（`scripts/up.ps1`，只动 `[2/6]` 一段）：把端口检查提到问 CLI 之前，端口没在听就用仓库既有的脱离式启动法
    `Start-ShoppilotService`（`Win32_Process.Create`，日志写自己的 `logs/ollama.out`）拉起 `ollama serve`，`Wait-For` 到 11434 之后再 `list`/`pull`。
    不新写启动机制，用现成的那个函数——`lib-launch.ps1` 第 7-10 行早就写明它存在的理由是"服务要真正脱离启动它的终端"。
  - 机制反证（同机实测）：用 `Start-ShoppilotService` 起一个活 60 秒的 `powershell Start-Sleep 60`，2 秒后查它 `alive=True`、父进程 `WmiPrvSE.exe`，
    而外层 pwsh（stdout 重定向到文件）**3.5 秒就退出了**——这条路径的子孙不持有父进程的重定向句柄。
    仓库自己的历史也是同一条证据：每一轮 `stack` 步都用它拉起网关与 biz-mock，那两个进程跑完这一步之后还活着，这一步却照样在 70 秒内收口。
  - 顺带记两条环境事实：`C:` 余量 15.06 GB、`wsl-crashes` 目录已被清掉（上一轮那条磁盘例外成因消失，但 `TMP`/`TEMP` 仍指 `D:\tmp`）；
    第一次跑的 `build`/`unit` 两步在磁盘恢复后确实不再红，这与上一轮的记录不冲突——上一轮红的是磁盘，本轮红的是起栈脚本。
  - 待办：重跑整轮，判据不变。

### P3 落点更新（只改引用，不改结论）

- README 矩阵块：header 行的时段 / `HEAD=`、17 步各自耗时、`ok=16` 那格。
- README 落点 prose：`logs/acceptance-run-<新ts>.log`、`commit=`、`开始/结束/总耗时`、`stack NNs + demo NNs`。
- README 索引行（第 16 条动作那一格）：冒烟产物名换成新那一份。
- ticket 20：`Status` 行的 log 文件名、验收结果第 11 与 24 条、追加「第三轮收尾」取证段。
- **通过判据**：README 与 ticket 20 里出现的每一个 `acceptance-run-*` / `*-smoke*` 文件名都能在磁盘上找到；`git diff` 不含任何阈值、判据、gold 改动；承诺项那一格结论仍是「未达成」。
- 状态：pending

### P4 产物清理与引用可解析

- 只保留被引用的重算产物与冒烟产物；未被任何文档引用的旧冒烟产物删除（删除前先确认它不是唯一一份跨轮对照证据）。
- **通过判据**：`git ls-files eval/results` 里每个 20260911 文件都被 README 或某张 ticket 引用；每条引用都指向在库文件。
- 状态：pending

### P5 问答库重生成（0 token）

- `python scripts/collect_interview_questions.py` -> 问数 >= 83、覆盖 20/20、无「缺收尾记录的 ticket」、头部计数与正文 **Q 条目数一致。
- **通过判据**：四条同时成立；`docs/interview-qa.md` 的 diff 只含预期新增。
- 状态：pending

### P6 commit + push

- 落点更新与产物变更合成一笔；提交信息说明「第三轮：门禁换到 `11a12ac` 之后那一轮」。
- **通过判据**：`git status` clean；`git log origin/main..HEAD` 为空；push 后远端 HEAD 与本地一致。
- 状态：pending

### P7 第三轮双轴 code-review（fixed point `e64dc66`）

- Standards + Spec 两条轴并行子代理，逐条核实：成立则闭环，不成立则把证据写进 ticket 20 的 Handoff notes，免得下一轮重做。
- **通过判据**：所有成立项修完并重跑受影响的取证命令；不成立项逐条留反证；若改了 `scripts/` 或 `eval/` 则回到 P2 重跑门禁。
- 状态：pending

### P8 目标收口

- **通过判据**：P0-P7 全绿、`tokensUsedToday` 差值 0、证据链可一键复现，才 `update_goal complete`。
- 状态：pending

## 本轮明确不做

合并工具；改任何阈值或判据；花钱重跑 dev；新增门禁第 18 步；改 TTFT 口径（交接文档第 2 件，另走 ADR 0022）；把 `mustNotLeak` 降级成注释。
