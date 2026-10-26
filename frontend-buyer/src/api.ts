import { messageOf, request, type ApiResult } from 'frontend-shared'
import type { Citation, CitationDetail, Session, Ticket } from './types'

/**
 * 请求封装与失败出口在 `frontend-shared`（round36 / 票 151）：网络级失败（fetch reject）
 * 收进同一个出口，返回 `status: 0` 加一句为什么，不留下未捕获的 `Failed to fetch`。
 *
 * <p>本文件只保留买家自己的路径与失败文案：0 = 网关不可达；401 = 登录失效；
 * 403 = 没有这个买家数据的权限。这三个出口各有一句话，不混。
 */
export { messageOf }
export type { ApiResult }

/** 登录换令牌（round25 的 `/auth/login`，`AuthFilter` 对 `/auth/` 整体放行）。 */
export function login(tenantId: string, username: string, password: string): Promise<ApiResult<Session>> {
  return request<Session>('/auth/login', { method: 'POST', body: { tenantId, username, password } })
}

/**
 * 游客身份（round37）：本机演示一键开聊。
 *
 * <p>走回环调试口 `/auth/mock-token`（ADR 0014/0029）：它只在回环绑定上注册，
 * 非回环部署拿到 404 时页面回落为口令登录。顾客号固定 C001——演示段的默认买家。
 */
export async function guestSession(tenantId: string): Promise<ApiResult<Session>> {
  const result = await request<{ token: string; claims: { tid: string; cid: string }; expiresInSeconds: number }>(
    '/auth/mock-token',
    { method: 'POST', body: { tenantId, customerId: 'C001' } },
  )
  if (result.status !== 200) {
    return { status: result.status, body: result.body as { message?: string } }
  }
  const body = result.body as { token: string; claims: { tid: string; cid: string } }
  return {
    status: 200,
    body: {
      token: body.token,
      accountId: null,
      username: 'guest',
      role: 'BUYER',
      tenantId: body.claims.tid,
      customerId: body.claims.cid,
      displayName: '访客',
    },
  }
}

/**
 * 一键体验买家（UX-01）：服务端用演示口令换真买家令牌，浏览器不接触口令。
 *
 * <p>与坐席页共用 `/auth/demo-login`；容器演示档由 `SHOPPILOT_IDENTITY_DEMO_LOGIN_ENABLED=true`
 * 注册，默认关闭。非演示档返回 404 时页面回落为口令登录。
 */
export function demoBuyerLogin(tenantId: string): Promise<ApiResult<Session>> {
  return request<Session>('/auth/demo-login', { method: 'POST', body: { tenantId, role: 'buyer' } })
}

/** 当前店铺资料（round37）：店名、主营、本店承诺，只读且只认令牌里的租户。 */
export interface StoreProfile {
  tenantId: string
  name: string
  business: string
  guarantees: string
}

export function storeProfile(token: string): Promise<ApiResult<StoreProfile>> {
  return request<StoreProfile>('/api/v1/support/store-profile', { token })
}

/** 店铺清单：登录后把内部租户号换成店名（round36 / 票 151）。 */
export interface StoreInfo {
  tenantId: string
  name: string
  rateLimitQps?: number
}

/**
 * 店铺清单读面（网关 `/api/v1/support/ops/tenants` → biz-mock `/api/admin/tenants`）。
 *
 * <p>它在鉴权面内，所以只能**登录后**调用——这也是 B2 选 URL 店铺码而不是新增公开端点的原因
 * （ADR 0070）。取不到就继续显示店铺码，不编名字。
 */
export function stores(token: string): Promise<ApiResult<StoreInfo[]>> {
  return request<StoreInfo[]>('/api/v1/support/ops/tenants', { token })
}

/**
 * 我的工单（票 92）。
 *
 * <p>**不带任何买家号参数**：买家号只来自服务端按令牌过滤的结果。
 * 页面就算想传也没有可传的地方——那不是省事，是把 ADR 0005 防线一写成接口形状。
 */
export function myTickets(token: string): Promise<ApiResult<Ticket[]>> {
  return request<Ticket[]>('/api/v1/support/ops/tickets/mine', { token })
}

/** 买家自己的最近订单（ADR 0075）。同样不接受买家号参数，归属只在服务端判定。 */
export interface MyOrder {
  orderNo: string
  amountFen: number
  status: string
  categoryName: string
  serviceFlags: string[]
  createdAt: string
}

export function myOrders(token: string): Promise<ApiResult<MyOrder[]>> {
  return request<MyOrder[]>('/api/v1/support/orders/mine', { token })
}

/** 买家自己的单张工单详情（UX-03）：隔离由下游按令牌里的买家号再判一次。 */
export function myTicket(token: string, ticketId: string): Promise<ApiResult<Ticket>> {
  return request<Ticket>('/api/v1/support/ops/tickets/mine/' + encodeURIComponent(ticketId), { token })
}

/** 同步问答。SSE 那条在 `stream.ts` 里，因为它要逐帧处理。 */
export function ask(token: string, query: string, conversationId: string): Promise<ApiResult<{ answer: string; ticketId: string | null }>> {
  return request('/api/v1/support/chat', {
    method: 'POST',
    token,
    body: { query, idempotencyToken: `buyer-${conversationId}-${Date.now()}` },
  })
}

/** SSE 流的响应体（`EventSource` 不支持 POST，所以逐帧手读，ADR 0006 的同款理由）。 */
export async function streamAnswer(
  token: string,
  query: string,
  conversationId: string,
  onToken: (chunk: string) => void,
  signal?: AbortSignal,
): Promise<ApiResult<{ ticketId: string | null; citations: Citation[]; citationDetails: CitationDetail[]; fallbackReason: string | null }>> {
  let res: Response
  try {
    res = await fetch('/api/v1/support/chat/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        Authorization: `Bearer ${token.trim()}`,
        'X-Conversation-Id': conversationId,
      },
      body: JSON.stringify({ query, idempotencyToken: `buyer-${conversationId}-${Date.now()}` }),
      signal,
    })
  } catch (unreachable) {
    if (signal?.aborted) {
      return { status: 499, body: { message: '已停止生成' } }
    }
    const reason = unreachable instanceof Error ? unreachable.message : 'network error'
    return { status: 0, body: { message: `网关不可达（${reason}）` } }
  }
  if (res.status / 100 !== 2) {
    return { status: res.status, body: { message: await res.text() } }
  }

  const reader = res.body?.getReader()
  if (!reader) {
    return { status: 0, body: { message: '这条浏览器不支持流式读取' } }
  }
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let event = ''
  let answer = ''
  let ticketId: string | null = null
  let citations: Citation[] = []
  let citationDetails: CitationDetail[] = []
  let fallbackReason: string | null = null

  // 分帧不能按子串猜：round21 记过一次「流式分帧不能子串匹配 → 读回用同步端点」。
  // 这里按 \n\n 切块、逐块解析 `event:` 与 `data:` 行。
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let split = buffer.indexOf('\n\n')
      while (split >= 0) {
        const frame = buffer.slice(0, split)
        buffer = buffer.slice(split + 2)
        for (const line of frame.split('\n')) {
          if (line.startsWith('event:')) {
            event = line.slice(6).trim()
          } else if (line.startsWith('data:') && event) {
            const data = line.slice(5).trim()
            if (event === 'token') {
              // token 帧的 data 是一段 JSON **字符串**（"片段"），不是对象——按 .text 取值会得到 undefined，
              // 拼出来是空串，于是「流式逐字」这条永远失败（round21 记过同一处）。
              const parsed = safeParse(data)
              const chunk = typeof parsed === 'string' ? parsed : ((parsed as Record<string, unknown>)?.text as string ?? '')
              if (chunk) {
                answer += chunk
                onToken(chunk)
              }
            } else if (event === 'fallback') {
              const parsed = asObject(safeParse(data))
              fallbackReason = (parsed.reason as string) ?? fallbackReason
              ticketId = (parsed.ticketId as string) ?? ticketId
            } else if (event === 'done') {
              const parsed = asObject(safeParse(data))
              // round33 / ADR 0067：详情优先，citations 那份字符串契约照旧留着（旧客户端读它）
              citationDetails = (parsed.citationDetails as CitationDetail[]) ?? citationDetails
              citations = (parsed.citations as Citation[]) ?? citations
              ticketId = (parsed.ticketId as string) ?? ticketId
            }
          }
        }
        event = ''
        split = buffer.indexOf('\n\n')
      }
    }
  } catch (readFailure) {
    if (signal?.aborted) {
      return { status: 499, body: { message: '已停止生成' } }
    }
    return { status: 0, body: { message: '流式连接中断，请重试' } }
  }
  return { status: 200, body: { ticketId, citations, citationDetails, fallbackReason } }
}

function safeParse(raw: string): unknown {
  try {
    return JSON.parse(raw)
  } catch (unparsable) {
    return null
  }
}

/** 帧数据一律按对象读；它是字符串时（token 帧）返回空对象，调用点因此不必判两次类型。 */
function asObject(parsed: unknown): Record<string, unknown> {
  return parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : {}
}

/** 取下游那句话；页面各处直接展示，不要各自写一遍 if。 */
/** 三种失败各有各的说法，混成一句「加载失败」正是调试台修掉的那个毛病。 */
export function describe(result: ApiResult<unknown>, fallback: string): string {
  if (result.status === 0) return messageOf(result.body) ?? '网关不可达'
  if (result.status === 401) return '登录已失效，请重新登录'
  if (result.status === 403) return '这个账号没有查看买家数据的权限'
  if (result.status >= 200 && result.status < 300) return fallback
  return messageOf(result.body) ?? `请求失败（HTTP ${result.status}）`
}
