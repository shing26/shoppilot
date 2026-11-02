# 票 189：深度卡片——订单详情 / 物流轨迹 / 工单生命周期

Round: 44（spec：[round44-spec-product-surface.md](../round44-spec-product-surface.md)）
ADR: 0079　Status: ready-for-agent　Blocked by: 188

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

（收口时补：关键决策 / 验证落点 / 三个现场追问）
