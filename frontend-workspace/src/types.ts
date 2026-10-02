/**
 * 工单视图的前端镜像（round23 票 73）。字段与 `com.shoppilot.tool.view.TicketView` 逐项对齐——
 * 那份是契约的唯一来源，这里只是它在前端的样子；多一个少一个都会让「读不到」变成「读错」。
 */
export interface Ticket {
  id: string
  tenantId: string
  customerId: string
  reason: string
  userQuery: string
  transcript: string
  status: string
  /** 存储字面量 high/money/normal（领域名是 URGENT_EMOTION/MONEY/NORMAL，见 TicketPriority）。 */
  priority: string | null
  createdAt: string
  /** DEGRADE / FEEDBACK_REVIEW / REFUND_APPROVAL / CHANNEL_RECEIPT */
  source: string | null
  queue: string | null
  assignee: string | null
  slaDeadline: string | null
  /** 有上游记录时指回上游 id 的 JSON；自包含来源为 null。 */
  payload: string | null
  /** 非空即代表已被观测到超时——**工单状态不变**，超时不是关闭。 */
  escalatedAt: string | null
}

export const QUEUES = ['ALL', 'ESCALATION', 'REFUND', 'REVIEW', 'REPLY', 'DEFAULT'] as const

/** 优先级排序用的名次：越小越靠前。与后端 `TicketPriority.rank` 同一份口径。 */
export function priorityRank(priority: string | null): number {
  if (priority === 'high') return 0
  if (priority === 'money') return 1
  return 2
}

/** SLA 剩余分钟数；已超时为负（前端据此显示「已超时」，但不改状态）。 */
export function minutesLeft(deadline: string | null, now: number): number | null {
  if (!deadline) return null
  return Math.round((new Date(deadline).getTime() - now) / 60000)
}