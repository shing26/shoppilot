# 44 — JaCoCo 覆盖率棘轮（报告进 verify + 0 token 门禁脚本）

**What to build:** 给全仓接上覆盖率：`jacoco-maven-plugin` 的 `prepare-agent` + `report` 绑到 `verify`，产出各模块 `jacoco.xml`；棘轮由 0 token 的 stdlib 脚本 `scripts/check_coverage.py` 读产物按模块分别设闸，并作为 CI 第 4 个 0 token step。

**Blocked by:** None（只碰根 `pom.xml`、CI workflow 与 `scripts/`，与票 42/43 的文件面不重叠）。

**Status:** implemented（2026-09-21）。

口径（ADR 0041 已定，本票只执行）：

- **按模块分别设闸**，不设聚合门槛：gateway 249 条与 tool-api 3 条的量级差太大，聚合门槛会让量小的模块的回归被掩盖。
- **LINE 设闸、BRANCH 只报不设闸**。
- **门槛 = 首次实测值向下取整再留 1pp 余量，先测后定，不猜数字、不设高门槛。**

- [x] 根 `pom.xml` 声明 `jacoco-maven-plugin`（`0.8.12`，按根 pom 既有风格显式声明），`prepare-agent` + `report` 绑 `verify`；声明在 `<build><plugins>` 而不是 `pluginManagement`，三模块一律继承
- [x] `scripts/check_coverage.py`：读各模块 `jacoco.xml`，按模块比较 LINE 覆盖率与门槛，打印实测值/门槛，exit 0/1；`--report` 只打印不判定
- [x] 棘轮常数 = 首次实测值（向下取整留 1pp），写进脚本并登记 EVIDENCE
- [x] 变异对照：门槛临时上调到超过实测值 → 脚本 exit 1；恢复 → exit 0
- [x] `.github/workflows/ci-subset.yml` 新增第 4 个 0 token step
- [x] `docs/EVIDENCE.md` 登记覆盖率数字与数法
- [x] round15 spec 的 Out of Scope「覆盖率」行加换代指针，记录本次覆盖及理由

**Verify:**

```powershell
.\mvnw.cmd -B -ntp verify
python scripts/check_coverage.py
```

预期：全量 `3 + 21 + 249 = 273` 绿；脚本 exit 0 并打印按模块的实测值与门槛。

## Handoff notes

**关键决策**

- **报告进构建、棘轮归脚本**，没有用 `jacoco:check` 直接绑 `verify`。理由是本仓既有的 0 token 门禁全是这个形状（`verify_eval_judge.py` / `run_tool_eval.py --rescore` / `eval_suites.py`），脚本能把实测值与门槛一起打出来供产物引用，`jacoco:check` 只给一句失败信息。代价是多一个文件，换来可引用的数字。
- **门槛 = floor(实测) − 1.0**，不是实测值本身。1pp 是**抖动余量，不是目标值**——本仓已有「恒红的健康检查只会教人忽略红色」这笔账，棘轮若紧贴实测值，任何一次合法的防御性分支都会让它变红，最后会被当成噪声忽略。设成当前值就已经拿到「防退化守卫」要件。
- **按模块分别设闸**是本票唯一一处偏离「一个总门槛」的取舍。gateway 55.37% 与 tool-api 41.73% 差 13.6pp，聚合门槛会让 tool-api 的整段回归被 gateway 的体量盖住；而本轮动的正是 biz-mock。
- 覆盖率进 CI 是**范围边界的更正而不是判据改动**：round15 spec 排除覆盖率的原始理由是「子集门禁不依赖外部服务」，而 JaCoCo 在进程内跑，那条理由对覆盖率本来就不成立。round15 spec 那一行按仓库惯例加了换代指针，**没有改写原文**。

**验证落点**

- 全量：`.\mvnw.cmd -B -ntp verify` → `3 + 21 + 249 = 273` 绿，三模块各自产出 `target/site/jacoco/jacoco.xml`。
- 棘轮：`python scripts/check_coverage.py` → exit 0。
- **干净 runner**：push 后 `ci-subset` run `35538377810`（`edbd3b1`，92 s）**五步一次全过**，其中 `Coverage ratchet (0-token)` 步是该脚本在 Linux 上的首次运行。这是本票第 5 条验收项的落点。
- 变异对照：把 gateway 门槛从 54.0 临时改成 56.0（实测 55.37%）→ `COVERAGE FAIL` 且 exit 1，输出 `shoppilot-gateway: LINE 55.37% < 门槛 56.00%`；恢复后 exit 0。
- 缺产物路径不是「跳过」而是「没验证」：找不到 `jacoco.xml` 时脚本判红并提示先跑 `mvnw verify`——静默放过等于把门禁变成摆设。

**首次实测（2026-09-21 基线，round18）**

| 模块 | LINE | BRANCH（只报） | 门槛 |
|---|---|---|---|
| shoppilot-gateway | 55.37%（2066/3731） | 50.29%（769/1529） | 54.0 |
| shoppilot-biz-mock | 77.49%（513/662） | 69.46%（116/167） | 76.0 |
| shoppilot-tool-api | 41.73%（58/139） | 15.91%（14/88） | 40.0 |

**你需要能当场回答的三个追问**

1. "为什么门槛比实测低 1pp 而不是正好等于实测？" —— 那 1pp 是抖动余量，不是目标值。棘轮要防的是「覆盖率掉下来没人发现」，不是「任何一次新增分支都必须同时补测」。贴着实测值设闸会让合法的防御性分支当场变红，然后这条红就被学会了忽略——本仓在 Qdrant 健康检查上认过这笔账。
2. "为什么不设一个像样的目标值（比如 70%）？" —— 设成当前值就已经拿到评分体系要的「防退化守卫」要件。高门槛会制造与功能无关的补测压力，并且诱导未来为过闸而写无断言用例——那正好把覆盖率这个指标的意义毁掉。
3. "覆盖率为什么能进 CI，round15 不是说 Out of Scope 吗？" —— round15 排除覆盖率的理由是「子集门禁不依赖外部服务」，JaCoCo 在进程内跑，那条理由对它不成立。这是一次范围边界的更正，已在该 spec 那一行留换代指针，其余 Out of Scope 项与全部门禁判据一字未动。
