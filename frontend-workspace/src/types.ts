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

/** 队列的可读名（round36 / 票 151）。未知值原样透出，不吞。 */
export const QUEUE_LABEL: Record<string, string> = {
  ALL: '全部',
  ESCALATION: '转人工',
  REFUND: '退款',
  REVIEW: '复核',
  REPLY: '回复',
  DEFAULT: '默认',
}

/** 状态的坐席视角说法；与买家端同一口径（`frontend-buyer/src/types.ts`）。 */
export const STATUS_LABEL: Record<string, string> = {
  OPEN: '等待处理',
  ASSIGNED: '处理中',
  RESOLVED: '已处理完成',
}

/** 工单来源（`TicketSource`）的可读名；未知值原样透出。 */
export const SOURCE_LABEL: Record<string, string> = {
  DEGRADE: '降级转人工',
  FEEDBACK_REVIEW: '点踩复核',
  REFUND_APPROVAL: '退款审批',
  CHANNEL_RECEIPT: '渠道回执',
}

/** 优先级的可读名：领域名（TicketPriority）与存储字面量（high/money/normal）在页面上对齐成一处。 */
export const PRIORITY_LABEL: Record<string, string> = {
  high: '紧急',
  money: '资金',
  normal: '普通',
}

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