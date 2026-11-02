# round44 开轮依据：产品面质感（ARCHITECTURE §5.6 触发线成立）

Date: 2026-11-03　Status: **Accepted**（2026-11-03 所有者指令开轮）
Owner basis: **双层依据**。《ARCHITECTURE.md》§5 第 6 项登记的缺口——
「前端：两个产品入口约 1400 行，无路由/状态库/单测；触发 = 产品面继续投入」——**本轮即该条的执行，
触发线真实成立**；同时 v1.0.0 冻结后开轮仍需所有者点名，按 ADR 0033 记录的所有者政策执行。
两者是两件事：前者说明这一格本来就该补，后者说明由谁决定现在补。round43 / ADR 0078 是纯政策覆盖
（触发线未到），**本轮不是**——不要在文档里把两种情况写成同一种理由。

Tickets: [188](../../.scratch/shoppilot-mvp/issues/188-design-system-and-component-skeleton.md) /
[189](../../.scratch/shoppilot-mvp/issues/189-order-logistics-ticket-lifecycle-depth.md) /
[190](../../.scratch/shoppilot-mvp/issues/190-knowledge-flywheel-and-visual-closeout.md)。
Spec: [round44-spec-product-surface.md](../../.scratch/shoppilot-mvp/round44-spec-product-surface.md)。

## Context

后端已经跑通业务闭环：工单竖切、身份域、渠道出站、买家端、agent 自主性、知识反向沉淀
（round43 活体 10/10，含纪元推进与旧答案作废）。但两个产品入口各自是一个单文件 Vue 组件
（`frontend-buyer/src/App.vue` 1239 行、`frontend-workspace/src/App.vue` 1627 行），
设计语言停在 `frontend-shared/src/tokens.css` 的表单级基线，圆角与色值在页面里硬编码成魔法数，
全仓三个 `@keyframes`。结果是：**能力在后端，观感在夹具**——用户明确反馈"展示页面太重 demo 感，
看起来没有探索欲望"。

这不是"缺功能"，是缺产品投入。判定依据有三条可观测事实：

1. 一个文件承担页面全部职责，改动任何一处都要读 1200+ 行，谈不上可维护的界面结构；
2. 后端已有大量**未被渲染**的深度数据（订单地址块与三个生命周期时间戳、物流轨迹实体、
   工单 `transcript`/`escalatedAt`、质量报告树、round43 刚打通的知识修订链路），页面上只呈现
   字段的子集；
3. 仓库最独特的东西——知识飞轮（点踩 → 复核 → 附稿 → 发布 → 纪元推进）——在前端完全不可见，
   round43 票 186 开好的代理口没有任何前端调用。

## Decision

1. **拆分组件，但不引路由库、状态库、组件库、测试 runner**。两个 ADR 已定死视觉边界
   （[0073](0073-round38-workspace-console-product-surface.md) 第 29 行、
   [0074](0074-round39-buyer-surface-product-layout.md) 第 26 行：不引组件库、不加运行时依赖），
   本轮把这条边界原样继承并推广到前端基础设施：组件拆分靠 `props`/`emits` + 根组件持有状态，
   视图切换沿用已有的 `view` ref，不引入第二套状态源。**理由不是保守，是这套规模下引入状态库
   会把 30+ 个 ref 的归属重写一遍，收益抵不上回归风险。** 各自登记了触发条件（spec §4）。
2. **深度卡片分两类处理，不假装都是前端活**。
   - **买家端订单详情 / 物流轨迹需要新增后端面**：`Order` 与 `LogisticsNode` 实体数据齐全，
     但 `OrderQueryController` 只暴露 `GET /api/orders/mine`，`OrderView` 只吐 6 个字段。
     本轮新增 `OrderDetailView` / `LogisticsNodeView`（与 `OrderView` 同包在
     `shoppilot-tool-api`）+ biz-mock 两条查询口 + 网关两条转发。归属条件沿用 ADR 0004 的
     「租户 + 买家」双条件，越权一律 404 不确认存在。
   - **手机号在 biz-mock 侧脱敏**（`138****5678`）：脱敏放前端等于原文过网并进浏览器内存，
     这种"看起来遮住了"的实现比不遮更危险。
   - **工作台侧深度卡片（工单生命周期、退款决策、质量报告）零后端**：数据与 `api.ts` 全齐，
     只是渲染得太浅。
3. **知识飞轮可视化复用 round43 已开的代理口，不新增后端**。工作台新增「知识」视图，
   呈现 复核完成 → 附稿 → 发布 → 「需重跑 ingest 才生效」的完整链路。
   这使 [round43 spec](../.scratch/shoppilot-mvp/round43-spec-knowledge-backflow.md) §4 登记项 1
   「工作台修订编辑器」的触发条件（"修订面进入产品演示清单"）**成立并转执行**——登记项不是
   永久豁免，触发到了就要在 spec 与 ADR 里各写一句，不能让它悬空。
4. **产物入库纪律收紧**。前端构建产物提交在 `static/buyer|workspace`，CI 用
   `git diff --exit-code` 逐字节比对——但该命令**只看已跟踪内容**，构建新吐出的未跟踪 chunk
   它看不见（CI 绿、干净克隆 404）。本轮两条一起做：
   - **禁止代码分割**（不用 `defineAsyncComponent` / 动态 `import()`），产物保持单 bundle；
   - 在同一 CI 步骤追加 `git status --porcelain -- <两个 static 目录>`，非空即失败。
   后者是保护本轮交付物自身的门禁，不是票外重构。
5. **设计系统只收跨端一致的令牌与原子**。`tokens.css` 文件头自述的边界不变：颜色、字阶、
   间距、圆角、阴影、动效时长、focus 环、`prefers-reduced-motion` 放这里；页面布局留在各页。
   硬编码色值先收敛成语义令牌（实色 / soft / line 三件套），再改观感。
6. **视觉验收不宣称自动化能覆盖**。Playwright 能钉结构与流程，钉不了"好不好看"；
   截图落 `docs/r44-*.png` 并登记 EVIDENCE，主观判断由所有者给出。

## Non-goals

- 不引组件库、路由库、状态库、测试 runner（触发条件见 spec §4）。
- 不做暗色主题；只补 `prefers-reduced-motion` 与 focus-visible。
- 不做进程内"发布即生效"（沿 round43 原触发线）。
- 不改任何验收判据、阈值、gold、指标分母；不改 `knowledge/` 文件语料。
- 不改 `static/console.html` 中被 `ServedConsoleHidesDevDefaultsTest` 钉住的字面量
  （`id="opsToken"` 的 `placeholder`、`id="agentPass" size="10" placeholder="口令"` 等），
  只改样式。
- 不做票外重构：拆分中发现的死代码只在被重写的文件内处理，其余记入 Handoff「登记不修」。

## Consequences

- 买家端 `App.vue` 拆为根壳 + 8 个 SFC + `src/lib/format.ts`；工作台拆为根壳 + 10 个 SFC。
  `tsconfig` 的 `include` 已覆盖 `src/**/*.vue`，新组件自动进 typecheck，无需别名或配置。
- `frontend-shared/src/tokens.css` 扩充令牌与跨端原子；`index.ts` 合并两端重复的纯函数
  （`formatTime` / `slaText` / `shortId` / `countLabel`）。
- `shoppilot-tool-api` 新增 `OrderDetailView`、`LogisticsNodeView`；biz-mock `OrderQueryController`
  新增 `GET /api/orders/mine/{orderNo}` 与 `.../logistics`；网关买家订单代理口新增两条转发。
- 两个 `vite.config.ts` 增加 `server.proxy` 指向 `127.0.0.1:8082`（仅 dev server，构建产物零影响），
  使视觉迭代不必每次重建。
- CI 第 9 步从"两工程构建 + 逐字节比对"扩为"再查未跟踪产物"，门禁仍在一处。
- 买家 `App.vue` 中 30 秒 `now` 定时器无句柄无清理的既有泄漏在拆分中修掉；两端重复的
  `formatTime`/`slaText` 收进 `frontend-shared`。
- 证据：`docs/r44-*.png` + `docs/EVIDENCE.md` round44 条目（区分「脚本实测」与「所有者主观确认」）。
