import type { Ticket } from './types'

/**
 * 与运维调试台同一套失败口径（`index.html` 的 `api()`，票 61 修过的那条）。
 *
 * <p>网络级失败（fetch 自己 reject）**必须**收进同一个出口：以前这里是直接抛出去，
 * 于是读数格冻结在初始的「-」、抽屉停在「尚未拉取」，还留下未捕获的 `Failed to fetch`。
 * 所以返回 `status: 0` 加一句为什么，让所有调用方走它们既有的失败分支。
 *
 * <p>动作端点要两个头：`X-Ops-Token` 守门（票 73 补的守卫：读队列和改队列一样要门），
 * `X-Agent` 是坐席自称。**两者都不是认证**——真身份域是下一轮（ADR 0056），
 * 那之前它们只证明「不是随便一个人」，不证明「是谁」。
 */
export interface ApiResult<T> {
  status: number
  body: T | { message?: string }
}

const BASE = '/api/v1/support/ops'

export async function api<T>(method: string, path: string, opts: { opsToken?: string; agent?: string; authToken?: string; body?: unknown } = {}): Promise<ApiResult<T>> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  // **两个凭证都要**：ops 端点过两道路口——AuthFilter 要买家 JWT，OpsController 要运维令牌。
  // 少带一个就是 401，而页面看起来只是「队列是空的」。第一版就漏了 JWT，
  // 是清场日把「队列空必须判红」改成硬断言才把它逼出来的。
  if (opts.authToken?.trim()) headers.Authorization = `Bearer ${opts.authToken.trim()}`
  if (opts.opsToken?.trim()) headers['X-Ops-Token'] = opts.opsToken.trim()
  if (opts.agent?.trim()) headers['X-Agent'] = opts.agent.trim()
  let res: Response
  try {
    res = await fetch(BASE + path, {
      method,
      headers,
      body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
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

/**
 * 队列列表。
 *
 * <p>三种失败各有各的处置，所以它们都必须原样回到调用方：**0** 是「读不到网关」、
 * **401/403** 是「凭证不对」、**其余非 2xx** 是下游的问题。三者混成一句「加载失败」
 * 就是调试台那轮修掉的毛病。
 */
export function listTickets(queue: string, creds: Creds): Promise<ApiResult<Ticket[]>> {
  const query = queue && queue !== 'ALL' ? `?queue=${encodeURIComponent(queue)}` : ''
  return api<Ticket[]>('GET', `/tickets${query}`, { ...creds })
}

/** 一次 ops 调用需要的两个凭证。 */
export interface Creds {
  opsToken: string
  authToken: string
  agent: string
}

/**
 * 取演示用的买家 JWT（调试台同一套家法）。
 *
 * <p>`/auth/` 路径不在 AuthFilter 的拦截面上，所以这一调用不需要先有令牌——
 * 这正是「演示入口」与「真身份」在本仓的区别（ADR 0014）。
 */
export async function fetchDemoToken(tenantId: string, customerId: string): Promise<string> {
  try {
    const res = await fetch('/auth/mock-token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ tenantId, customerId }),
    })
    const text = await res.text()
    return text ? (JSON.parse(text).token ?? '') : ''
  } catch (unreachable) {
    return ''
  }
}

/** 领取。204 领到、409 已被别人领走、404 不存在或不是本店的（工单服务侧保证）。 */
export function claimTicket(id: string, creds: Creds): Promise<ApiResult<unknown>> {
  return api<unknown>('POST', `/tickets/${encodeURIComponent(id)}/claim`, { ...creds })
}

export function releaseTicket(id: string, creds: Creds): Promise<ApiResult<unknown>> {
  return api<unknown>('POST', `/tickets/${encodeURIComponent(id)}/release`, { ...creds })
}

export function resolveTicket(id: string, creds: Creds, note: string): Promise<ApiResult<unknown>> {
  return api<unknown>('POST', `/tickets/${encodeURIComponent(id)}/resolve`, { ...creds, body: { note } })
}

/**
 * 取下游给的一句话（若有）。
 *
 * <p>单独一个函数而不是让每个调用点 cast：body 是 `T | { message? }`，
 * 散着写就会出现三处各写一遍的类型断言——那种地方迟早漏一处，而漏的那处编译不出来。
 */
export function messageOf(body: unknown): string | null {
  if (body && typeof body === 'object' && 'message' in body) {
    const message = (body as { message?: unknown }).message
    return typeof message === 'string' && message ? message : null
  }
  return null
}

/** 把一次动作的结果翻成人话。调用方直接展示，不要各自写一遍 if。 */
export function describe(result: ApiResult<unknown>, done: string): string {
  if (result.status === 0) return messageOf(result.body) ?? '网关不可达'
  if (result.status === 401 || result.status === 403) return '运维凭证不对（ops token）'
  if (result.status === 409) return '这张单现在不在你手上（已被别人领走或已结单）'
  if (result.status === 404) return '这张单不存在或不属于本店'
  if (result.status >= 200 && result.status < 300) return done
  return messageOf(result.body) ?? `动作失败（HTTP ${result.status}）`
}