# 31 — JVM 崩溃归因与峰值条件入证据

**What to build:** README 说「凌晨两次压测后网关凭空消失、没有 hs_err、归因未定」——而 14 份崩溃日志一直躺在仓库根。本票把它们读完，把「归因未定」改成有因结论；把头条吞吐数字的峰值步主机条件从「已知限制」提到数字旁边；给两个 JVM 的起栈脚本设显式堆上限，并明写历轮读数的供法。全票是证据与运维参数，不改任何判据与压测逻辑。

**Blocked by:** None — can start immediately

**Status:** implemented（README 归因表覆盖 14 份日志并回引具体文件名；三条机器判据通过；脚本语法检查通过；真实起栈生成 `-XX:MaxRAMPercentage=3` 后已清理；round14 共用门禁 `logs/acceptance-run-20260916-095520.log` 17/17、543s，审计 95 项全绿）

- [x] 读 14 份 `hs_err_pid*.log`（连同 replay 日志）出归因表：崩溃类型（native 内存 OOM）、时间簇、网关与业务 Mock 各自的份、崩溃瞬间系统空闲内存区间；表述以日志原文为据，引用日志文件名
- [x] README 技术债「归因未定」那条改有因结论；「没有 hs_err_pid 日志」的字样在仓内不得再出现
- [x] 三个 headline QPS 数字旁补峰值步主机条件（压测 env 产物里的空闲内存与 CPU 读数），引用对应 env-json 产物名，标注「登记当时状态，不作跨轮证据」
- [x] 业务 Mock 与网关的起栈脚本显式设 `-XX:MaxRAMPercentage`；看门狗抬堆的既有逻辑与新参数对齐，不留两套打架
- [x] README 运维段注一句：起栈形态自此带显式堆上限，历轮压测读数按当时状态原样供着，不承诺跨形态复现
- [x] 机器复跑判据三条：README 全文 grep 无「没有 hs_err」式字样；归因段至少引用一份日志文件名；起栈脚本含堆上限参数
- [x] 审计项数保持 95；判据、阈值、压测判定逻辑一字不动；零额度

**Verify:** 归因表逐行对日志原文抽查 -> 三条机器判据各跑一遍 -> 起栈脚本改动在本机目测一次启动参数生效（活体只登记当时状态）-> 门禁全绿。

## Handoff notes

**关键决策**

- 14 份日志按应用归属是 biz-mock 5、gateway 1、Maven/其他 Java 启动器 8；四簇崩溃瞬间系统空闲内存为 258-1216 MB。README 表逐簇列出 `hs_err_pid*.log` 文件名，并登记 `replay_pid68928.log`、`replay_pid45344.log`、`replay_pid42336.log`、`replay_pid26260.log` 与 0 字节的 `replay_pid34196.log`。
- JVM 堆上限改成 `-XX:MaxRAMPercentage`：网关 3%（16 G 机约 491 MB），业务 Mock 2%（约 327 MB）；看门狗重启业务 Mock 时统一抬到 4%。POM 把默认值暴露成 `shoppilot.jvm.max-ram-percentage`，两个 `spring-boot:run` 回退路径用同名用户属性覆盖，不再保留 `-Xmx` 第二套口径；实跑 `=5` 已回读 fork JVM 为 `-XX:MaxRAMPercentage=5`。
- 三个头条数字的峰值条件已挪到数字旁：1013 QPS 引用 `env-l1-perf-20260908-231233-final.json`（CPU 94%、最低空闲 0.0 GB），1141 QPS 引用 `env-l2-perf-20260909-000535-l2.json`（CPU 93%、最低空闲 0.0 GB），482 QPS 引用 `env-l1-perf,no-ollama-20260909-123509-noollama.json`（CPU 93%、最低空闲 0.0 GB）；均注明是登记当时状态。
- 旧起栈形态与历轮读数不做追认：README 明写此后带显式百分比上限，历史 QPS 按当时 `-Xmx512m` / `-Xmx256m` 形态原样供着，不承诺跨形态复现。
- `docs/interview-qa.md` 中旧的“无 hs_err、外部终止”答案已改成 native OOM 结论与首手日志指针；`scripts/clean_clone_check.ps1` 里同源的“无日志消失”注释也改成 native OOM + `hs_err`。

**你需要能当场回答的三个追问**

1. "为什么不是按日志里的 `-Xmx` 继续调，而是改成百分比？" —— 百分比随宿主机内存伸缩，仍保留显式上限；同时把网关与业务 Mock 的默认口径写进同一参数，避免脚本与看门狗各抬各的。
2. "为什么 1013/1141 不直接引用 `hs_err`？" —— 它们是压测 headline 的数字条件，证据在 env JSON；崩溃日志只负责回答进程为何退出，两组读数不能混成一条因果。
3. "为什么这次不改判据或重跑压测？" —— 本票只做证据与运维参数收口；历史读数是当时形态的一次性实测，重跑会换掉被测形态，反而破坏跨轮可比性。
