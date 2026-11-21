import type { Capability } from '../api'
import type { WorkspaceView } from './ui'

/**
 * 工作台导航与能力判定（票 221）。
 *
 * <p>这份表只决定「画不画入口」；真正的边界在后端。把纯函数留在这里，
 * 是为了让菜单可见性可以在没有 DOM 的 vitest 里钉住，而不是靠截图猜。
 */
export interface NavItem {
  view: WorkspaceView
  label: string
  capability: Capability
  group: 'work' | 'insight'
}

export const NAV_ITEMS: readonly NavItem[] = [
  { view: 'queue', label: '工单队列', capability: 'tickets', group: 'work' },
  { view: 'conversations', label: '渠道会话', capability: 'conversations', group: 'work' },
  { view: 'refunds', label: '退款审核', capability: 'refunds', group: 'work' },
  { view: 'knowledge', label: '知识飞轮', capability: 'knowledge', group: 'insight' },
  { view: 'quality', label: '编排质量', capability: 'quality', group: 'insight' },
  { view: 'channels', label: '渠道连接', capability: 'channels', group: 'insight' },
]

const AGENT_CAPABILITIES: readonly Capability[] = [
  'tickets',
  'conversations',
  'refunds',
  'knowledge',
  'quality',
]

const ADMIN_CAPABILITIES: readonly Capability[] = [...AGENT_CAPABILITIES, 'channels', 'tenants']

const KNOWN_CAPABILITIES: readonly Capability[] = ADMIN_CAPABILITIES

export function hasCapability(
  capabilities: readonly string[] | undefined | null,
  capability: Capability,
): boolean {
  return !!capabilities && capabilities.includes(capability)
}

export function canView(
  capabilities: readonly string[] | undefined | null,
  view: WorkspaceView,
): boolean {
  const item = NAV_ITEMS.find((candidate) => candidate.view === view)
  return !!item && hasCapability(capabilities, item.capability)
}

export function visibleViews(capabilities: readonly string[] | undefined | null): WorkspaceView[] {
  return NAV_ITEMS.filter((item) => hasCapability(capabilities, item.capability))
    .map((item) => item.view)
}

export function firstVisibleView(
  capabilities: readonly string[] | undefined | null,
): WorkspaceView | null {
  return visibleViews(capabilities)[0] ?? null
}

/**
 * 221 之前存进 sessionStorage 的会话没有 `capabilities`。
 *
 * <p>只在读取旧会话时按角色补齐；新登录一律以服务端返回为准。这不是前端授权判定，
 * 后端仍会对每个端点独立返回 403。这层兼容只是为了升级后不必强制所有人重新登录。
 */
export function legacyCapabilitiesForRole(role: string): Capability[] {
  if (role === 'ADMIN') return [...ADMIN_CAPABILITIES]
  if (role === 'AGENT') return [...AGENT_CAPABILITIES]
  return []
}

/**
 * 归一化登录响应或旧会话里的能力数组：只保留已知能力，未知值静默丢弃。
 * 字段缺失时按角色走旧会话兼容；服务端返回空数组时保持空数组（买家就是空）。
 */
export function normalizeCapabilities(capabilities: unknown, role: string): Capability[] {
  if (Array.isArray(capabilities)) {
    return capabilities.filter((value): value is Capability =>
      typeof value === 'string' && (KNOWN_CAPABILITIES as readonly string[]).includes(value),
    )
  }
  return legacyCapabilitiesForRole(role)
}
