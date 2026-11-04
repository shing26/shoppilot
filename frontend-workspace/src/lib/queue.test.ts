/**
 * 队列筛选、排序与计数（round48 票 205 / ADR 0083）。
 *
 * <p>round47 的筛选一致性事故就出在「筛选结果」与「详情归属」的组合上；这里把筛选与排序
 * 钉成纯函数后，边界（空档、已超时、同优先级时间序）不必再靠活体页验证。
 */
import { describe, expect, it } from 'vitest'
import { countTickets, filterTickets } from './queue'
import type { Ticket } from '../types'

const NOW = Date.parse('2026-11-05T12:00:00Z')

function ticket(overrides: Partial<Ticket> = {}): Ticket {
  return {
    id: 'T-1',
    tenantId: 'T001',
    customerId: 'C001',
    reason: 'TOOL_UNAVAILABLE',
    userQuery: '查订单',
    status: 'OPEN',
    priority: 'normal',
    createdAt: '2026-11-05T00:00:00Z',
    source: 'DEGRADE',
    queue: 'ESCALATION',
    assignee: null,
    slaDeadline: null,
    escalatedAt: null,
    transcript: '',
    payload: null,
    ...overrides,
  }
}

describe('filterTickets', () => {
  it('ALL 按优先级升序：high → money → normal', () => {
    const rows = filterTickets(
      [
        ticket({ id: 'normal', priority: 'normal' }),
        ticket({ id: 'money', priority: 'money' }),
        ticket({ id: 'high', priority: 'high' }),
      ],
      'ALL',
      NOW,
    )
    expect(rows.map((row) => row.id)).toEqual(['high', 'money', 'normal'])
  })

  it('同优先级按创建时间倒序', () => {
    const rows = filterTickets(
      [
        ticket({ id: 'old', createdAt: '2026-11-04T00:00:00Z' }),
        ticket({ id: 'new', createdAt: '2026-11-05T00:00:00Z' }),
      ],
      'ALL',
      NOW,
    )
    expect(rows.map((row) => row.id)).toEqual(['new', 'old'])
  })

  it('OPEN / ASSIGNED 只留对应状态', () => {
    const rows = [
      ticket({ id: 'open', status: 'OPEN' }),
      ticket({ id: 'assigned', status: 'ASSIGNED', assignee: 'alice' }),
      ticket({ id: 'resolved', status: 'RESOLVED', assignee: 'alice' }),
    ]
    expect(filterTickets(rows, 'OPEN', NOW).map((row) => row.id)).toEqual(['open'])
    expect(filterTickets(rows, 'ASSIGNED', NOW).map((row) => row.id)).toEqual(['assigned'])
  })

  it('OVERDUE 同时认「已打超时戳」与「SLA 已过」，未到期的不算', () => {
    const rows = [
      ticket({ id: 'stamped', escalatedAt: '2026-11-05T11:00:00Z' }),
      ticket({ id: 'late', slaDeadline: '2026-11-05T11:00:00Z' }),
      ticket({ id: 'future', slaDeadline: '2026-11-05T13:00:00Z' }),
      ticket({ id: 'no-sla' }),
    ]
    expect(filterTickets(rows, 'OVERDUE', NOW).map((row) => row.id).sort()).toEqual(['late', 'stamped'])
  })

  it('排序不就地改写入参数组', () => {
    const rows = [ticket({ id: 'normal' }), ticket({ id: 'high', priority: 'high' })]
    filterTickets(rows, 'ALL', NOW)
    expect(rows.map((row) => row.id)).toEqual(['normal', 'high'])
  })
})

describe('countTickets', () => {
  it('三个 KPI 与列表口径一致', () => {
    const rows = [
      ticket({ id: 'open' }),
      ticket({ id: 'assigned', status: 'ASSIGNED', assignee: 'alice' }),
      ticket({ id: 'late', slaDeadline: '2026-11-05T11:00:00Z' }),
      ticket({ id: 'done', status: 'RESOLVED', assignee: 'alice' }),
    ]
    expect(countTickets(rows, NOW)).toEqual({ open: 2, assigned: 1, overdue: 1 })
  })
})
