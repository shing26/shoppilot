/**
 * 两个产品入口的共用契约与请求封装（round36 票 151 / ADR 0070）。
 *
 * <p>这里只放**两边必须同源**的东西：`TicketView` 的镜像类型、标签映射、SLA 计算、
 * 以及那份「网络级失败收进同一个出口」的请求封装。各页的路径前缀与失败文案仍留在各自的
 * `api.ts`——买家的 403 与坐席的 403 不是同一句话，混成一句就是把两种失败读成一种。
 */

/** 统一出口：0 = 网关不可达；401 = 登录失效；403 = 没有权限；其余非 2xx = 下游问题。 */
export interface ApiResult<T> {
  status: number
  body: T | { message?: string }
}

export interface RequestOptions {
  method?: string
  token?: string
  body?: unknown
  /** 原样发送的请求体（目前只有 SSE 之外的少数调用用得上）。 */
  raw?: string
}

export async function request<T>(url: string, opts: RequestOptions = {}): Promise<ApiResult<T>> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (opts.token?.trim()) headers.Authorization = `Bearer ${opts.token.trim()}`
  let res: Response
  try {
    res = await fetch(url, {
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

export function messageOf(body: unknown): string | null {
  if (body && typeof body === 'object' && 'message' in body) {
    const message = (body as { message?: unknown }).message
    return typeof message === 'string' && message ? message : null
  }
  return null
}

/**
 * 工单视图的共用字段，与 `com.shoppilot.tool.view.TicketView` 逐项对齐。
 *
 * <p>**`transcript` 不在这里**：坐席视角在 `frontend-workspace` 里扩展它，买家视角刻意没有
 * （round36 票 151 之前那条「买家拿不到 transcript」的断言是真空的，因为两边都没有这个字段）。
 */
export interface TicketSummary {
  id: string
  tenantId: string
  customerId: string
  reason: string
  userQuery: string
  status: string
  priority: string | null
  createdAt: string
  source: string | null
  queue: string | null
  assignee: string | null
  slaDeadline: string | null
  escalatedAt: string | null
  channel?: string | null
  contact?: string | null
}

/** 状态的两个视角同一口径（买家与坐席看到的说法一致）。 */
export const STATUS_LABEL: Record<string, string> = {
  OPEN: '等待处理',
  ASSIGNED: '处理中',
  RESOLVED: '已处理完成',
}

/** 优先级的可读名：存储字面量 high/money/normal 在页面上对齐成一处。 */
export const PRIORITY_LABEL: Record<string, string> = {
  high: '紧急',
  money: '资金',
  normal: '普通',
}

/** 优先级排序用的名次：越小越靠前。与后端 `TicketPriority.rank` 同一份口径。 */
export function priorityRank(priority: string | null): number {
  if (priority === 'high') return 0
  if (priority === 'money') return 1
  return 2
}

/** SLA 剩余分钟数；已超时为负（前端据此显示「已超时」，但不改状态）。 */
export function minutesLeft(deadline: string | null, now: number): number | null {
  if (!deadline) return null
  return Math.round((new Date(deadline).getTime() - now) / 60000)
}
