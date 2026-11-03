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

/**
 * 401 的统一出口（round47 票 195）：两端各自注册「中止流 / 清会话 / 回登录面」。
 *
 * <p>**只在带令牌的请求上触发**：登录请求本身不带令牌，密码错误也是 401，
 * 那不是「会话失效」，不该把用户留在登录页却提示登录已失效。
 */
let unauthorizedHandler: (() => void) | null = null

export function setUnauthorizedHandler(handler: (() => void) | null): void {
  unauthorizedHandler = handler
}

/**
 * 给不走 `request()` 的入口复用同一钩子（买家 SSE 是裸 `fetch`）。
 *
 * <p>导出是为了同一个包内的 `api.ts` 能用；它不是产品面 API，入口不要直接调用。
 */
export function notifyUnauthorized(): void {
  unauthorizedHandler?.()
}

export async function request<T>(url: string, opts: RequestOptions = {}): Promise<ApiResult<T>> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  const authenticated = Boolean(opts.token?.trim())
  if (authenticated) headers.Authorization = `Bearer ${opts.token!.trim()}`
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
  if (res.status === 401 && authenticated) notifyUnauthorized()
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
  /** UX-03：坐席结单意见；买家详情可见，未结单为 null。 */
  resolutionNote?: string | null
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

/**
 * 反馈票的可读名（round47 票 195）：知识视图的复核列表直接显示 `UP` / `DOWN`，
 * 读起来像数据库字段而不是「有用 / 没用」。未知值由调用方原样透出。
 */
export const FEEDBACK_VERDICT_LABEL: Record<string, string> = {
  UP: '有用',
  DOWN: '没用',
}

/**
 * 退款态的可读名：biz-mock 的存储字面量 `PENDING_REVIEW` / `PROCESSING` / `REJECTED`
 * 与坐席看到的「待审核 / 已放行（待通道）/ 已驳回」对齐（ADR 0047 的迁移语义不变）。
 */
export const REFUND_STATUS_LABEL: Record<string, string> = {
  PENDING_REVIEW: '待审核',
  PROCESSING: '已放行（待通道）',
  REJECTED: '已驳回',
}

/** 渠道会话态的可读名：`BOT` / `AGENT` 说的是「谁在答」，不是产品文案。 */
export const CONVERSATION_STATUS_LABEL: Record<string, string> = {
  BOT: '智能助手',
  AGENT: '坐席接管',
}

/** 买家订单服务标：存储字面量是下划线常量，页面上应读成保障条款。 */
export const SERVICE_FLAG_LABEL: Record<string, string> = {
  SEVEN_DAY_RETURN: '七天无理由',
  FRESH_GUARANTEE: '生鲜保障',
}

/** 标签映射的统一读法：未知值原样透出，不吞、不猜（本仓既有约定）。 */
export function labelOf(map: Record<string, string>, value: string | null | undefined): string {
  if (value === null || value === undefined) return ''
  return map[value] ?? value
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

/**
 * 短时间戳：`MM-DD HH:mm`。空值或不可解析返回 `null`。
 *
 * <p>**为什么返回 null 而不是空串或破折号**：两个入口对"没有时间"的写法不同——
 * 买家端留空（`''`），坐席端写 `—`。那两句话是各自的文案，不是同一件事，
 * 合并成一个字面量就是把产品语气压成一种。调用方各自决定兜底。
 */
export function formatStamp(value: string | null | undefined): string | null {
  if (!value) return null
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return null
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** 长 id 的列表写法：太长的只留尾部，避免撑破表格列。 */
export function shortId(id: string): string {
  return id.length > 18 ? `…${id.slice(-12)}` : id
}

/** 计数字面量：超过 99 一律显示 `99+`。 */
export function countLabel(value: number): string {
  return value > 99 ? '99+' : String(value)
}

/** 弹层键盘礼让（round46 票 193）：与框架无关，见 `dialog.ts` 里为什么不做成组合式函数。 */
export { trapDialogFocus } from './dialog'
