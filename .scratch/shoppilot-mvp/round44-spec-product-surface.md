# round44 spec：产品面质感（B 档）

**收口状态（2026-11-03）**：**done**。三票 188-190 全部 `implemented`；票 190 收口时三脚本实跑
（买家 13/13、工作台 21/21 + 1 WARN、调试台受本机检索降级影响有 3 条红，见票 190 Handoff），
`docs/r44-*.png` 十张双宽度截图入库。

开轮依据：[ADR 0079](../../docs/adr/0079-round44-product-surface-reopen-basis.md)
（**触发线真实成立**：ARCHITECTURE.md §5 第 6 项「前端…触发 = 产品面继续投入」；
同时按 ADR 0033 所有者政策由所有者点名开轮。两条依据是两件事，别混写成同一种）。

## 0. 本轮要补的那一格

```
┌──────────────── 买家（frontend-buyer）────────────────┐
│ 店铺卡 → 问答流 → 依据面板（doc/heading/ruleId）      │
│                    ↓ 我的订单                         │
│         订单深度卡（地址/金额/服务标）                 │
│                    ↓ 展开                             │
│         物流时间轴（承运商/运单号/节点）  ← 新后端面   │
└───────────────────────┬───────────────────────────────┘
                        │ 点踩
┌───────────────────────v───────────────────────────────┐
│ 坐席（frontend-workspace）                            │
│ 队列 → 工单生命周期 → 退款决策 → 质量报告              │
│                        └→ 知识飞轮（新视图）           │
│                           复核完成 → 附稿 → 发布       │
└───────────────────────┬───────────────────────────────┘
                        │ round43 票 186 已开代理口
                        v
        ingest 第二语料源 → 纪元推进 → 旧答案整体作废
```

本轮不动这条链的任何判据；动的是**它在人眼里长什么样**。

## 1. 票拆分

| 票 | 内容 | 依赖 |
| --- | --- | --- |
| [188](issues/188-design-system-and-component-skeleton.md) | 设计令牌补齐 + 两端组件拆分（**零行为变更**）+ dev 代理 + CI 未跟踪产物门禁 | — |
| [189](issues/189-order-logistics-ticket-lifecycle-depth.md) | 深度卡片：订单详情/物流轨迹后端补口 + 工单生命周期/退款决策渲染 | 188 |
| [190](issues/190-knowledge-flywheel-and-visual-closeout.md) | 知识飞轮视图 + 三入口视觉收口 + 浏览器断言实跑 + 文档换代 | 189 |

## 2. 逐票判据

**票 188**（结构，零行为变更）：

- `frontend-shared/src/tokens.css` 补齐 `--radius-*` / `--space-*` / `--text-*` / `--shadow-*` /
  `--ring` / `--ease` / `--dur-*`；硬编码色值收敛为「实色 + soft + line」三件套；
  文件头注释写明边界（跨端一致项 vs 页面布局）。
- 只加跨端原子：`:focus-visible` 环、`.chip`、`.kpi`、`.timeline`、`prefers-reduced-motion` 块。
- `index.ts` 合并 `formatTime` / `slaText` / `shortId` / `countLabel`；已有
  `minutesLeft`/`priorityRank`/`STATUS_LABEL`/`PRIORITY_LABEL` 不动。
- 买家 8 SFC（TopBar / StoreCard / LoginGate / ChatThread / Composer / SideActivity /
  ServiceCard / TicketDrawer）+ `lib/format.ts`；工作台 10 SFC（TopBar / LoginCard / SideNav /
  QueueView / ConversationsView / RefundsView / ChannelsView / QualityView / ResolveDialog /
  RefundDialog）。
- 状态全部留根组件；子组件只 `props`/`emits`。流式消息对象**按引用**传递；`selectView` 的
  懒加载副作用只能由根组件执行；SSE / Notification / AudioContext / push 订阅留根。
- 保留 `.thread` / `.msg` / `.bubble` / `.ask` 类名（`scripts/verify-buyer.mjs` 手工可用）。
- 顺带修：买家 `now` 的 30 秒 `setInterval` 无清理（真泄漏）。
- `ci-subset.yml` 第 9 步追加 `git status --porcelain -- <static 两目录>` 非空即失败。
- 判定：**页面观感与拆分前一致**；typecheck/build 绿；产物整目录（含删除）入库。

**票 189**（深度卡片）：

- `shoppilot-tool-api`：`OrderDetailView`、`LogisticsNodeView`（与 `OrderView` 同包）。
- biz-mock `OrderQueryController`：`GET /api/orders/mine/{orderNo}`（地址块 + `paidAt`/
  `shippedAt`/`deliveredAt` + `addressVersion` + 服务标）与 `/logistics`（按 `occurredAt` 倒序）。
  归属 = `@TenantId` + `customerId` 双条件（ADR 0004）；越权 404；手机号 biz-mock 侧脱敏。
- 网关两条转发 + `api.ts` `orderDetail()` / `orderLogistics()`。
- 买家端：状态推进条、脱敏地址、物流时间轴（承运商 + 运单号可复制 + 最新节点高亮）。
- 工作台：`transcript` 从裸 `pre` 改对话气泡；工单生命周期时间轴（创建 / 升级 / 领取 / 结单，
  用 `SOURCE_LABEL`/`QUEUE_LABEL`）；退款决策路径与 `source=REFUND_APPROVAL` 工单对应。
- 测试：biz-mock 本人可见 / 同店他人 404 / 跨店 404 / 轨迹倒序 / 无轨迹返回空数组 / 脱敏；
  网关代理路径与租户头。

**票 190**（飞轮 + 收口）：

- 工作台「知识」视图：接 round43 票 186 的四个代理口（复核完成 / 附稿 / 列表 / 发布或驳回），
  全路径开工第一步按 `@RequestMapping` 核对（预期 `/api/v1/support/ops` 前缀）。
- 链路呈现：PENDING → REVIEWED → DRAFT → PUBLISHED，附「发布后需重跑 ingest 才生效」的显式提示，
  不伪造"发布即生效"。
- 买家端依据面板只展示已有 `citationDetails`，不新增后端字段、不伪造知识版本号。
- 三入口视觉收口（`static/index.html` 为标杆，不抄布局，统一令牌与空状态语言）；
  `console.html` 只改样式，不动测试钉住的字面量。
- `scripts/verify-buyer.mjs` / `verify-workspace.mjs` / `verify-console.mjs` 选择器跟随新 DOM
  修好并**实跑**；跑不过照登，不许写"已验证"。
- 截图 `docs/r44-*.png`（桌面 + 390px）；`docs/EVIDENCE.md` round44 条目区分实测与主观确认。
- 文档换代：CODE_MAP（前端组件表 + 新查询口/代理口）、CONTEXT.md（如需新术语，**增词不改旧词**）、
  `python scripts/gen_status.py` 重生 STATUS、tracker 索引与三票收口。

## 3. 本轮不改

- 任何验收判据、阈值、gold、`judge()`、指标分母。
- `knowledge/` 文件语料（B7 内容级禁面）。
- 缓存写回七道资格、降级枚举、评测集。
- `static/console.html` 中被测试钉住的属性字面量。
- 进程内 ingest 触发（发布后仍靠离线重跑）。

## 4. 登记不执行

1. **前端测试 runner（vitest + CI 第 10 步）**——触发 = 组件内出现非平凡纯逻辑（缓存/仲裁类），
   手工验证成本超过建 runner 的成本。本轮逻辑仍在根组件与 `lib/format.ts`，typecheck +
   playwright 覆盖足够。
2. **路由库 / 状态库**——触发 = 入口数超过 3 个，或跨视图共享状态出现第二处写者。
3. **暗色主题**——触发 = 产品演示清单提出；本轮只补 `prefers-reduced-motion`。
4. **发布即生效（进程内触发 ingest）**——沿 round43 spec §4 原触发线，不因前端做完而改变。
5. **三入口共享导航栏**——触发 = 三入口需要同时出现在一个导航里；本轮只统一视觉令牌。

## 5. 验证分档

| 票 | 必跑 |
| --- | --- |
| 188 | 两工程 `npm run typecheck` + `npm run build`；产物整目录（含删除）入库；`.\mvnw.cmd -B -ntp verify` |
| 189 | `.\mvnw.cmd -B -ntp verify`（新增 biz-mock / 网关用例）；前端 typecheck/build/入库 |
| 190 | 三脚本 playwright 实跑 + `node --check`；`python scripts/gen_status.py`；双宽度截图 |
| 每票 | `git diff --check`、`git status --short` |

**产物入库是本轮最容易翻车的一步**：源码改了不重建、或重建后不提交删除项，CI 都会红
（round30 事故 run `37547276974` 的先例）；而新增未跟踪 chunk 则相反——CI 绿、干净克隆 404。
票 188 起把后一条堵进 CI。
