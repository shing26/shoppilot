/**
 * 前端契约镜像（round27 票 93）。
 *
 * <p>字段与 `com.shoppilot.tool.view.TicketView` 逐项对齐——那份是契约的唯一来源，
 * 这里只是它在前端的样子；多一个少一个都会把「读不到」变成「读错」。
 *
 * <p>⚠️ 买家这一份**没有 `transcript`**（票 92 的 `toBuyerView` 刻意少那一格）：
 * 坐席与买家之间的会话原文不是给买家看的。留着这一格会让「前端多一个字段」
 * 看起来像无害，而它其实是一条数据可见性的承诺。
 */
export interface Ticket {
  id: string
  tenantId: string
  customerId: string
  reason: string
  userQuery: string
  status: string
  priority: string | null
  createdAt: string
  source: string | null
  queue: string | null
  assignee: string | null
  slaDeadline: string | null
  escalatedAt: string | null
}

/** 一条引用（政策条款），与网关 `citations` 同形。 */
export interface Citation {
  ruleId?: string
  doc?: string
  heading?: string
  text?: string
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

/** 状态的买家视角说法。领域枚举是 TicketStatus 的 OPEN/ASSIGNED/RESOLVED。 */
export const STATUS_LABEL: Record<string, string> = {
  OPEN: '等待处理',
  ASSIGNED: '处理中',
  RESOLVED: '已处理完成',
}

/** 优先级的可读名：存储字面量 high/money/normal 在页面上对齐成一处。 */
export const PRIORITY_LABEL: Record<string, string> = {
  high: '紧急',
  money: '资金',
  normal: '普通',
}

/** SLA 剩余分钟数；已超时为负（前端据此显示「已超时」，但不改状态）。 */
export function minutesLeft(deadline: string | null, now: number): number | null {
  if (!deadline) return null
  return Math.round((new Date(deadline).getTime() - now) / 60000)
}