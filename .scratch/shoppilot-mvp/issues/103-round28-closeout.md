# 103 round28 收口

**Status:** implemented（2026-10-06）

## What to build

round28 收口：B1「数据活过一次重启」全部落地后的登记与换代。

- `docs/EVIDENCE.md`：新增「B1 持久化」节（活体读数、命令、证据边界）。
- `docs/CODE_MAP.md`：数据层真相源更新（每服务两套迁移集 + 持久档 profile 的位置）。
- `docs/adr/0061`（开轮时已立）补 §2b 的实验定案。
- `README.md`：架构表加数据层行、已知限制更新（「业务数据在内存」的表述按双档改写）。
- tracker（`.scratch/shoppilot-mvp/README.md`）：round28 行、票 100-103 索引、交付层读数换代。
- `program-a-to-b-upgrade.md` 加换代指针：B1 已收口，B2-B5 的触发条件原样。

## Blocked by

[100](100-persistent-postgres-profile.md)、[101](101-seed-init-demo-split.md)、[102](102-compose-persistence-and-live-restart.md)。

## 口径

- 一条判据都没动：gold 180 / 阈值 / `judge()` / 降级枚举口径 / verify-\*.ps1 断言全零改动。
- 交付层读数换代以**本次实测**为准（surefire XML 汇总），不沿用旧数。
- `registered-debt.md` 本轮不新增条目；ADR 0061 §2b 的「text 落点」触发条件写在该文件里。

## 验收

- 全仓 `mvnw verify` 绿；`git diff --check` / `git status --short` 干净（无本机日志、无崩溃日志混入）。
- tracker 状态位与票面一致（票 21/72 的教训：两边必须同步改）。

## Verify

```powershell
git diff --check; git status --short
.\mvnw.cmd -B -ntp verify
```

## Handoff notes

### 交付层读数换代（2026-10-06 实测）

- JVM：**`10 + 59 + 345 + 40 = 454`**（tool-api / biz-mock / gateway / ticket；本轮 **+3**：
  biz-mock +2 = SeedSegmentationTest + PostgresMigrationParityTest，ticket +1 = PostgresMigrationParityTest；
  gateway 345 与冻结读数一致、零改动）。**一个自查**：过程中曾把 gateway 数成 347——那是直接
  sum surefire XML 时混进了陈旧报告文件；**以模块汇总行「Tests run:」为准**，账面已更正。
- ADR 60 编号 − 0022 预留 = **60 篇**（新增 0061）；票 **103** 张（新增 100-103）。
- CI 九步未动（本轮零新增门禁；ParityTest 与 SeedSegmentationTest 在既有 verify 步内跑）。

### 本轮三条最值得记的

1. **「服务 UP」不等于「连的是你想要的库」**：shell 优先级坑让 ticket 在 H2 上答 readiness，
   活体断言必须核 profile 行与连接串。
2. **三种 LOB 映射各瘸一条腿**：`@Lob`/`LONGVARCHAR`/`LONG32VARCHAR` 的实测矩阵钉死了
   「text 落点需要按方言注册自定义 JdbcType」——登记不执行，不是不知道。
3. **读数要认准口径**：surefire 的模块汇总行与 XML 文件 sum 可能不一致（陈旧报告文件不清理的话），
   gateway 的 345 一度被数成 347——451 时代就登记过「声明数 vs 执行数」，这次是它的变体。

### 现场追问

1. 为什么 B1 做成「双档」而不是把默认档换掉？——A 身份红线（演示可复现性 + 判据面零触碰），
   见 ADR 0061 §1。
2. gateway 的 +2 从哪来？——不知道，登记待查；本轮对网关零改动（git 可证）。
3. 下一格是什么？——B2（凭据与部署：真库连接串生产态、备份/恢复演练——大对象会在这里第一次露面）。
