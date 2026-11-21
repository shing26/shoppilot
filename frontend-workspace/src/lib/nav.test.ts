import { describe, expect, it } from 'vitest'
import {
  canView,
  firstVisibleView,
  legacyCapabilitiesForRole,
  normalizeCapabilities,
  visibleViews,
} from './nav'

describe('workspace capability navigation', () => {
  it('管理员看到全部六个入口', () => {
    expect(visibleViews(legacyCapabilitiesForRole('ADMIN'))).toEqual([
      'queue',
      'conversations',
      'refunds',
      'knowledge',
      'quality',
      'channels',
    ])
  })

  it('坐席看不到渠道连接', () => {
    expect(visibleViews(legacyCapabilitiesForRole('AGENT'))).toEqual([
      'queue',
      'conversations',
      'refunds',
      'knowledge',
      'quality',
    ])
    expect(canView(legacyCapabilitiesForRole('AGENT'), 'channels')).toBe(false)
    expect(canView(legacyCapabilitiesForRole('ADMIN'), 'channels')).toBe(true)
  })

  it('买家没有任何工作台入口', () => {
    expect(visibleViews([])).toEqual([])
    expect(firstVisibleView([])).toBeNull()
  })

  it('首入口按固定顺序取第一个可用视图', () => {
    expect(firstVisibleView(['quality', 'tickets'])).toBe('queue')
    expect(firstVisibleView(['quality'])).toBe('quality')
  })

  it('旧会话缺 capabilities 时按角色补齐', () => {
    expect(normalizeCapabilities(undefined, 'AGENT')).toEqual([
      'tickets',
      'conversations',
      'refunds',
      'knowledge',
      'quality',
    ])
    expect(normalizeCapabilities(undefined, 'ADMIN')).toContain('channels')
    expect(normalizeCapabilities(undefined, 'BUYER')).toEqual([])
  })

  it('服务端返回的空数组保持为空，不被旧会话回退覆盖', () => {
    expect(normalizeCapabilities([], 'ADMIN')).toEqual([])
  })

  it('未知能力被丢弃，已知能力保持服务端顺序', () => {
    expect(normalizeCapabilities(['quality', 'unknown', 'tickets'], 'AGENT')).toEqual([
      'quality',
      'tickets',
    ])
  })
})
