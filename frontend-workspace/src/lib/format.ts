/**
 * 工作台的格式化与判读工具（round44 票 188 从 `App.vue` 拆出）。
 *
 * <p>与买家端刻意不同的一处：时间缺失时显示 `—` 而不是空串——坐席是在表格与卡片里
 * 扫读，"这一格是空的"与"这一格没有值"要能一眼分开。所以共享层只给 `formatStamp`
 * 的格式化结果，兜底字面量留在各自入口。
 */
import { formatStamp, minutesLeft } from 'frontend-shared'
import type { Ticket } from '../types'

export function isOverdue(ticket: Ticket, now: number): boolean {
  if (ticket.escalatedAt) return true
  const left = minutesLeft(ticket.slaDeadline, now)
  return left !== null && left < 0
}

export function slaText(ticket: Ticket, now: number): string {
  if (ticket.escalatedAt) return '已超时'
  const left = minutesLeft(ticket.slaDeadline, now)
  if (left === null) return '无 SLA'
  return left >= 0 ? `剩 ${left} 分钟` : `超时 ${-left} 分钟`
}

export function formatTime(value: string | null | undefined): string {
  return formatStamp(value) ?? '—'
}

export function ratioText(value: number | undefined | null): string {
  return `${((value ?? 0) * 100).toFixed(1)}%`
}

export function secondsText(value: number | undefined | null): string {
  const millis = (value ?? 0) * 1000
  return millis >= 1 ? `${millis.toFixed(0)}ms` : `${millis.toFixed(2)}ms`
}

export function entriesOf(map: Record<string, number> | undefined | null): Array<[string, number]> {
  return Object.entries(map ?? {}).sort((a, b) => b[1] - a[1])
}
