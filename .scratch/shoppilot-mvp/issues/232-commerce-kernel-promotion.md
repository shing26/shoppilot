# 票 232：退款内核与 Shopify 适配器转正（独立 reactor）

Round: 62（模块转正 / 生产依赖变更）
ADR: 0088（审批载体）、0089（终态读模型）、0090（买家术语）
Status: done（2026-11-27；AC8 已执行；独立 Verifier PASS `87ae5cb`；所有者书面签收）
Claimed by: root　Blocked by: 无
实现提交: `5ee7e02`（2026-11-26 模块转正）、`40a5357`（2026-11-27 AC8 资金缺口修复）、
`e7004e6`（2026-11-27 退空后同键重放修复）

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

- 提交：`5ee7e02 feat(round62): 退款内核与 Shopify 适配器转正为独立 reactor`；
  提交内容与独立 Verifier 复验的工作树一致，提交后 `git status --short` 为空。
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

**AC8 真实开发商店（2026-11-27，已执行；待独立 Verifier 复验）**

- 环境：Shopify Partner 免费开发商店 `shoppilot-kernel-dev.myshopify.com`，
  Admin GraphQL `2026-10`；App scopes
  `read_orders, write_orders, read_order_edits, write_order_edits, read_all_orders`。
- **关键发现（P0，已修，提交 `40a5357`）**：`refundCreate` 不传 `transactions`
  时，Shopify 创建退款单并消耗 `refundableQuantity`，但
  `totalRefundedSet = 0`、`transactions = []`，订单仍是 `PAID`。`#1001`
  （manual）、`#1002`/`#1006`（Bogus SALE/CAPTURE）均复现。
- 修复：适配器先读订单 `CAPTURE`（优先）或 `SALE` 交易，显式提交
  `transactions: [{orderId, kind: REFUND, gateway, amount, parentId}]`；
  无可退支付交易时 `STATE_NOT_ALLOWED`；回执金额取报价金额，
  `totalRefundedSet` 缺失仍 `INTERNAL`，非零但与报价不一致也 `INTERNAL`。
- 实测：
  - `#1007`：24.95 USD 显式交易退款，`REFUND/SUCCESS`，订单 `REFUNDED`。
  - `#1008`：10 + 10 两次部分退款，`PARTIALLY_REFUNDED` → `REFUNDED`；
    同键同参返回同一 refund id；同键改 note 返回
    `Idempotency key has been used with a different request payload.`。
  - `#1010`：适配器 live 测试，首退 10 USD `SETTLED`、同键重放同 id、
    同键改参 `INVALID_REQUEST`、末次 10 USD `SETTLED`，订单 `REFUNDED`。
  - `#1012`：行项目 10 + 运费 5，退款总额 15、`refundShippingLines` 5，
    订单 `REFUNDED`。
  - `#1013`：manual `SALE` + 显式 `REFUND` 10 USD，订单 `REFUNDED`（仅记账）；
    `CANCEL` 缺 `locationId` 被拒，`NO_RESTOCK` 通过。
  - `#1015`：AUTHORIZATION → CAPTURE 20 USD、2 件可退；live 测试首退/末退各
    10 USD `SETTLED`，同键重放同 id、同键改参 `INVALID_REQUEST`；订单
    `REFUNDED`、总额 20、两笔 `REFUND/SUCCESS`。**退空后**用首笔同键同参重放，
    仍返回首笔 refund id（`1030085935269`）。
  - `#1003` 边界：API 建 `SALE/SUCCESS` 20 USD 的 `refundCreate` 返回 userError
    `Reference can't be blank`，留下两条 `REFUND/ERROR`，订单仍 `PAID`、
    可退数量仍 2；适配器映射 `INVALID_REQUEST`。根因未完全隔离，不作生产结论。
- 测试：`.\mvnw.cmd -f kernel/pom.xml -B -ntp verify` → contract 30 /
  shopify 24 + 2 skipped，BUILD SUCCESS；coverage contract 84.53% /
  shopify 65.38%。新增 `ShopifyLiveRefundTest` 默认跳过，只有
  `SHOPIFY_LIVE_WRITE=1` 才写真实店铺。
- 真实限流：GraphQL 6 个 936-cost 查询并发，2 个 HTTP 200 + `THROTTLED`
  （`currentlyAvailable=128`）；Admin REST 80 并发，32 个 HTTP 429 +
  `Retry-After: 2`；8 别名（1248）被 `MAX_COST_EXCEEDED` 拒绝。
- 边界：`Refund.staffMember` 返回 `ACCESS_DENIED`（需 `read_users` +
  finance embedded/Plus/Advanced）；Webhook、`returnCreate`、退货/换货未验证
  （无对应 scope）；多笔 CAPTURE 的跨交易拆分未实现；60 天边界未触发；
  重放缓存仅在进程内，跨进程/重启的持久幂等仍待 `RefundApprovalStore` / outbox。

**AC8 三个现场追问的回答**

1. **为什么假传输层没抓到？** 假传输层假设 `refundCreate` 会返回
   `totalRefundedSet` 和 transaction；真实 2026-10 在不传 `transactions` 时
   两者都为空，但仍消耗可退数量。现已用真实形状补假传输层用例，并强制
   `REFUND` 交易。
2. **会不会把“已提交”误报成“已到账”？** 不会。只有显式 `REFUND` 交易
   `SUCCESS` 才映射 `SETTLED`；空交易仍是 `SUBMITTED`。修复后真实退款在
   响应里立即 `SUCCESS`，live 测试断言 `SETTLED`。
3. **开发商店的 Bogus 结果能否代表生产？** 只能证明 GraphQL 形状、幂等、
   交易时序和金额映射；Bogus 是测试网关，不代表真实清算。生产前仍需
   Shopify Payments/真实网关的退款对账与 Webhook 验证。

**独立 Verifier（2026-11-26）**

- 结论 **PASS**：AC1–AC7 全部通过，AC8 记 BLOCKED；P0 无。
- P1：票面曾写 shopify 覆盖率 56.75%，clean 实测为 56.35%。已整改：
  JaCoCo `prepare-agent` 设 `append=false`，票面同步为 56.35%；
  非 clean 重跑不再受旧 `jacoco.exec` 影响。
- 复核（2026-11-26）：不 clean 重跑仍为 contract 84.53% / shopify 56.35%，
  P1 关闭；最终结论 **PASS**，无需继续整改。
- **AC8 修复后（`40a5357`）需要独立 Verifier 重新亲跑 AC1–AC8**；上面的 PASS
  只覆盖 AC1–AC7 与 AC8 BLOCKED 时的状态；2026-11-27 已在 `87ae5cb` 复跑，见下。
- 当时所有者书面签收尚未发生；2026-11-27 已签收 `87ae5cb`，本票转 `done`。

**独立 Verifier（2026-11-27，HEAD `fa6befa`）：FAIL + 整改**

- A–G 全过：内核 verify、覆盖率、根回归 878/144、文档真值/链接、
  `git diff --check`、AC8 退款写链路本身均 PASS；无 P0。
- **F1**：AC8 要求真实 `429` 与 `THROWS/THROTTLED` 形状，此前只有单测合成。
  整改：真实触发 GraphQL `THROTTLED`（6×936 cost 并发，2 次）与 REST 429
  （80 并发，32 次，`Retry-After: 2`）；读数写入 `docs/EVIDENCE.md`、
  `kernel/README.md`、映射表 §8.1。
- **P1-1**：`kernel/shoppilot-commerce-shopify/README.md` 曾写“尚未对真实开发
  商店执行写操作”、测试计数过期。已更新为 AC8 已执行、24 执行 + 2 skipped。
- **P1-2**：`docs/shopify-refund-contract-mapping.md:5` 曾写“尚未在开发商店实跑”
  且错误引用“第 9 节”。已改为 §8.1 实测结论。
- **P1-3**：订单退空后同键重放先撞 `RefundQuoter.quote`，返回
  `STATE_NOT_ALLOWED` 而不是原 receipt。整改：`createRefund` 在报价前查
  按租户 + key 隔离的进程内重放缓存（access-order，上限 10,000）；同键同指纹
  返回原 receipt，同键不同指纹 `INVALID_REQUEST`。新增 3 条单测 + live
  `#1015` 退空后重放断言。红对照：移除缓存后 2 条用例分别得到
  `result is not Ok` 与 `STATE_NOT_ALLOWED`。
- 整改提交：`e7004e6`；文档证据提交：`87ae5cb`。

**独立 Verifier 复验（2026-11-27，HEAD `87ae5cb`）：PASS**

- 结论：F1、P1-1、P1-2、P1-3 全部闭环；A–G 与 live 写链路亲跑通过；无 P0/P1。
- 内核：contract 30；shopify 26（24 执行 + 2 live skipped）；覆盖率
  contract 84.53% / shopify 65.38%；BUILD SUCCESS。
- 根回归：878 tests / 144 suites 全绿；`gen_status`、`verify_doc_truth`、
  `check_doc_links`、`git diff --check` 全过；验证前后 `git status --short` 为空。
- F1 亲跑：GraphQL 32/40 `THROTTLED`（HTTP 200）；Admin REST 36/80 HTTP 429
  （`Retry-After: 2`）。
- live `#1016`：首退 10 USD `SETTLED`，同键重放同 id，同键改参 `INVALID_REQUEST`，
  退空后重放仍返回首笔 receipt；最终 `REFUNDED`、20 USD、两笔 `REFUND/SUCCESS`。
- 边界：重放缓存挂在同一 `ShopifyCommerceGateway` 实例上，check-then-act 非原子；
  并发同键仍依赖 Shopify `@idempotent`；不是持久幂等。均已在 README/EVIDENCE 声明。
- **所有者书面签收：2026-11-27，`87ae5cb`。**
