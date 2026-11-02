# 票 189：深度卡片——订单详情 / 物流轨迹 / 工单生命周期

Round: 44（spec：[round44-spec-product-surface.md](../round44-spec-product-surface.md)）
ADR: 0079　Status: implemented　Blocked by: 188

## What to build

1. **视图契约**（`shoppilot-tool-api/src/main/java/com/shoppilot/tool/view/`，与 `OrderView` 同包）：
   - `OrderDetailView`：`orderNo`、`amountFen`、`status`、`categoryName`、`serviceFlags`、
     `createdAt`、`paidAt`、`shippedAt`、`deliveredAt`、`addressVersion`，以及地址块
     （`receiverName`、**已脱敏** `receiverPhone`、`province`/`city`/`district`/`detailAddress`）。
   - `LogisticsNodeView`：`cpCode`、`cpName`、`trackingNo`、`seq`、`nodeCode`、`description`、
     `occurredAt`。
2. **biz-mock 查询口**（`OrderQueryController`）：
   - `GET /api/orders/mine/{orderNo}` → `OrderDetailView`。
   - `GET /api/orders/mine/{orderNo}/logistics` → `List<LogisticsNodeView>`，按 `occurredAt` 倒序。
   - 归属：`@TenantId` 自动过滤 + `customerId` 显式比对（ADR 0004 的第二条件）。
     越权一律 **404 不确认存在**，与全仓语义一致；订单存在但无轨迹返回**空数组**（不是 404）。
   - **手机号在 biz-mock 侧脱敏**（`138****5678`）：脱敏放前端等于原文过网并进浏览器内存。
3. **网关转发**：买家订单代理口所在 controller 增两条等价转发，沿用既有用户上下文透传与
   守卫取或语义；`frontend-buyer/src/api.ts` 增 `orderDetail(orderNo)` / `orderLogistics(orderNo)`。
4. **买家端深度卡**（`OrderPanel` 相关组件内）：
   - 状态推进条：下单 → 付款 → 发货 → 签收，用三个时间戳点亮；未到达的节点显灰。
   - 金额、品类、服务标 chips、脱敏收货地址。
   - 展开**物流时间轴**：承运商 + 运单号（可复制）+ 节点描述 + 时间，最新节点高亮。
   - 空态：无轨迹时给明确说明，不显示空白时间轴。
5. **工作台深度渲染**（全部用已有数据，零后端）：
   - 工单详情 `transcript` 从裸 `pre` 改成对话气泡（角色左右分列，保留原文不解析）。
   - 生命周期时间轴：创建 → 升级（`escalatedAt`）→ 领取（`assignee`）→ 结单（`resolutionNote`），
     配合 `types.ts` 已有的 `SOURCE_LABEL` / `QUEUE_LABEL`。
   - SLA：把列表里已有的剩余时间计算接进详情，超时态与列表同口径。
   - 退款队列：金额/原因之外补决策路径，并与 `source=REFUND_APPROVAL` 的工单对应起来。
6. **测试**：
   - biz-mock `OrderDetailQueryTest`：本人可见 / 同店他人 404 / 跨店 404 / 轨迹按时间倒序 /
     无轨迹返回空数组 / 手机号已脱敏（断言不含原始 11 位）。
   - 网关：代理路径、租户头、买家上下文透传断言。

## Blocked by / Verify

188。Verify：

```powershell
.\mvnw.cmd -B -ntp verify
cd frontend-buyer; npm run typecheck; npm run build; cd ..
cd frontend-workspace; npm run typecheck; npm run build; cd ..
git diff --check
git status --short
```

## 验收项

- 越权语义是硬判据：同店他人、跨店都必须 404，且响应体不泄露订单是否存在。
- 手机号脱敏在 biz-mock 侧完成，网关与前端拿到的就不是原文。
- 新增用例走真实 `InternalAuthFilter` 上下文，不 mock 租户谓词。
- 前端 typecheck/build 绿，`static/buyer` 产物整目录入库。
- 无轨迹时买家端不给空白时间轴。
- 不新增与订单/物流无关的后端面；工作台侧不改任何后端。

## Handoff notes

### 关键决策

1. **`LogisticsNodeView` 早已存在，票面说"新建"是过时的。** 它只有
   `seq/nodeCode/description/occurredAt`，承运商与运单号在 `LogisticsView` 顶层。
   本票没有扩展它，而是新增 `OrderLogisticsView`——理由不是"多一个 record 好看"：
   `LogisticsView` 喂给模型、节点升序、`latest()` 取**末元素**；买家端要倒序、`latest()` 取
   **首元素**。同一形状承载两种排序语义，任何一侧改排序都会让另一侧悄悄变旧。

2. **新增 `OrderDetailView` 而不是扩 `OrderView`。** `OrderView` 是工具回执契约，多出的
   `deliveredAt` / `addressVersion` 只有买家页面要，不该顺带进模型上下文。更关键的是
   `OrderView.address.receiverPhone` 是**原文**（`GET /orders/mine` 今天就带着它出站），
   新入口单独出视图，才可能只在新入口脱敏而不动工具面。

3. **买家入口不复用 `findOwned()`。** 那个方法在没有买家上下文时回退成"整店可查"，坐席工具
   路径需要它；买家入口继承这个回退就等于"不带买家号能看全店"。新加 `buyerOwned()` 两个条件
   都必填，缺买家号返回空 → 404。

4. **脱敏在 biz-mock 出站前完成**（`BizMockService.maskPhone`：留前 3 后 4，中间固定四个星号）。
   用例除了断言格式，还断言**响应体不含数据库里的原文号码**——只测格式会漏掉"旁边另有一个字段
   把原文带出去"。

5. **网关把下游 404 原样传成 404。** 原 `mine()` 把所有非 2xx 折成 502；折成 502 会让
   "这单存在但不是你的"从状态码上露出来，与"不存在"区分开。

6. **订单号先过白名单正则再拼 URL**（`[A-Za-z0-9_-]{1,32}`）。`URI.create(base + "/mine/" + orderNo)`
   里一个含 `..` 的订单号会把请求带到没打算暴露的路径；校验失败直接 404，与越权同形。

7. **工作台 `transcript` 不是对话记录，票面"角色左右分列的气泡"按字面做会伪造对话。**
   它实际是降级出口写下的 `detail`：`emotion=URGENT via LEXICON`、`handoff blocked reason=…`、
   工具回执 JSON（`shoppilot-gateway/.../AgentStateMachine.java:1335` 把 `detail` 当 transcript 传）。
   `frontend-workspace/src/types.ts` 早就写明"不是完整对话记录——页面按这个口径展示"。
   落地形态：买家提问一个右对齐气泡 + 系统记录一个左对齐块（**原文不解析**），并在块下写明口径。
   这处偏差是票面与数据事实冲突，不是省事。

8. **退款与审批工单按 `payload.refundId` 对上**，不按订单号猜——biz-mock 开单时写死的
   `{"refundId":"…","orderId":"…"}` 是唯一可靠键；同一单多次申请时按订单号会对错。

9. **SLA 接进详情这半条本票无事可做**：票 188 拆分时 `QueueView` 的详情列与生命周期末步
   已经用 `slaText(selected, now)`，与列表同口径。这里只确认，没有二次实现。

### 验证落点

| 命令 | 结果 |
| --- | --- |
| `mvn -pl shoppilot-biz-mock,shoppilot-gateway -am test -Dtest=OrderDetailQueryTest,BuyerOrdersEndpointTest` | 绿：biz-mock 7 条、gateway 6 条 |
| `cd frontend-buyer && npm run typecheck && npm run build` | 绿，单 bundle `index-Cq_ZANWR.js` 104.99 kB + `index-BUH4AQWa.css` 24.46 kB |
| `cd frontend-workspace && npm run typecheck && npm run build` | 绿，单 bundle `index-cpbuag95.js` 121.53 kB + `index-CQ6pD8Tl.css` 23.18 kB |
| `bash ./mvnw -B -ntp verify` | BUILD SUCCESS；四模块 12 / **89** / **617** / 50，合计 **768**（较票 188 的 757 增 11，正是本票新增用例） |
| `git diff --check` | 干净 |
| `git status --short` | 无日志、无崩溃转储、无过期快照；产物新增/删除两端全部入库 |

biz-mock `OrderDetailQueryTest` 的七条：本人可见且脱敏、同店他人 404、跨店 404、缺买家上下文 404、
轨迹倒序、无轨迹 200 + 空数组、轨迹越权 404。gateway `BuyerOrdersEndpointTest` 的六条：身份透传、
200 原样回、详情路径、轨迹路径、下游 404 保形、白名单外**根本不发下游请求**。

### 三个现场追问

1. **`GET /orders/mine` 仍然带着未脱敏的手机号。** 本票只给新入口脱敏，没动列表接口——它是
   ADR 0075 的既有面，改它会同时改变买家列表的响应体形状（`OrderView.address` 在工具路径上
   也被模型读）。这是**已登记的遗留**：要收口应单开一票，决定是"列表也不带地址"还是"地址块
   统一走脱敏视图"。
2. **`OrderDetailView` 与 `OrderView` 字段重复度很高。** 目前拆开的收益是"模型上下文不变 + 手机号
   可在新面单独脱敏"；若将来票 190 之后再加两个字段，就该考虑 `OrderView` 内部改用脱敏 `AddressView`，
   而不是继续加平行 record。
3. **`payloadField` 只认字符串字段。** 现有三条来源（退款、复核、渠道回执）的 payload 都是扁平
   JSON；若将来出现嵌套结构，`payloadField` 会静默返回 `null`，页面表现为"找不到对应工单"而不是报错。
   要嵌套就先扩这个函数，别在组件里各写一份 `JSON.parse`。

