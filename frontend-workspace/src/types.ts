/**
 * 坐席端契约镜像（round23 票 73；round36 票 151 与买家端共用 `frontend-shared`）。
 *
 * <p>共用字段与标签映射已下沉到 `frontend-shared`（`TicketSummary` / `STATUS_LABEL` /
 * `PRIORITY_LABEL` / `priorityRank` / `minutesLeft`）。这里只保留坐席侧特有的两格：
 * `transcript`（会话原文，买家视角恒为 null）与 `payload`（上游记录指回）。
 */
import type { TicketSummary } from 'frontend-shared'

export { PRIORITY_LABEL, STATUS_LABEL, minutesLeft, priorityRank } from 'frontend-shared'

/** 坐席看到的工单：共用字段 + 会话原文 + 上游记录指回。 */
export interface Ticket extends TicketSummary {
  /** 会话原文；契约里恒有该字段（买家视角为 null），长度上限 8000。 */
  transcript: string
  /** 有上游记录时指回上游 id 的 JSON；自包含来源为 null。 */
  payload: string | null
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

/** 工单来源（`TicketSource`）的可读名；未知值原样透出。 */
export const SOURCE_LABEL: Record<string, string> = {
  DEGRADE: '降级转人工',
  FEEDBACK_REVIEW: '点踩复核',
  REFUND_APPROVAL: '退款审批',
  CHANNEL_RECEIPT: '渠道回执',
}
