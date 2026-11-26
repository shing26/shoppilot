# 票 232 契约：退款内核与 Shopify 适配器转正（独立 reactor）

- Round: 62
- 类型: 模块转正 / 生产依赖变更（走完整验收链路）
- 开票依据: 所有者 2026-11-26 指令「接受 A，按提案默认值继续」
- ADR: [0088](../docs/adr/0088-refund-approval-ledger.md)（审批载体）、
  [0089](../docs/adr/0089-refund-settlement-read-model.md)（终态读模型）、
  [0090](../docs/adr/0090-buyer-term-and-store-scoped-customer.md)（买家术语）
- 冻结时间: 2026-11-26
- Implementer: 独立分支 `codex/232-commerce-kernel-promotion`；不得与 Verifier 同会话
- Verifier: 独立 subagent；命令由 Verifier 亲跑

## 问题（开票时事实）

退款端口、TCK 与 Shopify 2026-10 适配器已经能编译、能跑：

- `drafts/shoppilot-commerce-contract/`：30 条测试绿；
- `drafts/shoppilot-commerce-shopify/`：13 条 TCK 绿 + 1 条 live smoke 默认跳过；
- 两者在 `drafts/pom.xml` 独立 reactor 中，不进入根 `pom.xml`，也不进入 CI。

它们仍是「草案」：没有独立版本、没有 TCK 制品、没有覆盖率门禁、没有 CI；
适配器还有两处资金正确性缺口：

1. `mapOrder` 的 `shippingRefunded` 恒为 `false`，同一订单第二次退运费可能重复计价；
2. `createRefund` 用 `totalRefundedSet` 的缺省 0 兜底，字段缺失时会把金额退成 0
   而不是失败关闭。

**本票不接网关、不改 `biz-mock`、不改现有 agent 行为。** 目标是把内核做成
可独立构建、可独立发布、可被别人复用的 reactor，而不是把它塞进 ShopPilot 主链路。

## 目标

把 `drafts/` 提升为仓库内的独立 `kernel/` reactor：

```
kernel/
  pom.xml                        独立 parent（不继承 shoppilot-parent）
  shoppilot-commerce-contract/   端口 + 领域类型 + RefundWorkflow + TCK（test-jar）
  shoppilot-commerce-shopify/    Shopify Admin GraphQL 2026-10 适配器
  scripts/check_coverage.py      内核自己的覆盖率棘轮
```

版本从 `0.1.0-SNAPSHOT` 起，groupId 暂用 `com.shoppilot`，包名保持
`com.shoppilot.commerce*` 不变（本票不做包名迁移）。

## Allowed paths

- `kernel/**`（新建；从 `drafts/` 迁入）
- `drafts/**`（迁出后删除重复源码，只保留一行指向 `kernel/` 的说明）
- `.github/workflows/kernel-ci.yml`（新建）
- `docs/adr/0088-refund-approval-ledger.md`
- `docs/adr/0089-refund-settlement-read-model.md`
- `docs/adr/0090-buyer-term-and-store-scoped-customer.md`
- `README.md`（**仅** ADR badge、ADR 计数格、编号位说明三处；见契约修订 1）
- `docs/CODE_MAP.md`（只加内核 reactor 指针）
- `docs/EVIDENCE.md`（只在 AC8 产生真实证据时加行）
- `docs/shopify-refund-contract-mapping.md`（只更新状态与路径）
- `plans/232-commerce-kernel-promotion.md`
- `.scratch/shoppilot-mvp/issues/232-commerce-kernel-promotion.md`
- `.scratch/shoppilot-mvp/README.md`

## 明确禁止

- 不改根 `pom.xml`、`docs/STATUS.md`、`TUNING_PLAN.md` 或根 reactor 的
  四模块测试计数；根 `README.md` 只允许 ADR 计数三处同步，不得改 JVM/套件/
  验收步数或产品叙述。
- 不改 `shoppilot-gateway` / `shoppilot-biz-mock` / `shoppilot-ticket` /
  `shoppilot-tool-api` 的任何源码、配置、测试或 API。
- 不把内核接进 `AgentStateMachine`、`ToolDispatcher`、`BizMockClient`。
- 不改 `CONTEXT.md`、既有 ADR 结论、gold、阈值、判据或指标分母。
- 不放宽、删除、跳过任何测试；不为过覆盖率门禁写无断言用例。
- 不提交 `.env`、token、`logs/`、`target/`、截图或临时产物。
- 在 AC8 完成前，不得对外宣称「已接入 Shopify / 支持真实店铺退款」。

## Acceptance criteria

### AC1 独立 reactor

`kernel/pom.xml` 不继承 `shoppilot-parent`，不依赖 Spring、网关或 `biz-mock`；
以下命令从干净状态成功：

```powershell
.\mvnw.cmd -f kernel/pom.xml -B -ntp verify
```

`kernel/shoppilot-commerce-contract` 与 `kernel/shoppilot-commerce-shopify`
的运行时依赖只允许 JDK + Jackson；JUnit/AssertJ 只能出现在 `test` 或
`provided`（TCK 制品）范围。

### AC2 契约与 TCK 制品

- contract 模块 30 条测试全绿；
- TCK 从 `src/main` 移到 `src/test`，通过 `maven-jar-plugin` 的 `test-jar`
  发布；适配器用 `<classifier>tests</classifier>` + `test` scope 依赖；
- 运行时 jar 中不得出现 `org/junit`、`org/assertj` 或 TCK 类；
- 用 `jar tf` 或 `mvn dependency:tree` 留证据。

### AC3 Shopify 适配器

- 13 条 TCK 在假传输层全绿；
- 未设置 `SHOPIFY_SHOP` / `SHOPIFY_TOKEN` 时 live smoke 仍跳过，默认构建零网络；
- 适配器 README 写明「假传输层 TCK 通过 ≠ 真实店铺验证」。

### AC4 资金正确性缺口（P0）

二选一，必须有测试与文档：

1. **正确读取已退运费**：`shippingRefunded` 不再恒为 `false`，第二次含运费的报价
   不会重复计费；或
2. **暂不支持退运费**：`refundShipping=true` 在报价/提交时返回
   `STATE_NOT_ALLOWED`，直到实现正确的已退运费读取。

同时：`totalRefundedSet` 缺失或形状不符时必须返回 `INTERNAL`，不得退化为 0。
两条都要有「修复前红、修复后绿」的测试证据。

### AC5 覆盖率门禁

`kernel/scripts/check_coverage.py` 按模块读 JaCoCo，缺产物即失败；
两个内核模块都进门槛表；门槛 = 首次实测 LINE 向下取整 − 1.0pp。
该脚本在 kernel CI 中执行，不并入根 `scripts/check_coverage.py`。

### AC6 独立 CI

新增 `.github/workflows/kernel-ci.yml`：

- 触发：`push` / `pull_request` 且路径包含 `kernel/**`，加 `workflow_dispatch`；
- JDK 21；
- 步骤：`./mvnw -f kernel/pom.xml -B -ntp verify`，再跑
  `python3 kernel/scripts/check_coverage.py`；
- 不需要 secrets、不需要 Docker、不需要 Ollama；
- 现有 `.github/workflows/ci-subset.yml` 一字不改。

### AC7 文档与去重

- `kernel/README.md`：定位、端口、TCK 用法、适配器范围、明确没做的、已知缺口；
- `drafts/` 删除重复源码，只留指向 `kernel/` 的说明；
- `docs/CODE_MAP.md` 加「kernel 是独立 reactor，不属于根四模块」的指针；
- `docs/shopify-refund-contract-mapping.md` 的路径与状态同步到 `kernel/`；
- 根 `README.md` 与 `docs/STATUS.md` 不动。

### AC8 真实开发商店（阻塞，另行验收）

用 Shopify Partner 免费开发商店执行 `docs/shopify-refund-contract-mapping.md`
第 8 节的 10 条验证，至少覆盖：

- `refundCreate` 的行项目/运费形状；
- 同 `@idempotent` 键重放；
- 测试模式 Transaction 的 `PENDING` / `SUCCESS` 时序；
- 多次部分退款到 `REFUNDED`；
- 429 与 `THROWS` 错误形状。

证据落 `docs/EVIDENCE.md` 或 `kernel/docs/`，并绑定 commit SHA。
**没有所有者提供的 dev store 凭据时，AC8 记 BLOCKED，不得用假传输层结果替代。**

**AC8 执行结果（2026-11-27，已执行；待独立 Verifier 复验）**：免费开发商店已执行真实退款写操作。
实测发现 `refundCreate` 不传 `transactions` 时返回 0 金额、无交易，但消耗
`refundableQuantity`；已修为显式提交 `REFUND` 交易并失败关闭（`40a5357`）。
补验发现订单退空后同键重放会先撞报价，已修为重放先于报价（`e7004e6`）；
同键同参返回原回执，同键改参 `INVALID_REQUEST`。
证据：`docs/EVIDENCE.md` 的 round62 章节、`kernel/README.md` 的 AC8 章节。
边界：staffMember、Webhook、returnCreate/退货、多笔 CAPTURE 拆分、60 天边界
仍未验证；真实 GraphQL `THROTTLED` 与 HTTP 429 形状已触发并记录；重放缓存
仅在进程内，持久幂等仍待 `RefundApprovalStore` / outbox。不改变上面的验收判据。

### AC9 独立验证与签收

- Verifier 与 Implementer 不同会话；
- Verifier 亲跑 AC1–AC7 的命令，不接受实现方日志；
- 输出 P0/P1 清单；P0 必须修完复验；
- 所有者书面签收后才算 `done`。

## Verify

内核：

```powershell
.\mvnw.cmd -f kernel/pom.xml -B -ntp verify
python kernel/scripts/check_coverage.py
```

根回归（证明没碰主仓）：

```powershell
.\mvnw.cmd -B -ntp verify
python scripts/gen_status.py --check
python scripts/verify_doc_truth.py
python scripts/check_doc_links.py
git diff --check
git status --short
```

CI 等价：

```bash
bash ./mvnw -f kernel/pom.xml -B -ntp verify
python3 kernel/scripts/check_coverage.py
```

## 实施顺序

1. 建独立分支 `codex/232-commerce-kernel-promotion`。
2. `drafts/` → `kernel/` 迁移；写独立 parent POM 与 `0.1.0-SNAPSHOT`。
3. TCK 移到 `src/test` 并打 `test-jar`；适配器改依赖。
4. 修 AC4 两条资金缺口，补红绿对照测试。
5. 加内核覆盖率脚本与门槛。
6. 加 `kernel-ci.yml`。
7. 补 `kernel/README.md`、`docs/CODE_MAP.md`、映射文档状态。
8. 跑完整 Verify，提交证据。
9. 独立 Verifier 复验；P0 修完再复验。
10. 所有者签收；AC8 在拿到 dev store 后单独补验。

## 已知边界（不在本票）

- 不接网关、不做对话工具循环、不做工单降级链路；这些是后续票。
- 不做持久化 store、outbox、CAS、Webhook、对账任务。
- 不做退货/换货、回库、税费、多币种、商店积分。
- 不做 Maven Central 发布与签名；本票只做到「可独立构建的 reactor」。

## 契约修订

**修订 1（2026-11-26，契约冻结同日）**：新增 ADR 0088–0090 后，
`python scripts/verify_doc_truth.py` 会因 README 的 ADR badge 仍写 86、实际 89 而红。
因此把根 `README.md` 的三处 ADR 计数（badge、指标格、编号位说明）纳入 Allowed paths：
86 → 89、编号位 0087 → 0090。**JVM 878 / 套件 144 / 验收 29 步不动**；
其余 README 内容仍禁止改。
