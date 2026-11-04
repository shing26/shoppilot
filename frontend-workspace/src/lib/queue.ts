/**
 * 队列的筛选、排序与 KPI 计数（round48 票 205 / ADR 0083，从 `App.vue` 下沉）。
 *
 * <p>这是坐席台最常改、也最容易回归的一段纯逻辑：状态筛选与「已超时」的判读结果必须
 * 和列表展示一致；排序是「优先级升序，同优先级按创建时间倒序」——round47 的筛选一致性
 * 事故就出在这两者的组合上。下沉后 Vitest 可以直接钉住顺序与边界，不再依赖活体页。
 *
 * <p>语义与下沉前逐字一致：`ALL` 不过滤；`OVERDUE` 走 `isOverdue`（含已打超时戳的单）。
 */
import { priorityRank, type Ticket } from '../types'
import { isOverdue } from './format'
import type { StatusFilter } from './ui'

export interface TicketCounts {
  open: number
  assigned: number
  overdue: number
}

/** 按状态筛选后按「优先级升序 + 创建时间倒序」排；不改动入参数组。 */
export function filterTickets(tickets: Ticket[], filter: StatusFilter, now: number): Ticket[] {
  const rows = tickets.filter((ticket) => {
    if (filter === 'ALL') return true
    if (filter === 'OPEN') return ticket.status === 'OPEN'
    if (filter === 'ASSIGNED') return ticket.status === 'ASSIGNED'
    return isOverdue(ticket, now)
  })
  return rows.sort((a, b) => {
    const byPriority = priorityRank(a.priority) - priorityRank(b.priority)
    return byPriority !== 0 ? byPriority : b.createdAt.localeCompare(a.createdAt)
  })
}

/** 队列头部的三个 KPI；口径与列表筛选相同。 */
export function countTickets(tickets: Ticket[], now: number): TicketCounts {
  return {
    open: tickets.filter((ticket) => ticket.status === 'OPEN').length,
    assigned: tickets.filter((ticket) => ticket.status === 'ASSIGNED').length,
    overdue: tickets.filter((ticket) => isOverdue(ticket, now)).length,
  }
}
