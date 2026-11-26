# 票 232：退款内核与 Shopify 适配器转正（独立 reactor）

Round: 62（模块转正 / 生产依赖变更）
ADR: 0088（审批载体）、0089（终态读模型）、0090（买家术语）
Status: implemented（2026-11-26；独立 Verifier PASS，P0 无、P1 已关闭；
待所有者书面签收；AC8 BLOCKED，待 Shopify Partner dev store 凭据）
Claimed by: root　Blocked by: 无（AC8 另需凭据）

契约：[plans/232-commerce-kernel-promotion.md](../../plans/232-commerce-kernel-promotion.md)（2026-11-26 冻结）

## What to build

1. 把 `drafts/` 提升为仓库内独立 `kernel/` reactor：独立 parent、
   `0.1.0-SNAPSHOT`、不继承 `shoppilot-parent`、不依赖 Spring/网关/biz-mock。
2. contract 模块 30 条测试保持绿；TCK 从 `src/main` 移到 `src/test`，
   用 `maven-jar-plugin` 打 `test-jar`，适配器以 `test` scope +
   `<classifier>tests</classifier>` 消费。
3. Shopify 适配器 13 条 TCK 在假传输层保持绿；live smoke 默认跳过。
4. 修两条资金正确性缺口：
   - `shippingRefunded` 不再恒为 `false`，或明确拒绝 `refundShipping=true`；
   - `totalRefundedSet` 缺失时失败关闭，不得退化为 0。
   两条都要有「修复前红、修复后绿」的测试证据。
5. 新增 `kernel/scripts/check_coverage.py`，两个内核模块进门槛表；
   新增 `.github/workflows/kernel-ci.yml`，只跑内核 reactor。
6. 文档：`kernel/README.md`、`docs/CODE_MAP.md` 指针、映射文档路径同步；
   `drafts/` 只留指向 `kernel/` 的说明。

## 边界

- 不改根 `pom.xml`、根 `README.md`、`docs/STATUS.md`、`TUNING_PLAN.md`
  或根四模块的 878 条读数；根 `README.md` 仅允许 ADR 计数三处同步
  （契约修订 1，86 → 89）。
- 不改 `shoppilot-gateway` / `shoppilot-biz-mock` / `shoppilot-ticket` /
  `shoppilot-tool-api`。
- 不接 `AgentStateMachine` / `ToolDispatcher` / `BizMockClient`。
- 不改 `CONTEXT.md`、既有 ADR 结论、gold、阈值、判据或指标分母。
- 不删除、跳过、弱化测试；不为覆盖率写无断言用例。
- 不提交 `.env`、token、`logs/`、`target/`、截图。
- AC8 完成前不得宣称「已接入 Shopify / 支持真实店铺退款」。

## Verify

```powershell
.\mvnw.cmd -f kernel/pom.xml -B -ntp verify
python kernel/scripts/check_coverage.py
.\mvnw.cmd -B -ntp verify
python scripts/gen_status.py --check
python scripts/verify_doc_truth.py
python scripts/check_doc_links.py
git diff --check
git status --short
```

真实开发商店（AC8，阻塞）另跑 `docs/shopify-refund-contract-mapping.md` 第 8 节。

## Handoff notes

**实现落点（2026-11-26，分支 `codex/232-commerce-kernel-promotion`）**

- `drafts/` → 独立 `kernel/` reactor：parent `shoppilot-kernel-parent:0.1.0-SNAPSHOT`，
  不继承 `shoppilot-parent`，不依赖 Spring/网关/biz-mock。
- `shoppilot-commerce-contract`：30 条测试绿；TCK 从 `src/main` 移到 `src/test`，
  由 `maven-jar-plugin` 打 `test-jar`，只含 `com/shoppilot/commerce/tck/**`。
- `shoppilot-commerce-shopify`：15 条测试（13 TCK + 2 条资金安全）+ 1 条 live smoke
  默认跳过；运行时只依赖 contract jar + Jackson。
- 修掉两条资金缺口：`shippingRefunded` 由 `Order.totalRefundedShippingSet` 推导；
  退款金额缺失时 `INTERNAL` 失败关闭，不再退化为 0。
- 新增 `kernel/scripts/check_coverage.py`（contract 83.0 / shopify 55.0 门槛）
  与 `.github/workflows/kernel-ci.yml`（只在 `kernel/**` 变更时跑，无 secrets）。
- `drafts/` 只留指向 `kernel/` 的 README；`docs/CODE_MAP.md` 增加内核 reactor 指针。
- 根 `README.md` 只同步 ADR 计数（86 → 89，编号位 0090）；JVM/套件/验收步数不动。

**验证证据**

- 内核：`.\mvnw.cmd -f kernel/pom.xml -B -ntp verify` → BUILD SUCCESS；
  contract 30 / shopify 15 + 1 skipped。
- 覆盖率：`python kernel\scripts\check_coverage.py` → contract 84.53% / shopify 56.35%，
  两模块均 OK。
- TCK 制品：主 jar `jar tf` 无 `junit|assertj|tck`；tests jar 含
  `com/shoppilot/commerce/tck/CommerceGatewayContractTest.class`；
  `dependency:tree` 显示 contract 的 `test-jar:tests` 为 `test` scope。
- 根回归：`.\mvnw.cmd -B -ntp verify` → BUILD SUCCESS，五模块全绿；
  `gen_status.py --check` → STATUS ok；`verify_doc_truth.py` → 878 tests / 144 suites；
  `check_doc_links.py` → 123 文件 / 254 链接；`git diff --check` → 0。
- 变异对照（均已还原）：
  1. `shippingRefunded` 改回常量 `false` →
     `shippingRefundIsNotOfferedTwice` 红；
  2. 退款金额改回 `moneyOrZero` → `missingRefundTotalFailsClosed` 红。

**三个现场追问的回答**

1. **TCK `test-jar` 是否把 JUnit 带进运行时？** 没有。主 jar 无
   `junit/assertj/tck` 条目；JUnit/AssertJ 只在 contract 的 `test` scope，
   TCK 以 `test-jar` + `test` scope 给适配器。
2. **两条资金缺口有没有真红对照？** 有，见上面两条变异；还原后全绿。
3. **根 reactor 是否被碰？** 没有。根 `pom.xml`、`STATUS.md`、`TUNING_PLAN.md`、
   四模块源码与 878/144 读数均未变；README 只同步了 ADR 计数。

**AC8（阻塞，未执行）**

真实开发商店的 10 条验证仍未跑。需要所有者提供 Shopify Partner 免费开发商店的
`SHOPIFY_SHOP` 与 `SHOPIFY_TOKEN`（至少读订单；创建退款另需写权限）。
在 AC8 完成前，对外只能称「假传输层 TCK 通过」，不得称「已接入 Shopify」。

**独立 Verifier（2026-11-26）**

- 结论 **PASS**：AC1–AC7 全部通过，AC8 记 BLOCKED；P0 无。
- P1：票面曾写 shopify 覆盖率 56.75%，clean 实测为 56.35%。已整改：
  JaCoCo `prepare-agent` 设 `append=false`，票面同步为 56.35%；
  非 clean 重跑不再受旧 `jacoco.exec` 影响。
- 复核（2026-11-26）：不 clean 重跑仍为 contract 84.53% / shopify 56.35%，
  P1 关闭；最终结论 **PASS**，无需继续整改。
- 所有者书面签收尚未发生，所以本票保持 `implemented`，不是 `done`。
