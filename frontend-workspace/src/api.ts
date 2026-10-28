import { messageOf, request, type ApiResult } from 'frontend-shared'
import type { Ticket } from './types'

/**
 * 与运维调试台同一套失败口径（`index.html` 的 `api()`，票 61 修过的那条）。
 *
 * <p>round36 / 票 151 起请求封装下沉到 `frontend-shared`：网络级失败（fetch reject）收进
 * 同一个出口，返回 `status: 0` 加一句为什么，不留下未捕获的 `Failed to fetch`。
 *
 * <p>票据 83 起**只有一种凭证**：坐席账号登录换来的令牌。
 * 此前这一层要同时带 {@code X-Ops-Token} 与自报坐席名——而自报的那个名字在网关侧
 * 会被令牌里的账号覆盖，页面上那个输入框其实已经不起作用了（ADR 0058 第 4 条）。
 * 留着它只会让人以为「改个名字就能换个人」。
 */
export { messageOf }
export type { ApiResult }

const BASE = '/api/v1/support/ops'

/** 登录态。票据 83 起它只有一件东西：登录换来的令牌。 */
export interface Session {
  token: string
  username: string
  role: string
  tenantId: string
  customerId: string | null
}

export function api<T>(method: string, path: string, opts: { token?: string; body?: unknown } = {}): Promise<ApiResult<T>> {
  return request<T>(BASE + path, { ...opts, method })
}

/**
 * 一键体验登录（round37）：服务端用演示口令换真账号令牌，浏览器不接触口令。
 *
 * <p>端点同样只回环注册；非回环部署返回 404，页面回落为账号口令登录。
 */
export function demoLogin(tenantId: string, role: 'agent' | 'admin'): Promise<ApiResult<Session>> {
  return request<Session>('/auth/demo-login', { method: 'POST', body: { tenantId, role } })
}

/**
 * 队列列表。
 *
 * <p>三种失败各有各的处置，所以它们都必须原样回到调用方：**0** 是「读不到网关」、
 * **401/403** 是「凭证不对」、**其余非 2xx** 是下游的问题。三者混成一句「加载失败」
 * 就是调试台那轮修掉的毛病。
 */
export function listTickets(queue: string, session: Session): Promise<ApiResult<Ticket[]>> {
  const query = queue && queue !== 'ALL' ? `?queue=${encodeURIComponent(queue)}` : ''
  return api<Ticket[]>('GET', `/tickets${query}`, { token: session.token })
}

/** 领取。204 领到、409 已被别人领走、404 不存在或不是本店的（工单服务侧保证）。 */
export function claimTicket(id: string, session: Session): Promise<ApiResult<unknown>> {
  return api<unknown>('POST', `/tickets/${encodeURIComponent(id)}/claim`, { token: session.token })
}

export function releaseTicket(id: string, session: Session): Promise<ApiResult<unknown>> {
  return api<unknown>('POST', `/tickets/${encodeURIComponent(id)}/release`, { token: session.token })
}

export function resolveTicket(id: string, session: Session, note: string): Promise<ApiResult<unknown>> {
  return api<unknown>('POST', `/tickets/${encodeURIComponent(id)}/resolve`, { token: session.token, body: { note } })
}

/**
 * 登录：账号换令牌。
 *
 * <p>走 `/auth/login` 而不是 ops 前缀下的路径——登录面本来就在 `/auth/` 下，
 * 而 `AuthFilter` 对那个前缀整体放行（登录页拿不到令牌）。
 *
 * <p>失败 401，文案由网关给（用户不存在与口令错是同一句，见 biz-mock 侧的口径）。
 */
export async function login(tenantId: string, username: string, password: string): Promise<ApiResult<Session>> {
  let res: Response
  try {
    res = await fetch('/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ tenantId, username, password }),
    })
  } catch (unreachable) {
    const reason = unreachable instanceof Error ? unreachable.message : 'network error'
    return { status: 0, body: { message: `网关不可达（${reason}）` } }
  }
  const text = await res.text()
  try {
    return { status: res.status, body: (text ? JSON.parse(text) : {}) as Session }
  } catch (badJson) {
    return { status: res.status, body: { message: text || '响应不是 JSON' } }
  }
}

/**
 * 取下游给的一句话（若有）。
 *
 * <p>单独一个函数而不是让每个调用点 cast：body 是 `T | { message? }`，
 * 散着写就会出现三处各写一遍的类型断言——那种地方迟早漏一处，而漏的那处编译不出来。
 */
/** 把一次动作的结果翻成人话。调用方直接展示，不要各自写一遍 if。 */
export function describe(result: ApiResult<unknown>, done: string): string {
  if (result.status === 0) return messageOf(result.body) ?? '网关不可达'
  if (result.status === 401) return '登录已失效，请重新登录'
  if (result.status === 403) return '这个账号没有坐席权限'
  if (result.status === 409) return '这张单现在不在你手上（已被别人领走或已结单）'
  if (result.status === 404) return '这张单不存在或不属于本店'
  if (result.status >= 200 && result.status < 300) return done
  return messageOf(result.body) ?? `动作失败（HTTP ${result.status}）`
}

// ── 退款审核（B3 / ADR 0063）────────────────────────────────────────────────

/** 退款审核队列里的一项。 */
export interface RefundItem {
  refundId: number
  orderNo: string
  tenantId: string
  customerId: string
  amountFen: number
  reason: string
  status: string
}

/** 退款审核结果。 */
export interface RefundReviewResult {
  status: string
  payload?: {
    refundId: number
    status: string
    orderNo: string
  }
}

/** 读退款审核队列。B3 起只允许坐席/管理员账号。 */
export function listPendingRefunds(session: Session): Promise<ApiResult<RefundItem[]>> {
  return api<RefundItem[]>('GET', '/refunds/pending', { token: session.token })
}

// ── 渠道连接（票 173）────────────────────────────────────────────────────

export interface ChannelConnection {
  type: string
  enabled: boolean
  appId: string
  tenant: string
  customer: string
  source: string
  restartRequired: boolean
}

export interface ChannelList {
  channels: ChannelConnection[]
}

export interface ChannelProbe {
  ok: boolean
  message: string
}

export function listChannels(session: Session): Promise<ApiResult<ChannelList>> {
  return api<ChannelList>('GET', '/channels', { token: session.token })
}

export function testFeishu(
  session: Session,
  appId: string,
  appSecret: string,
): Promise<ApiResult<ChannelProbe>> {
  return api<ChannelProbe>('POST', '/channels/feishu/test', {
    token: session.token,
    body: { appId, appSecret },
  })
}

export interface FeishuUpdate {
  enabled: boolean
  appId: string
  appSecret: string
  tenant: string
  customer: string
  identityFile: string
}

export function saveFeishu(session: Session, update: FeishuUpdate): Promise<ApiResult<unknown>> {
  return api<unknown>('PUT', '/channels/feishu', { token: session.token, body: update })
}

// ── 渠道会话收件箱（票 175）────────────────────────────────────────────────

export interface ChannelMessage {
  role: string
  text: string
  at: string
}

export interface ChannelConversation {
  conversationId: string
  channel: string
  tenantId: string
  customerId: string
  status: string
  assignee: string | null
  messages: ChannelMessage[]
  lastMessage: string
  lastAt: string
  handoffTicketId: string | null
}

export function listChannelConversations(
  session: Session,
): Promise<ApiResult<{ conversations: ChannelConversation[] }>> {
  return api<{ conversations: ChannelConversation[] }>('GET', '/channel-conversations', {
    token: session.token,
  })
}

export function takeoverConversation(
  session: Session,
  id: string,
): Promise<ApiResult<{ conversation: ChannelConversation }>> {
  return api<{ conversation: ChannelConversation }>(
    'POST',
    `/channel-conversations/${encodeURIComponent(id)}/takeover`,
    { token: session.token },
  )
}

export function releaseConversation(
  session: Session,
  id: string,
): Promise<ApiResult<{ conversation: ChannelConversation }>> {
  return api<{ conversation: ChannelConversation }>(
    'POST',
    `/channel-conversations/${encodeURIComponent(id)}/release`,
    { token: session.token },
  )
}

export function replyConversation(
  session: Session,
  id: string,
  text: string,
): Promise<ApiResult<{ conversation: ChannelConversation }>> {
  return api<{ conversation: ChannelConversation }>(
    'POST',
    `/channel-conversations/${encodeURIComponent(id)}/reply`,
    { token: session.token, body: { text } },
  )
}

// ── 编排质量（ADR 0072 H4 / ADR 0073）───────────────────────────────────────

/** 通用指标行：标签字段随指标名变化，count 恒为次数。 */
export interface MetricRow {
  count: number
  [key: string]: string | number | boolean | null | undefined
}

export interface QualityReport {
  generatedAt: string
  scope: string
  requests: { total: number; fallbacks: number; fallbackRatio: number }
  handoff: {
    rows: MetricRow[]
    allowed: number
    blocked: number
    blockedRatio: number
    blockedByReason: Record<string, number>
    decisionSeconds: { count: number; meanSeconds: number; maxSeconds: number }
  }
  toolRisk: { rows: MetricRow[] }
  friction: {
    toolSuccessFallbacks: number
    repeatedSlotAsks: number
    repeatedSlotAsksByPhase: MetricRow[]
    handoffContextLosses: MetricRow[]
  }
  audit: {
    available: boolean
    source?: string
    windowMinutes?: number
    eventsScanned?: number
    actions: Record<string, number>
  }
}

/** 只读编排质量报表；坐席/管理员登录令牌即可读，不需要页面另填 ops token。 */
export function qualityReport(session: Session): Promise<ApiResult<QualityReport>> {
  return api<QualityReport>('GET', '/quality/report', { token: session.token })
}

/** 审核退款。B3 起只允许坐席/管理员账号，且责任人必须是已验签账号。 */
export function reviewRefund(
  refundId: number,
  decision: 'APPROVE' | 'REJECT',
  note: string,
  session: Session,
): Promise<ApiResult<RefundReviewResult>> {
  return api<RefundReviewResult>('POST', `/refunds/${refundId}/review`, {
    token: session.token,
    body: { decision, note },
  })
}