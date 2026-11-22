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
  /** 是否允许 401 时尝试刷新（登录/刷新端点自身必须传 false，否则会递归）。 */
  allowRefresh?: boolean
}

/**
 * 401 的统一出口（round47 票 195）：两端各自注册「中止流 / 清会话 / 回登录面」。
 *
 * <p>**只在带令牌的请求上触发**：登录请求本身不带令牌，密码错误也是 401，
 * 那不是「会话失效」，不该把用户留在登录页却提示登录已失效。
 */
let unauthorizedHandler: (() => void) | null = null
let refreshHandler: (() => Promise<string | null>) | null = null
let refreshing: Promise<string | null> | null = null
/**
 * 同一轮刷新失败只登出一次。
 *
 * <p>并发 401 共用同一个刷新 Promise，刷新失败时每个调用点都会走到「登出」这一步；
 * 不去重就会调用两次 `onUnauthorized`，两端各自再清一次会话、再弹一次登录门。
 * 下一次刷新成功时复位，让真正的新一轮失效还能登出。
 */
let unauthorizedNotified = false

export function setUnauthorizedHandler(handler: (() => void) | null): void {
  unauthorizedHandler = handler
}

/**
 * 注册 401 单飞刷新（round56 票 224 / ADR 0086）。
 *
 * <p>返回新的 access token；失败返回 null。**全局只允许一个刷新在飞**：
 * 两个并发 401 共用同一个 Promise，避免同一张 refresh 被用两次而触发复用撤销。
 * 刷新失败时只调用一次 `unauthorizedHandler`，不形成 401 循环。
 */
export function setRefreshHandler(handler: (() => Promise<string | null>) | null): void {
  refreshHandler = handler
  unauthorizedNotified = false
}

/** 当前是否有刷新在飞；测试与调试用。 */
export function isRefreshing(): boolean {
  return refreshing !== null
}

async function refreshOnce(): Promise<string | null> {
  if (!refreshHandler) return null
  refreshing ??= refreshHandler()
    .then((token) => {
      if (token) unauthorizedNotified = false
      return token
    })
    .catch(() => null)
    .finally(() => {
      refreshing = null
    })
  return refreshing
}

/**
 * 给裸 `fetch`（SSE）复用的刷新入口：与 `request()` 共用同一个单飞 Promise。
 *
 * <p>SSE 不能把已经消费的流「重放」，但 401 发生在流建立之前，所以调用方可以在
 * 拿到 401 后刷新一次、用新令牌重发同一个请求。
 */
export async function refreshAccessToken(): Promise<string | null> {
  return refreshOnce()
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
  const first = await send<T>(url, opts)
  if (first.status !== 401 || !opts.token?.trim() || opts.allowRefresh === false) {
    if (first.status === 401 && opts.token?.trim()) notifyUnauthorized()
    return first
  }
  const renewed = await refreshOnce()
  if (!renewed) {
    notifyUnauthorizedOnce()
    return first
  }
  // 只重试原请求一次；第二次 401 直接登出，不再次刷新。
  const second = await send<T>(url, { ...opts, token: renewed, allowRefresh: false })
  if (second.status === 401) notifyUnauthorized()
  return second
}

export function notifyUnauthorizedOnce(): void {
  if (unauthorizedNotified) return
  unauthorizedNotified = true
  notifyUnauthorized()
}

async function send<T>(url: string, opts: RequestOptions): Promise<ApiResult<T>> {
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
 * 身份生命周期调用（round56 票 224 / ADR 0086）。
 *
 * <p>refresh token 只在 HttpOnly Cookie 里，前端拿不到也不该拿；这里所有请求都带
 * `credentials: 'include'`，但只有 `/auth/refresh`、`/auth/logout` 两个端点会收到它
 * （Cookie 的 `Path=/auth`）。access token 仍走 `Authorization` 头。
 *
 * <p>放在本文件而不是单独入口：`frontend-shared` 只有一个导出面，加子路径导出会
 * 把「两端同源」拆成两套打包边界，也让 224 的契约多出一个不该有的改动点。
 */
export interface LoginBody {
  token: string
  accountId: string | null
  username: string
  tenantId: string
  customerId: string | null
  role: string
  displayName?: string | null
  capabilities?: string[]
  expiresInSeconds?: number
}

/** 刷新：不依赖 access token，只靠 Cookie；成功回新的 access token 与账号视图。 */
export async function refreshSession(): Promise<ApiResult<LoginBody>> {
  return postAuth<LoginBody>('/auth/refresh')
}

/** 登出：撤销 refresh 族并清 Cookie；失败也当成功（本地会话必须清掉）。 */
export async function logoutSession(): Promise<void> {
  await postAuth<unknown>('/auth/logout').catch(() => undefined)
}

/** 改密：必须带有效 access token；成功后服务端会撤销全部 refresh。 */
export async function changePassword(
  token: string,
  currentPassword: string,
  newPassword: string,
): Promise<ApiResult<{ changed: boolean; message?: string }>> {
  return request<{ changed: boolean; message?: string }>('/auth/password', {
    method: 'POST',
    token,
    body: { currentPassword, newPassword },
    allowRefresh: true,
  })
}

async function postAuth<T>(path: string): Promise<ApiResult<T>> {
  let response: Response
  try {
    response = await fetch(path, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    })
  } catch (unreachable) {
    const reason = unreachable instanceof Error ? unreachable.message : 'network error'
    return { status: 0, body: { message: `网关不可达（${reason}）` } }
  }
  const text = await response.text()
  try {
    return { status: response.status, body: (text ? JSON.parse(text) : {}) as T }
  } catch (badJson) {
    return { status: response.status, body: { message: text || '响应不是 JSON' } }
  }
}

/** 改密失败的统一人话：401 是当前口令不对，400 是格式问题，其余透出下游那句话。 */
export function describePasswordChange(result: ApiResult<unknown>): string {
  if (result.status === 0) return messageOf(result.body) ?? '网关不可达'
  if (result.status === 401 || result.status === 403) return messageOf(result.body) ?? '当前口令不对'
  if (result.status === 400) return messageOf(result.body) ?? '新口令不合法'
  if (result.status >= 200 && result.status < 300) return '口令已更新，请用新口令重新登录'
  return messageOf(result.body) ?? `修改失败（HTTP ${result.status}）`
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

/**
 * 工单号的短显：保留首字母与末 8 位。
 *
 * <p>工单号形如 `T<epochMillis>-<seq>`，长度落在 16 位左右；完整值仍由调用方放进
 * `title` 或复制动作里。这个规则两端必须同源，否则同一张单在两个入口会有两种短号。
 */
export function shortTicketId(id: string): string {
  if (id.length <= 14) return id
  const tail = id.slice(-8)
  return /^[A-Za-z]$/.test(id[0]) ? `${id[0]}…${tail}` : `…${tail}`
}

/**
 * 复制文本，带非安全上下文降级。
 *
 * <p>`navigator.clipboard` 只在 secure context 可用；LAN HTTP、容器 IP 和一部分
 * WebView 会直接拒绝。坐席在工单详情里复制完整单号是高频动作，不能因为部署协议
 * 不同就只剩一句「复制失败」。
 */
export async function copyToClipboard(text: string): Promise<boolean> {
  if (
    typeof navigator !== 'undefined'
    && navigator.clipboard
    && typeof window !== 'undefined'
    && window.isSecureContext
  ) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // 权限被拒或浏览器实现异常时继续走 textarea 兜底。
    }
  }
  if (typeof document === 'undefined' || !document.body) return false
  const area = document.createElement('textarea')
  area.value = text
  area.setAttribute('readonly', '')
  area.setAttribute('aria-hidden', 'true')
  area.style.position = 'fixed'
  area.style.top = '0'
  area.style.left = '-9999px'
  area.style.opacity = '0'
  try {
    document.body.appendChild(area)
    area.focus({ preventScroll: true })
    area.select()
    area.setSelectionRange(0, area.value.length)
    return document.execCommand('copy')
  } catch {
    return false
  } finally {
    area.remove()
  }
}

/** 计数字面量：超过 99 一律显示 `99+`。 */
export function countLabel(value: number): string {
  return value > 99 ? '99+' : String(value)
}

/** 弹层键盘礼让（round46 票 193）：与框架无关，见 `dialog.ts` 里为什么不做成组合式函数。 */
export { trapDialogFocus } from './dialog'
