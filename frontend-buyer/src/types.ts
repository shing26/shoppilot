/**
 * 买家端契约镜像（round27 票 93；round36 票 151 与坐席端共用 `frontend-shared`）。
 *
 * <p>共用字段与标签映射已下沉到 `frontend-shared`（`TicketSummary` / `STATUS_LABEL` /
 * `PRIORITY_LABEL` / `minutesLeft`），这里只保留买家侧特有的部分。
 *
 * <p>⚠️ 买家视角**没有 `transcript`**：`WorkItemService.toBuyerView` 刻意置空。
 * 它不是在共用类型上「少写一格」——共用类型里本来就没有这一格，坐席视角在自己的
 * `frontend-workspace/src/types.ts` 里扩展它。
 */
import type { TicketSummary } from 'frontend-shared'

export type { TicketSummary }
export { PRIORITY_LABEL, STATUS_LABEL, minutesLeft } from 'frontend-shared'

/** 买家看到的工单：共用字段的全集，没有坐席专属的 transcript。 */
export type Ticket = TicketSummary

/** 一条引用（政策条款），与网关 `citations` 同形。 */
export interface Citation {
  ruleId?: string
  doc?: string
  heading?: string
  text?: string
}

/**
 * 一条引用的可读详情（round33 / ADR 0067），与网关 `citationDetails` 同形。
 *
 * <p>网关的 `citations` 是**字符串数组**（反馈账本与旧脚本按它读，契约不动），只带 ruleId；
 * 标题与出处在新字段里。两者同源同序，页面优先读这一份——否则引用列表会退化成
 * 一条条「政策条款」。
 */
export interface CitationDetail {
  ruleId: string
  heading: string
  doc: string
}

/** 登录态：只有登录换来的那一张令牌（会话存 sessionStorage，关掉标签页即登出）。 */
export interface Session {
  token: string
  accountId: string | null
  username: string
  role: string
  tenantId: string
  customerId: string | null
  displayName: string | null
}
