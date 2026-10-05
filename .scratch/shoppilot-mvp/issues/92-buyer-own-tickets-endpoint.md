# 92 买家自己的工单列表

**Status:** implemented（2026-10-04）

## What to build

买家端页面的**唯一新依赖**：一个「我的工单」查询。现在 `GET /api/v1/support/ops/tickets` 要运维凭证，
而买家不该有那个凭证。

- **工单服务**：`TicketRepository` 加按 `customerId` 的派生查询；
  `WorkItemService.listOwn(customerId)`；
  `GET /api/tickets/mine`（`X-Internal-Token` + 租户上下文之后）。
- **网关**：`GET /api/v1/support/tickets` —— 买家侧入口，
  **不带任何买家号参数**：买家号一律取自已验签的 `TenantContext`。
- 若传了 `customerId` 之类的查询参数：**忽略**，并在 README 与 ADR 里写明这一点。

## Blocked by

无。round26 全部收口（[90](90-round26-closeout.md)）。

## 口径

- **隔离是第一位的**：返回集必须与「本租户 ∧ 本买家」相等。同租户另一买家的单**一条都不许出现**。
- 排序沿用坐席台那一套：优先级 → 创建时间倒序（一致口径，不为买家端另发明一个）。
- **不返回 `transcript`**：那是坐席看的会话原文。买家只看自己的诉求、状态、进度与处理结论。
- 判据面零改动。

## 验收

- JVM 用例覆盖：买家 A 拿到自己的若干张单；**同租户买家 B 的工单号一条都不在 A 的结果里**；
  **请求里带 `customerId=B` 参数也不改变结果**（仍只返回 A 的）；跨租户 404；
  返回体里没有 `transcript` 这一格。
- **变异对照**：把查询的 `customerId` 过滤去掉 → 前两条必须红（这一格就是靠它承重的）。

## Verify

```powershell
.\mvnw.cmd -B -ntp verify
```

## Handoff notes

### 落点

- `TicketRepository.findByTenantIdAndCustomerIdOrderByCreatedAtDesc`（新派生查询；`customerId` 是**方法参数**，不是从上下文取——调用方已验过签，仓储这一层再加一次租户谓词）。
- `WorkItemService.listOwn(customerId)` + `toBuyerView`（**买家视角少一格**：不带 `transcript`）。
- 工单服务 `GET /api/tickets/mine`（内部凭证 + 租户上下文之后）。
- 网关 `GET /api/v1/support/ops/tickets/mine`：**只过买家 JWT，不要 ops 凭证**。

### 两处刻意如此

1. **端点挂在 ops 命名空间下，但不需要 ops 凭证**。运维面与买家面共用一条 URL 前缀，
   而「我的工单」是买家自己的数据（ADR 0005 的会话归属：按店铺 + 买家）。
   买家不该有运维凭证，所以那一格没有凭证门。
2. **买家这一侧少一格 `transcript`**：那是坐席看的会话原文。买家看自己的诉求、状态、
   优先级与处理结论就够了，坐席与买家之间的那些来回不是给他看的。

### 变异对照实测为真

把查询换成不带 `customerId` 的 `findAllByOrderByCreatedAtDesc()` → **2 条红**
（`seesOnlyItsOwnTickets`、`ignoresBuyerIdFromTheRequest`）。这正是本票承重的那一格。

### 读数

| 项 | 值 |
|---|---|
| JVM 全仓 | `10 + 57 + 338 + 37 = 442`（gateway 336 → **338**，ticket 32 → **37**） |
| 覆盖率 | 四模块全过（`COVERAGE OK modules=4`） |

**一条判据都没动。**

### 现场三问

1. **为什么买家号不从查询参数读？** 那等于任何人传一个别人的买家号来读别人的单（ADR 0005 防线一）。
   用例 `ignoresBuyerIdFromTheRequest` 钉的就是这一格。
2. **为什么排序用时间倒序而不用坐席台那套优先级序？** 买家找的是「我最近问过什么、现在到哪一步了」；
   坐席台那个序是给「先处理紧急」用的。两个页面两种序，各有其道理。
3. **为什么不复用 `TicketView` 直接返回？** 复用会让 `transcript` 一起漏出去。
   `toBuyerView` 是同形状少一格——复用形状，不复用内容。