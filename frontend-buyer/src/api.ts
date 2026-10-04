import type { Citation, Session, Ticket } from './types'

/**
 * 与另两个前端同一套失败口径（`scripts/verify-console.mjs` 之外那份家法的第三份实现）。
 *
 * <p>网络级失败（fetch 自己 reject）**必须**收进同一个出口：返回 `status: 0` 加一句为什么，
 * 让所有调用方走它们既有的失败分支，而不是留下未捕获的 `Failed to fetch`
 * （调试台那轮修过的坑，见 `RestErrorEnvelopeTest` 的注释）。
 *
 * <p>三个出口各有一句话，不混：0 = 网关不可达；401 = 登录失效；403 = 没有这个买家数据的权限。
 */
export interface ApiResult<T> {
  status: number
  body: T | { message?: string }
}

export async function request<T>(
  path: string,
  opts: { method?: string; token?: string; body?: unknown; raw?: string } = {},
): Promise<ApiResult<T>> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (opts.token?.trim()) headers.Authorization = `Bearer ${opts.token.trim()}`
  let res: Response
  try {
    res = await fetch(path, {
      method: opts.method ?? 'GET',
      headers,
      body: opts.raw ?? (opts.body === undefined ? undefined : JSON.stringify(opts.body)),
    })
  } catch (unreachable) {
    const reason = unreachable instanceof Error ? unreachable.message : 'network error'
    return { status: 0, body: { message: `网关不可达（${reason}）` } }
  }
  const text = await res.text()
  try {
    return { status: res.status, body: (text ? JSON.parse(text) : {}) as T }
  } catch (badJson) {
    return { status: res.status, body: { message: text || '响应不是 JSON' } }
  }
}

/** 登录换令牌（round25 的 `/auth/login`，`AuthFilter` 对 `/auth/` 整体放行）。 */
export function login(tenantId: string, username: string, password: string): Promise<ApiResult<Session>> {
  return request<Session>('/auth/login', { method: 'POST', body: { tenantId, username, password } })
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
): Promise<ApiResult<{ ticketId: string | null; citations: Citation[]; fallbackReason: string | null }>> {
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
    })
  } catch (unreachable) {
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
  let fallbackReason: string | null = null

  // 分帧不能按子串猜：round21 记过一次「流式分帧不能子串匹配 → 读回用同步端点」。
  // 这里按 \n\n 切块、逐块解析 `event:` 与 `data:` 行。
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
            citations = (parsed.citations as Citation[]) ?? citations
            ticketId = (parsed.ticketId as string) ?? ticketId
          }
        }
      }
      event = ''
      split = buffer.indexOf('\n\n')
    }
  }
  return { status: 200, body: { ticketId, citations, fallbackReason } }
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
export function messageOf(body: unknown): string | null {
  if (body && typeof body === 'object' && 'message' in body) {
    const message = (body as { message?: unknown }).message
    return typeof message === 'string' && message ? message : null
  }
  return null
}

/** 三种失败各有各的说法，混成一句「加载失败」正是调试台修掉的那个毛病。 */
export function describe(result: ApiResult<unknown>, fallback: string): string {
  if (result.status === 0) return messageOf(result.body) ?? '网关不可达'
  if (result.status === 401) return '登录已失效，请重新登录'
  if (result.status === 403) return '这个账号没有查看买家数据的权限'
  if (result.status >= 200 && result.status < 300) return fallback
  return messageOf(result.body) ?? `请求失败（HTTP ${result.status}）`
}