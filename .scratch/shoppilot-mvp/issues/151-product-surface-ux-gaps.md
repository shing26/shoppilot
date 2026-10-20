# 151 产品面履约：两个产品入口从「验收夹具」到可用的界面

**Status:** ready-for-agent（2026-10-20 开轮：round36，[ADR 0070](../../../docs/adr/0070-round36-product-surface-completion-reopen-basis.md)）

**Claimed by:** zcode session 2026-10-20（round36 实现：A+B+C 三段）

> 本票是一张**范围票 + 实现票**的合并形态（本仓规则要求新工作先有 spec；本票的「范围与验收」即 round36 最小 spec，
> 开轮依据与逐项裁决见 ADR 0070）。

## What to build

三个前端里，`/`（调试台）是运维页，极客化是它的职责；`/buyer/`（买家中心）与 `/workspace/`（坐席工作台）
是 ADR 0057 说定的**产品入口**，但当前只做到「通过浏览器门禁」为止。本票按价值排序分三段：

- **A（P0，优先且可独立合并）— 坐席端拿到处理所需的全部信息**：结单意见不再永远为空；会话原文可见；
  枚举可读；页头去掉票号/ADR 编号。
- **B（P1）— 买家端像一个产品页**：对话流（不是只显示最后一条）；内部租户号不再要求买家理解；
  加载态/空态/失败态三态分清。
- **C（P2，最后）— 视觉与工程收口**：共享设计令牌与基础样式、共享契约类型；三个入口之间可导航；
  390px 下产品页不横向溢出。

## Blocked by

—

## 开轮依据（已裁定，2026-10-20）

所有者指令「A+B+C 一起走」。按 `AGENTS.md` 的冻结线纪律，本轮归类为**所有者政策覆盖**（B/C 段未命中
ADR 0030/0031 的任何触发），开轮依据、逐项裁决与红线写在
[ADR 0070](../../../docs/adr/0070-round36-product-surface-completion-reopen-basis.md)：

- **A1（空结单意见）** 单独归类为**对 ADR 0059「结单结论送回原渠道」的缺陷修复**，不冒充政策覆盖。
- **B2 取 URL 店铺码**、**C1 取 `file:` 共享包**：两处票面原写「所有者选」，本次未获逐项答复，
  **按建议值执行**并在 ADR 0070 记录（本仓先例：未获答按建议值记录）。
- 三段顺序 A → B → C，每段单独提交、单独验证；A 段可独立合并。

## 范围与验收

### A. 坐席端：处理所需的信息（P0）

**A1. 结单意见不能是空字符串**

- 现状：`frontend-workspace/src/App.vue:144` 调 `resolveTicket(ticket.id, session.value, '')`；
  后端 `shoppilot-ticket/.../WorkItemService.java:237` 的 `resolve` 把 `note` 同时写进审计与出站消息——
  空 note → 审计「处理完成，未填说明」、出站「已处理完成」（`:254`、`:259`）。ADR 0059 要的「结论」因此从未送出。
- 做法：结单确认框内加一个文本框（必填，去首尾空白后 ≥1 字），提交进 `note`；前端拦住空提交，
  后端维持现状（宽容）或同步加 400——**前后端口径必须一致**，在票面记录所选口径。
- 验收：
  - 结单后审计事件 `TICKET_RESOLVED` 的说明含该意见（JVM 或活体均可）。
  - 出站投递的正文含该意见而不是「已处理完成」（本地回声端点可测，照票 88 的既有手法）。
  - `verify-workspace.mjs` 的「处理完成会先确认」断言保留；新增「意见为空时提交被拦」一格。

**A2. 会话原文（transcript）——先补契约，再上页面**

- 现状（两处比观感更硬的事实）：
  - `shoppilot-tool-api/.../view/TicketView.java` **没有** `transcript` 字段（44 行，最后两个字段是 `channel` / `contact`）；
    `WorkItemService.toView` 与 `toBuyerView` 都不带它（`:185`、`:271`）。而 `WorkItemService.java:171` 的注释、
    以及 `frontend-workspace/src/types.ts:11` 都声称它存在 → 前端声明的是一个**永远 `undefined` 的字段**。
  - `shoppilot-ticket/src/test/.../BuyerOwnTicketsTest.java:71-78` 的「买家不该拿到会话原文」是**空断言**：
    两边都没有 transcript，所以它永远绿；`toBuyerView` 与 `toView` 的真实差异其实是 `contact`。
- 做法：
  - `TicketView` **尾部追加** `transcript`（沿用既有「追加在尾部 + 保留旧构造器」的做法，`@JsonIgnoreProperties` 已在）；
    `toView` 传入，`toBuyerView` 仍传 `null`（`transcript` 还是 `null`，但这次是**刻意**的）。
  - 把 `BuyerOwnTicketsTest` 改成真断言，并做变异对照：让 `toBuyerView` 也带 transcript → 该用例必须红。
  - 工作台渲染：表格行可展开或右侧详情抽屉，`pre-wrap` 限高滚动（字段长 8000，`Ticket.transcript` 的上限）。
    买家端**不渲染**。
- 验收：工作台能看到一张单的会话原文；买家端 `/mine` 响应里 `transcript` 为 `null`；变异对照实测为真。

**A3. 枚举可读化**

- 现状：`App.vue:274` 直接打印 `t.source`（`DEGRADE` / `REFUND_APPROVAL` / `CHANNEL_RECEIPT` / `FEEDBACK_REVIEW`），
  `:278` 直接打印 `t.status`（`OPEN` / `ASSIGNED` / `RESOLVED`），`:255` 的队列下拉是 `ALL/ESCALATION/REFUND/REVIEW/REPLY/DEFAULT`。
  买家端反而做了 `STATUS_LABEL` 映射（`frontend-buyer/src/types.ts`）。
- 做法：工作台补 `STATUS_LABEL` / `SOURCE_LABEL` / `QUEUE_LABEL`，口径与买家端一致；**未知值原样透出**（不吞、不显示「未知」）。
- 验收：页面上不出现上述裸枚举；后台新增一个枚举值时页面仍能显示该值本身。

**A4. 页头去内部编号**

- 现状：`App.vue:235` 的副标题是「工单与坐席服务（round23 票 72）· 凭证形态：坐席账号登录（票 83 / ADR 0056）」。
- 做法：换成产品话术（例如「待处理工单与资金审核」）；内部依据留在代码注释与文档。
- 验收：页面文本里不含 `round\d+`、`票 \d+`、`ADR \d+`。

### B. 买家端：像一个产品页（P1）

**B1. 对话流**

- 现状：`frontend-buyer/src/App.vue:29-30` 的 `query` / `answer` 都是单值，`:90-93` 每次提问先清空 →
  页面永远只有最后一轮；没有消息气泡，只有一个 `<p>`。
- 做法：改成消息数组（user / assistant），SSE 增量写入最后一条 assistant；保留进行态、失败时保留已生成部分 +
  降级说明 + 工单号（现有行为不能丢）。会话 id 仍只在内存（刷新即新会话，照登）。
- 验收：同一会话连续问两次 → 两轮问答都在；第二轮流式期间第一轮可见；失败时两轮都在且失败轮带原因与工单号。

**B2. 店铺入口不再要求买家理解内部 id**

- 现状：登录页第一格是 `店铺编号，如 T001`（`App.vue:155`）。店铺清单其实有现成读面：
  网关 `GET /api/v1/support/ops/tenants` → biz-mock `/api/admin/tenants`，返回 `{tenantId, name, rateLimitQps}`
  （`AdminController.java:62-73`）。但它在鉴权面内（`AuthFilter` 只放行静态资源），**登录前拿不到**。
- 二选一（**所有者选**，票面记录理由）：
  - **(a) 推荐**：店铺码走 URL（`/buyer/?store=T001`，扫码/链接进入），页面上只显示登录后拿到的店名；
    UI 不再出现「店铺编号」输入框。不新增未鉴权面。
  - **(b)** 新增公开只读 `GET /api/v1/support/stores`（只返回 `tenantId` + `name`，限流 + 只读），登录页下拉。
    多一个未鉴权面，需要单独的安全复核。
- 验收：未登录页面上不出现要求用户输入 `T00x` 的控件；登录后能看到店名；`store` 缺省时给出可读说明而不是 401。

**B3. 加载态不能冒充空态**

- 现状：登录后首次拉工单，`tickets=[]` 且 `ticketError=''` → 页面先显示「你还没有需要人工处理的工单」
  （`App.vue:219`）；工作台同样（`App.vue:288` 显示「这一档队列里没有待处理工单」）。
- 做法：加 `loading` 态，读取中显示「正在读取…」；空态只在**读取成功后**出现。
- 验收：首次登录时不会先闪「没有工单」；三态各有独立文本（loading / empty / error）。

**B4. 明确不做（登记，不在本票）**

- 订单/商品上下文：仓里没有买家侧订单读端点。要做得先加 biz-mock 的买家读面 + 隔离判据（同票 92 的口径），
  属于新功能，不在本票夹带。

### C. 视觉与工程收口（P2，最后）

**C1. 共享设计令牌与契约类型**

- 现状：两个 Vue 工程零共享；`api.ts` / `types.ts` / SLA 倒计时各有两份近似实现（买家端 `App.vue:124-129`、
  工作台 `App.vue:207-212`）。调试台反倒有一套完整设计令牌，产品页是默认浏览器控件。
- 做法（推荐 b，改动面小且不碰 CI 的 `npm ci` 位置）：
  - (a) 根 npm workspaces + `frontend-shared`；
  - (b) `frontend-shared/` 作为两个工程的 `file:` 依赖，导出 tokens CSS、基础组件样式、`ApiResult`/`request` 与
        契约类型；base 路径由各工程注入。
- 验收：同一份令牌改一处两个页面同时生效；`TicketView` 镜像类型只剩一份。

**C2. 三个入口之间可导航**

- 现状：三个页面之间**没有任何链接**（调试台 `index.html` 全文零 `href`）。
- 做法：只在调试台加「买家端 / 坐席工作台」两个入口。**产品页不回链运维台**（买家不该看到运维面）。
  入口不能塞 header：header 高度 49px 被抽屉 `inset` 钉死，且 `verify-console.mjs:396-404` 有 390px 页脚不溢出断言
  （round23 曾因往 header 加控件把整页撑到 554px 而红）。放页脚 `.ctl` 组或另设一行。
- 验收：`verify-console.mjs` 新增一格「两个入口存在且 href 正确」；390px 溢出断言原样通过。

**C3. 390px 不横向溢出**

- 做法：两个 Vue 页的宽表格在窄屏转卡片或允许表内横向滚动；页面级 `scrollWidth <= innerWidth`。
- 验收：照 `verify-console.mjs` 的 `doc <= view` 口径，给 `verify-buyer.mjs` / `verify-workspace.mjs` 各加一格。

## 口径与红线

- **判据面零触碰**：gold 180 / 阈值 / 分母 / `judge()` / 既有验收判据一字不动；本票只加前端与（A2 的）契约字段。
- **ADR 原文不动**：需要开轮就写新 ADR，不改既有 ADR。
- **不引组件库**：ADR 0057 已否决「React + 全套组件库」，本票沿用自建 CSS（令牌 + 基础样式），不新增运行时 UI 依赖。
- **产物入库**：`static/buyer/`、`static/workspace/` 是入仓产物，CI 第九步逐字节校验。改源码必须 `npm run build`
  并提交产物；`verify-console.mjs` 会重写 `docs/console.png`，视觉变化要连同截图一起提交。
- **门禁语义不降**：三个 Playwright 门禁的承重格（静态放行、内部令牌不泄漏、跨买家隔离、领取/结单确认）原样保留；
  需要改步骤时只改引导方式，不改断言所量的东西。
- 一次只做一段（A → B → C），每段单独提交、单独跑验证；C 段可与 A/B 分开合并。

## Verify

```powershell
# 后端与契约（A2 会动 TicketView）
.\mvnw.cmd -B -ntp verify

# 两个前端
cd frontend-buyer; npm ci; npm run typecheck; npm run build
cd ../frontend-workspace; npm ci; npm run typecheck; npm run build

# 浏览器门禁（需活体栈；本机按清场日裁定，未实跑必须照登）
node scripts/verify-console.mjs
node scripts/verify-workspace.mjs
node scripts/verify-buyer.mjs

# 纪律
git diff --check; git status --short
```

## 现状取证（2026-10-20，建票时实测）

- 三个入口：`/` 调试台（单文件 42,479 B，零 `href`）、`/buyer/`（`App.vue` 250 行）、`/workspace/`（341 行）；
  两个 Vue 工程只依赖 `vue`，无组件库、无共享包。
- `TicketView` 无 `transcript`；`frontend-workspace/src/types.ts:11` 声明了它；`BuyerOwnTicketsTest` 的
  transcript 断言为真空断言。
- 结单永远传空 `note`（`App.vue:144`），而 `WorkItemService.resolve` 用它写审计与出站正文。
- 店铺清单读面存在（`/api/v1/support/ops/tenants`），但在鉴权面内。
- 两个产品页的首次加载会把「读取中」渲染成「没有工单」。
- 浏览器门禁：`verify-console.mjs`（含 390px 页脚溢出断言）、`verify-workspace.mjs`、`verify-buyer.mjs`。

## Handoff notes

（未开工。开工前先完成「开工前置」裁定并补 `Claimed by`；收口时补关键决策、验证落点与三个现场追问。）
