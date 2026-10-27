<script setup lang="ts">
/**
 * 买家中心（round27 票 93 / ADR 0057；UX-01 首屏收口）。
 *
 * <p>这一版只做一件事：让买家打开页面就看到「这是哪家店、能帮什么、怎么开始」，
 * 而不是一张需要先问运维要账号的空白表单。容器演示档用一键体验买家进入；
 * 非演示档自动回落到口令登录，不把「端点没开」写成一句死路。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  demoBuyerLogin,
  describe,
  guestSession,
  login,
  messageOf,
  myOrders,
  myTicket,
  myTickets,
  storeProfile,
  streamAnswer,
} from './api'
import { PRIORITY_LABEL, STATUS_LABEL, minutesLeft, type CitationDetail, type Session, type Ticket } from './types'
import type { AgentRunStep, MyOrder } from './api'

const STORAGE_KEY = 'shoppilot.buyer.session'

/** 店铺码来自 URL；缺省时不猜、也不退回内部 id 输入框（ADR 0070 B2）。 */
const STORE_PARAMS = new URLSearchParams(window.location.search)
const STORE_CODE = (STORE_PARAMS.get('store') ?? '').trim()
const AUTO_GUEST = !['0', 'false', 'off'].includes((STORE_PARAMS.get('guest') ?? '').trim().toLowerCase())

const tenant = ref(STORE_CODE)
const storeName = ref('')
const storeBusiness = ref('')
const storeGuarantees = ref('')
const guestMode = ref(false)
const demoMode = ref(false)
const guestError = ref('')
const username = ref('')
const password = ref('')
const showLogin = ref(false)
const demoLoading = ref(false)
const session = ref<Session | null>(readStoredSession())
const loginError = ref('')

const query = ref('')
const asking = ref(false)
const inFlight = ref<AbortController | null>(null)
const messages = ref<ChatMessage[]>([])
const tickets = ref<Ticket[]>([])
const ticketsLoading = ref(false)
const ticketError = ref('')
const orders = ref<MyOrder[]>([])
const ordersLoading = ref(false)
const orderError = ref('')
const sideTab = ref<'orders' | 'tickets'>('orders')
const showAllOrders = ref(false)
const showAllTickets = ref(false)
const now = ref(Date.now())
let conversation = ''

// UX-03：工单详情抽屉。选中后轮询进度，结单后停止。
const selectedTicket = ref<Ticket | null>(null)
const detailLoading = ref(false)
const detailError = ref('')
const progressNote = ref('')
let detailTimer = 0

// 登录前拿不到店名时显示「本店」，不把内部租户号 T001 当产品文案。
const storeLabel = computed(() => storeName.value || '本店')

// P0 侧栏：默认只露出 2 条，避免工单历史把聊天淹没。
const visibleOrders = computed(() => (showAllOrders.value ? orders.value : orders.value.slice(0, 2)))
const visibleTickets = computed(() => (showAllTickets.value ? tickets.value : tickets.value.slice(0, 2)))

const QUICK_QUESTIONS = computed(() => {
  if (tenant.value === 'T004') {
    return ['你们酒店是卖什么的？', '入住和退房时间是几点？', '帮我查一下订单 90001 的物流']
  }
  if (tenant.value === 'T002') {
    return ['你们店是卖什么的？', '生鲜坏了怎么赔', '帮我查一下订单 90001 的物流']
  }
  return ['你们店是卖什么的？', '七天无理由怎么退', '帮我查一下订单 90001 的物流']
})

interface ChatMessage {
  role: 'user' | 'assistant'
  text: string
  pending?: boolean
  note?: string
  citations?: CitationDetail[]
  /** 重试时复用原问题，不要求用户再打一遍。 */
  retryQuery?: string
  stopped?: boolean
  failed?: boolean
  /** Agent 运行轨迹：只显示面客可读的步骤，不展示内部 state detail。 */
  trace?: AgentRunStep[]
}

const isGuest = computed(() => guestMode.value || session.value?.username === 'guest')

async function loadStoreInfo() {
  if (!session.value) return
  const result = await storeProfile(session.value.token)
  if (result.status !== 200) return
  const profile = result.body as { name?: string; business?: string; guarantees?: string }
  if (profile.name) storeName.value = profile.name
  storeBusiness.value = profile.business ?? ''
  storeGuarantees.value = profile.guarantees ?? ''
}

/**
 * 一键体验买家：服务端持有演示口令，浏览器只拿令牌。
 * 失败时把原因写出来并展开口令登录，不把首屏留成空白。
 */
async function enterAsDemoBuyer(): Promise<boolean> {
  if (demoLoading.value) return false
  if (!tenant.value.trim()) {
    guestError.value = '缺少店铺码：请通过店铺链接进入（例如 /buyer/?store=T001）'
    showLogin.value = true
    return false
  }
  demoLoading.value = true
  guestError.value = ''
  const result = await demoBuyerLogin(tenant.value.trim())
  demoLoading.value = false
  if (result.status !== 200 || !result.body || !(result.body as Session).token) {
    guestError.value = result.status === 404
      ? '当前部署未开启一键体验，请用买家账号登录'
      : describe(result, '一键体验暂时不可用')
    showLogin.value = true
    return false
  }
  session.value = result.body as Session
  demoMode.value = true
  guestMode.value = false
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify(result.body))
  await Promise.all([refreshTickets(), refreshOrders(), loadStoreInfo()])
  return true
}

/**
 * 游客身份：本机回环栈的免密入口。容器档会 404，随后尝试一键体验买家；
 * 两者都不可用时才显示口令登录。
 */
async function enterAsGuest(): Promise<boolean> {
  guestError.value = ''
  if (!tenant.value.trim()) return false
  const result = await guestSession(tenant.value.trim())
  if (result.status === 200 && result.body) {
    session.value = result.body as Session
    guestMode.value = true
    demoMode.value = false
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(result.body))
    await loadStoreInfo()
    return true
  }
  if (result.status === 404) {
    return enterAsDemoBuyer()
  }
  guestError.value = result.status === 0
    ? (messageOf(result.body) ?? '网关不可达')
    : (messageOf(result.body) ?? `进入失败（HTTP ${result.status}）`)
  showLogin.value = true
  return false
}

async function doLogin() {
  loginError.value = ''
  if (!tenant.value.trim()) {
    loginError.value = '缺少店铺码：请通过店铺链接进入（例如 /buyer/?store=T001）'
    return
  }
  const result = await login(tenant.value.trim(), username.value.trim(), password.value)
  if (result.status !== 200) {
    loginError.value = result.status === 0
      ? (messageOf(result.body) ?? '网关不可达')
      : (messageOf(result.body) ?? `登录失败（HTTP ${result.status}）`)
    session.value = null
    clearStoredSession()
    return
  }
  session.value = result.body as Session
  demoMode.value = false
  guestMode.value = false
  showLogin.value = false
  password.value = ''
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify(result.body))
  await Promise.all([refreshTickets(), refreshOrders(), loadStoreInfo()])
}

function signOut() {
  session.value = null
  demoMode.value = false
  guestMode.value = false
  showLogin.value = true
  clearStoredSession()
  tickets.value = []
  ticketError.value = ''
  orders.value = []
  orderError.value = ''
  messages.value = []
  conversation = ''
  asking.value = false
  closeTicket()
}

async function askQuick(question: string) {
  if (asking.value) return
  if (!session.value) {
    const entered = await enterAsDemoBuyer()
    if (!entered) return
  }
  query.value = question
  await ask()
}

const ORDER_STATUS_LABEL: Record<string, string> = {
  CREATED: '待付款',
  PAID: '待发货',
  SHIPPED: '运输中',
  DELIVERED: '已送达',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  REFUNDING: '退款中',
  REFUNDED: '已退款',
}

async function refreshOrders() {
  orderError.value = ''
  if (!session.value || isGuest.value) {
    orders.value = []
    ordersLoading.value = false
    return
  }
  ordersLoading.value = true
  const result = await myOrders(session.value.token)
  ordersLoading.value = false
  if (result.status !== 200 || !Array.isArray(result.body)) {
    orderError.value = describe(result, '')
    orders.value = []
    return
  }
  orders.value = result.body
}

async function askOrder(order: MyOrder) {
  query.value = `帮我查一下订单 ${order.orderNo} 的物流`
  await ask()
}

async function refreshTickets() {
  ticketError.value = ''
  if (!session.value) {
    tickets.value = []
    ticketsLoading.value = false
    return
  }
  ticketsLoading.value = true
  const result = await myTickets(session.value.token)
  ticketsLoading.value = false
  if (result.status !== 200 || !Array.isArray(result.body)) {
    ticketError.value = describe(result, '')
    tickets.value = []
    return
  }
  tickets.value = result.body
  const current = selectedTicket.value
  if (current) {
    const fresh = tickets.value.find((ticket) => ticket.id === current.id)
    if (fresh) selectedTicket.value = fresh
  }
}

/** 工单来源的产品化说法；未知来源原样显示，不吞掉信息。 */
function channelLabel(channel: string | null | undefined): string {
  const labels: Record<string, string> = { web: '网页', app: 'App', miniapp: '小程序', webhook: 'Webhook', email: '邮件' }
  return channel ? (labels[channel] ?? channel) : '网页'
}

function reasonLabel(reason: string | null): string {
  const labels: Record<string, string> = {
    TOOL_UNAVAILABLE: '系统暂时无法回答',
    USER_REQUESTED: '你要求转人工',
    LLM_TIMEOUT: '模型响应超时',
    LLM_UNAVAILABLE: '模型暂不可用',
    TOOL_ROUNDS_EXHAUSTED: '工具调用未完成',
    BUDGET_EXCEEDED: '今日模型额度用尽',
    REFUND_APPROVAL: '退款待审核',
    EMAIL_REPLY: '邮件回执',
    FEEDBACK_REVIEW: '反馈复核',
  }
  return reason ? (labels[reason] ?? reason) : '人工处理'
}

/** 读取当前选中工单的详情；轮询时静默刷新，不闪加载态。 */
async function loadTicketDetail(showSpinner = true) {
  if (!session.value || !selectedTicket.value) return
  if (showSpinner) detailLoading.value = true
  detailError.value = ''
  const result = await myTicket(session.value.token, selectedTicket.value.id)
  detailLoading.value = false
  if (result.status !== 200 || !result.body || !(result.body as Ticket).id) {
    detailError.value = describe(result, '工单详情暂时读不到')
    return
  }
  const next = result.body as Ticket
  const previous = selectedTicket.value
  if (previous.status !== next.status) {
    progressNote.value = `进度已更新：${STATUS_LABEL[next.status] ?? next.status}`
  }
  selectedTicket.value = next
  const index = tickets.value.findIndex((ticket) => ticket.id === next.id)
  if (index >= 0) tickets.value[index] = next
  if (next.status === 'RESOLVED' && detailTimer) {
    window.clearInterval(detailTimer)
    detailTimer = 0
  }
}

function openTicket(ticket: Ticket) {
  selectedTicket.value = ticket
  detailError.value = ''
  progressNote.value = ''
  if (detailTimer) window.clearInterval(detailTimer)
  void loadTicketDetail(false)
  detailTimer = window.setInterval(() => {
    if (selectedTicket.value && selectedTicket.value.status !== 'RESOLVED') {
      void loadTicketDetail(false)
    }
  }, 8000)
}

function closeTicket() {
  selectedTicket.value = null
  detailError.value = ''
  progressNote.value = ''
  if (detailTimer) {
    window.clearInterval(detailTimer)
    detailTimer = 0
  }
}

onUnmounted(() => {
  if (detailTimer) window.clearInterval(detailTimer)
})

/** 一次流式回答。ask 与 retry 共用，保证「停止 / 重试」不会产生两套状态机。 */
async function runAnswer(text: string, index: number) {
  if (!session.value || asking.value) return
  asking.value = true
  const target = messages.value[index]
  target.pending = true
  target.stopped = false
  target.failed = false
  target.note = undefined
  target.citations = undefined
  target.text = ''
  target.trace = []

  const controller = new AbortController()
  inFlight.value = controller
  const result = await streamAnswer(
    session.value.token,
    text,
    conversation,
    (chunk) => {
      target.text += chunk
    },
    (step) => {
      appendAgentStep(target, step)
    },
    controller.signal,
  )
  asking.value = false
  inFlight.value = null
  target.pending = false

  if (result.status === 499) {
    target.stopped = true
    if (!target.text.trim()) target.text = '已停止生成。'
    target.note = '已停止生成，可以重试；不用重新描述问题。'
    return
  }
  if (result.status !== 200) {
    target.failed = true
    target.text = describe(result, '')
    target.note = '这次没有连上，可以重试；问题已经留在上面。'
    return
  }
  const body = result.body as {
    ticketId: string | null
    citationDetails: CitationDetail[]
    fallbackReason: string | null
  }
  target.citations = body.citationDetails ?? []
  if (!target.text.trim()) {
    target.text = '这次没有收到完整回答，可以重试，或直接转人工。'
  }
  if (body.fallbackReason) {
    const ticketInText = !!body.ticketId && target.text.includes(body.ticketId)
    target.note = body.ticketId
      ? (isGuest.value
        ? `已转人工，工单号 ${body.ticketId}（游客模式不展示工单）`
        : ticketInText
          ? '已转人工，进度在下方「我的工单」里，不用再重复描述。'
          : `已转人工：${body.ticketId}。进度在下方「我的工单」里，不用再重复描述。`)
      : '这次没能直接答出来，已转人工处理，不用再重复描述。'
  }
  if (body.ticketId && !isGuest.value) {
    await refreshTickets()
  }
}

async function ask() {
  const text = query.value.trim()
  if (!session.value || asking.value || !text) return
  query.value = ''
  if (!conversation) conversation = `buyer-${Date.now()}`
  messages.value.push({ role: 'user', text })
  const index = messages.value.push({ role: 'assistant', text: '', pending: true, retryQuery: text }) - 1
  await runAnswer(text, index)
}

function stopAnswer() {
  inFlight.value?.abort()
}

async function retry(index: number) {
  const target = messages.value[index]
  if (!target?.retryQuery || asking.value) return
  await runAnswer(target.retryQuery, index)
}

onMounted(() => {
  if (session.value) {
    if (!isGuest.value) {
      void refreshTickets()
      void refreshOrders()
    }
    void loadStoreInfo()
  } else if (STORE_CODE && AUTO_GUEST) {
    void enterAsGuest()
  } else {
    showLogin.value = !STORE_CODE
  }
  now.value = Date.now()
  window.setInterval(() => {
    now.value = Date.now()
  }, 30_000)
})

function slaText(ticket: Ticket): string {
  if (ticket.escalatedAt) return '已超时（状态不变）'
  const left = minutesLeft(ticket.slaDeadline, now.value)
  if (left === null) return '—'
  return left >= 0 ? `剩 ${left} 分钟` : `超时 ${-left} 分钟`
}

/** 工单时间线用的短时间：同城演示只显示月-日 时:分，不引入日期库。 */
const TOOL_STATUS_LABEL: Record<string, string> = {
  OK: '成功',
  NOT_FOUND: '未找到',
  STATE_NOT_ALLOWED: '当前状态不允许',
  PENDING_APPROVAL: '待人工审核',
  IDEMPOTENT_REPLAY: '已处理过',
  TIMEOUT: '响应超时',
  UNAVAILABLE: '暂不可用',
}

function agentStepText(step: AgentRunStep): string {
  if (step.kind === 'tool_result') {
    return `业务系统返回${TOOL_STATUS_LABEL[step.status ?? ''] ?? (step.status || '结果')}`
  }
  if (step.kind === 'fallback') return '已转人工兜底，工单会出现在侧栏'
  return step.label
}

function appendAgentStep(target: ChatMessage, step: AgentRunStep) {
  const trace = target.trace ?? []
  const previous = trace[trace.length - 1]
  // 状态机可能连续发同名状态；对用户来说那是同一件事，去重后再展示。
  if (previous && step.kind === 'state' && previous.kind === 'state' && previous.label === step.label) {
    return
  }
  target.trace = [...trace, { ...step, at: Date.now() }]
}

interface AgentPhase {
  key: string
  title: string
  hint: string
  steps: AgentRunStep[]
}

/** 把内部 trace 折成三段人话：理解 → 调用 → 回复。 */
function agentPhases(trace: AgentRunStep[]): AgentPhase[] {
  const phases: AgentPhase[] = [
    { key: 'understand', title: '理解需求', hint: '识别意图与政策', steps: [] },
    { key: 'act', title: '调用系统', hint: '查订单 / 物流 / 办理', steps: [] },
    { key: 'reply', title: '生成回复', hint: '汇总结果与引用', steps: [] },
  ]
  const isActStep = (step: AgentRunStep) =>
    step.kind === 'tool' || step.kind === 'tool_result' || step.kind === 'fallback'
    || (step.kind === 'state' && (step.label.includes('调用业务系统') || step.label.includes('调用工具')))
  const actIndexes = trace
    .map((step, index) => ({ step, index }))
    .filter(({ step }) => isActStep(step))
    .map(({ index }) => index)
  const firstAct = actIndexes.length ? actIndexes[0] : -1
  const lastAct = actIndexes.length ? actIndexes[actIndexes.length - 1] : -1
  trace.forEach((step, index) => {
    if (step.kind === 'plan') return
    let phase: AgentPhase
    if (isActStep(step)) {
      phase = phases[1]
    } else if (firstAct >= 0 && index > lastAct) {
      phase = phases[2]
    } else if (firstAct < 0 && (step.label.includes('生成回复') || step.label.includes('写入缓存'))) {
      phase = phases[2]
    } else {
      phase = phases[0]
    }
    phase.steps.push(step)
  })
  return phases.filter((phase) => phase.steps.length)
}

/** plan 事件把步骤串成一句“计划：A → B”，这里拆成 checklist。 */
function agentPlanSteps(trace: AgentRunStep[]): string[] {
  const plan = trace.find((step) => step.kind === 'plan')
  if (!plan) return []
  return plan.label
    .replace(/^计划[:：]\s*/, '')
    .split('→')
    .map((item) => item.trim())
    .filter(Boolean)
}

function agentDuration(trace: AgentRunStep[]): string {
  const times = trace.map((step) => step.at).filter((value): value is number => typeof value === 'number')
  if (times.length < 2) return ''
  const elapsed = Math.max(...times) - Math.min(...times)
  if (elapsed < 1000) return `${elapsed}ms`
  return `${(elapsed / 1000).toFixed(1)}s`
}

function countLabel(value: number): string {
  return value > 99 ? '99+' : String(value)
}

function agentStepTone(step: AgentRunStep): string {
  if (step.kind === 'fallback') return 'danger'
  if (step.kind === 'tool_result') {
    if (step.status === 'OK' || step.status === 'PENDING_APPROVAL') return 'ok'
    if (step.status === 'TIMEOUT' || step.status === 'UNAVAILABLE') return 'danger'
    return 'warn'
  }
  return 'neutral'
}

function formatTime(value: string | null | undefined): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function readStoredSession(): Session | null {
  const raw = sessionStorage.getItem(STORAGE_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as Session
  } catch (unreadable) {
    sessionStorage.removeItem(STORAGE_KEY)
    return null
  }
}

function clearStoredSession() {
  sessionStorage.removeItem(STORAGE_KEY)
}
</script>

<template>
  <main class="wrap">
    <header class="topbar">
      <div class="brand">
        <span class="logo">SP</span>
        <div>
          <strong>ShopPilot</strong>
          <small>买家服务</small>
        </div>
      </div>
      <div class="top-right">
        <span v-if="demoMode" class="badge">体验买家</span>
        <span v-else-if="guestMode" class="badge">访客</span>
        <button v-if="session" class="link" type="button" @click="signOut">退出</button>
      </div>
    </header>

    <section class="store-card">
      <span class="avatar">{{ storeLabel.slice(0, 1) }}</span>
      <div class="store-main">
        <div class="store-title">
          <strong>{{ storeLabel }}</strong>
          <span class="online">在线</span>
        </div>
        <p>{{ storeBusiness || '在线客服 · 查订单 / 退换 / 生鲜理赔' }}</p>
        <p v-if="storeGuarantees" class="guarantee">本店保障：{{ storeGuarantees }}</p>
      </div>
    </section>

    <template v-if="!session">
      <section class="gate">
        <p class="eyebrow">在线客服</p>
        <h1>你好，我是{{ storeLabel }}的客服</h1>
        <p class="lede">
          可以帮你查订单、退换货、生鲜理赔。答不了会转人工，并给你一个可追踪的工单号，不用重复描述。
        </p>
        <div class="gate-actions">
          <button class="primary big" type="button" :disabled="demoLoading || !STORE_CODE" @click="enterAsDemoBuyer">
            {{ demoLoading ? '正在进入…' : '一键体验买家' }}
          </button>
          <button class="link" type="button" @click="showLogin = !showLogin">
            {{ showLogin ? '收起账号登录' : '用买家账号登录' }}
          </button>
        </div>

        <form v-if="showLogin" class="login" @submit.prevent="doLogin">
          <label>买家账号
            <input v-model="username" type="text" autocomplete="username" placeholder="例如 buyer" />
          </label>
          <label>口令
            <input v-model="password" type="password" autocomplete="current-password" placeholder="演示口令见 .env" />
          </label>
          <button class="primary" type="submit" :disabled="!STORE_CODE || !username.trim() || !password">登录</button>
        </form>

        <p v-if="!STORE_CODE" class="err">
          缺少店铺码：请通过店铺链接进入（例如 <span class="mono">/buyer/?store=T001</span>）
        </p>
        <p v-else-if="guestError" class="err">{{ guestError }}</p>
        <p v-else-if="AUTO_GUEST" class="fine">正在为你进入店铺…</p>
        <p v-if="loginError" class="err">{{ loginError }}</p>
        <p class="fine">演示环境可一键进入；正式环境请用买家账号登录。</p>
      </section>
    </template>

    <template v-else>
      <div class="buyer-layout">
        <div class="buyer-main">
      <section v-if="!messages.length" class="welcome">
        <p class="eyebrow">在线客服 · {{ storeLabel }}</p>
        <h1>有什么可以帮你？</h1>
        <p class="welcome-lede">我会先查店铺规则和订单；需要人工时直接转，不让你重复描述。</p>
        <div class="welcome-points">
          <span>查订单与物流</span><span>退换与理赔</span><span>转人工可追踪</span>
        </div>
        <div class="quick">
          <button v-for="q in QUICK_QUESTIONS" :key="q" type="button" class="chip" :disabled="asking" @click="askQuick(q)">
            {{ q }}
          </button>
        </div>
      </section>

      <section v-if="messages.length" class="thread" aria-live="polite">
        <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
          <div class="msg-who">{{ m.role === 'user' ? '我' : '客服' }}</div>
          <div class="bubble" :class="{ streaming: m.pending }">
            <span v-if="m.pending && !m.text" class="typing">正在回答</span>
            <template v-else>{{ m.text }}</template>
          </div>
          <p v-if="m.note" class="fallback">{{ m.note }}</p>
          <details v-if="m.trace?.length" class="agent-run" :open="!!m.pending">
            <summary>
              <span class="agent-dot" :class="{ running: m.pending }"></span>
              <span class="agent-title">{{ m.pending ? 'Agent 正在执行' : 'Agent 执行记录' }}</span>
              <span class="agent-meta">{{ m.trace.length }} 步<template v-if="agentDuration(m.trace)"> · {{ agentDuration(m.trace) }}</template></span>
              <span class="agent-chevron">›</span>
            </summary>
            <div class="agent-body">
              <ol v-if="agentPlanSteps(m.trace).length" class="agent-plan">
                <li v-for="(item, pi) in agentPlanSteps(m.trace)" :key="pi">
                  <span class="plan-dot"></span><span>{{ item }}</span>
                </li>
              </ol>
              <section v-for="phase in agentPhases(m.trace)" :key="phase.key" class="agent-phase">
                <header>
                  <span class="phase-title">{{ phase.title }}</span>
                  <span class="phase-hint">{{ phase.hint }}</span>
                </header>
                <ol>
                  <li v-for="(step, si) in phase.steps" :key="si" :class="[step.kind, agentStepTone(step)]">
                    <span class="step-dot"></span>
                    <span>{{ agentStepText(step) }}</span>
                  </li>
                </ol>
              </section>
              <details class="agent-raw">
                <summary>查看原始步骤</summary>
                <ol>
                  <li v-for="(step, si) in m.trace" :key="si" :class="step.kind">{{ agentStepText(step) }}</li>
                </ol>
              </details>
            </div>
          </details>
          <div v-if="m.stopped || m.failed" class="msg-actions">
            <button class="link" type="button" :disabled="asking" @click="retry(i)">重试</button>
            <span class="fine">问题已经留在上面，不用重新输入。</span>
          </div>
          <details v-if="m.citations?.length" class="cites">
            <summary>依据 {{ m.citations.length }} 条 · 展开</summary>
            <ul>
              <li v-for="(c, j) in m.citations" :key="c.ruleId || j">
                <span class="cite-h">{{ c.heading || c.doc || '政策条款' }}</span>
                <span v-if="c.doc" class="cite-doc">{{ c.doc }}</span>
              </li>
            </ul>
          </details>
        </div>
      </section>

      <section class="composer">
        <input
          v-model="query"
          type="text"
          placeholder="输入问题，例如：订单 90001 到哪了"
          aria-label="输入问题"
          @keyup.enter="ask"
        />
        <button v-if="asking" class="stop" type="button" @click="stopAnswer">停止</button>
        <button v-else class="primary" type="button" :disabled="!query.trim()" @click="ask">发送</button>
      </section>
        </div>

        <aside class="buyer-side">
      <section class="side-card side-activity">
        <div class="side-tabs" role="tablist" aria-label="订单与工单">
          <button
            class="side-tab"
            :class="{ active: sideTab === 'orders' }"
            type="button"
            role="tab"
            :aria-selected="sideTab === 'orders'"
            @click="sideTab = 'orders'"
          >
            订单<span class="tab-count">{{ countLabel(orders.length) }}</span>
          </button>
          <button
            class="side-tab"
            :class="{ active: sideTab === 'tickets' }"
            type="button"
            role="tab"
            :aria-selected="sideTab === 'tickets'"
            @click="sideTab = 'tickets'"
          >
            工单<span class="tab-count">{{ countLabel(tickets.length) }}</span>
          </button>
        </div>

        <template v-if="sideTab === 'orders'">
          <div class="section-head">
            <h2>最近订单</h2>
            <button v-if="!isGuest" class="link" type="button" @click="refreshOrders">刷新</button>
          </div>
          <p v-if="isGuest" class="notice">游客模式不展示订单：登录买家账号后可以查看自己的订单。</p>
          <p v-else-if="orderError" class="err">{{ orderError }}</p>
          <p v-else-if="ordersLoading" class="empty">正在读取订单…</p>
          <div v-else-if="orders.length" class="order-list">
            <article
              v-for="order in visibleOrders"
              :key="order.orderNo"
              class="order-card"
              role="button"
              tabindex="0"
              @click="askOrder(order)"
              @keyup.enter="askOrder(order)"
            >
              <div class="order-top">
                <strong class="mono">{{ order.orderNo }}</strong>
                <span class="pill">{{ ORDER_STATUS_LABEL[order.status] ?? order.status }}</span>
              </div>
              <div class="order-meta">
                <span>{{ order.categoryName }}</span>
                <strong>¥{{ (order.amountFen / 100).toFixed(2) }}</strong>
              </div>
              <p class="order-foot">{{ formatTime(order.createdAt) }} 下单 · 点击询问</p>
            </article>
          </div>
          <p v-else class="empty">你还没有订单</p>
          <button v-if="orders.length > 2" class="link side-more" type="button" @click="showAllOrders = !showAllOrders">
            {{ showAllOrders ? '收起' : '查看全部订单' }}
          </button>
        </template>

        <template v-else>
          <div class="section-head">
            <h2>最近工单</h2>
            <button v-if="!isGuest" class="link" type="button" @click="refreshTickets">刷新</button>
          </div>
          <p v-if="isGuest" class="notice">游客模式不展示工单：先登录买家账号，才能看到自己的售后进度。</p>
          <p v-else-if="ticketError" class="err">{{ ticketError }}</p>
          <p v-else-if="ticketsLoading" class="empty">正在读取…</p>
          <div v-else-if="tickets.length" class="ticket-list">
            <article
              v-for="t in visibleTickets"
              :key="t.id"
              class="ticket-card clickable"
              role="button"
              tabindex="0"
              @click="openTicket(t)"
              @keyup.enter="openTicket(t)"
            >
              <div class="ticket-top">
                <strong class="mono">{{ t.id }}</strong>
                <span class="pill">{{ STATUS_LABEL[t.status] ?? t.status }}</span>
              </div>
              <p class="ticket-query">{{ t.userQuery }}</p>
              <div class="ticket-timeline">
                <span class="step done">已提交 <time>{{ formatTime(t.createdAt) }}</time></span>
                <span v-if="t.escalatedAt" class="step warn">已超时升级 <time>{{ formatTime(t.escalatedAt) }}</time></span>
                <span class="step" :class="{ done: t.status === 'RESOLVED' }">
                  {{ STATUS_LABEL[t.status] ?? t.status }}<template v-if="t.status !== 'RESOLVED'"> · {{ slaText(t) }}</template>
                </span>
              </div>
              <div class="ticket-meta">
                <span>{{ PRIORITY_LABEL[t.priority ?? 'normal'] ?? t.priority }}</span>
                <span>{{ t.assignee ?? '待分配' }}</span>
                <span class="ticket-open">查看进度 →</span>
              </div>
            </article>
          </div>
          <p v-else class="empty">你还没有需要人工处理的工单</p>
          <button v-if="tickets.length > 2" class="link side-more" type="button" @click="showAllTickets = !showAllTickets">
            {{ showAllTickets ? '收起' : '查看全部工单' }}
          </button>
        </template>
      </section>

      <section class="side-card service-card">
        <h2>服务范围</h2>
        <ul>
          <li><strong>订单与物流</strong><span>查状态、金额、轨迹</span></li>
          <li><strong>退换与理赔</strong><span>按店铺政策办理</span></li>
          <li><strong>人工接管</strong><span>答不了就转，给可追踪工单</span></li>
        </ul>
        <p class="side-fine">需要人工时不用重复描述，工单进度会在这里更新。</p>
      </section>
        </aside>
      </div>
    </template>

    <div v-if="selectedTicket" class="drawer-mask" @click.self="closeTicket">
      <aside class="drawer" role="dialog" aria-modal="true" aria-label="工单详情">
        <header class="drawer-head">
          <div>
            <p class="eyebrow">工单详情</p>
            <h2 class="mono">{{ selectedTicket.id }}</h2>
          </div>
          <button class="link" type="button" @click="closeTicket">关闭</button>
        </header>

        <p v-if="detailLoading" class="empty">正在读取最新进度…</p>
        <p v-if="detailError" class="err">{{ detailError }}</p>
        <p v-if="progressNote" class="progress" aria-live="polite">{{ progressNote }}</p>

        <div class="detail-status">
          <span class="pill">{{ STATUS_LABEL[selectedTicket.status] ?? selectedTicket.status }}</span>
          <span>{{ reasonLabel(selectedTicket.reason) }}</span>
        </div>
        <p class="detail-query">{{ selectedTicket.userQuery }}</p>

        <ol class="detail-timeline">
          <li class="done"><span>已提交</span><time>{{ formatTime(selectedTicket.createdAt) }}</time></li>
          <li v-if="selectedTicket.assignee" class="done"><span>坐席 {{ selectedTicket.assignee }} 已接入</span></li>
          <li v-if="selectedTicket.escalatedAt" class="warn">
            <span>已超时升级</span><time>{{ formatTime(selectedTicket.escalatedAt) }}</time>
          </li>
          <li :class="{ done: selectedTicket.status === 'RESOLVED' }">
            <span>{{ STATUS_LABEL[selectedTicket.status] ?? selectedTicket.status }}</span>
            <time v-if="selectedTicket.status !== 'RESOLVED'">{{ slaText(selectedTicket) }}</time>
          </li>
        </ol>

        <dl class="detail-grid">
          <div><dt>优先级</dt><dd>{{ PRIORITY_LABEL[selectedTicket.priority ?? 'normal'] ?? selectedTicket.priority }}</dd></div>
          <div><dt>处理人</dt><dd>{{ selectedTicket.assignee ?? '待分配' }}</dd></div>
          <div><dt>预计时限</dt><dd>{{ formatTime(selectedTicket.slaDeadline) || '—' }}</dd></div>
          <div><dt>渠道</dt><dd>{{ channelLabel(selectedTicket.channel) }}</dd></div>
        </dl>

        <div v-if="selectedTicket.status === 'RESOLVED'" class="resolution">
          <h3>处理结论</h3>
          <p v-if="selectedTicket.resolutionNote">{{ selectedTicket.resolutionNote }}</p>
          <p v-else class="muted">人工已处理完成；本次没有填写结单说明。</p>
          <p class="muted">结论也会按你提问的渠道回传。</p>
        </div>
        <p v-else class="detail-note">坐席正在处理，进度每 8 秒自动更新；不用再重复描述。</p>
        <button class="primary" type="button" @click="loadTicketDetail()">刷新进度</button>
      </aside>
    </div>
  </main>
</template>

<style scoped>
.wrap {
  max-width: 1160px;
  margin: 0 auto;
  padding: 0 20px 80px;
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}
.topbar {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 0;
  background: rgba(242, 243, 245, 0.92);
  backdrop-filter: blur(10px);
}
.brand { display: flex; align-items: center; gap: 10px; }
.logo {
  width: 32px; height: 32px; border-radius: 10px; background: var(--teal); color: #fff;
  display: grid; place-items: center; font-size: 12px; font-weight: 700; letter-spacing: 0.5px;
}
.brand strong { display: block; font-size: 15px; line-height: 1.2; }
.brand small { color: var(--ink-3); font-size: 12px; }
.top-right { display: flex; align-items: center; gap: 10px; }
.badge {
  font-size: 12px; color: var(--teal); border: 1px solid #b7ddd8; background: #eef8f6;
  border-radius: 999px; padding: 1px 8px; white-space: nowrap;
}
.store-card {
  box-sizing: border-box;
  display: flex; gap: 12px; align-items: flex-start;
  background: var(--panel); border: 1px solid var(--line); border-radius: 16px;
  padding: 16px; margin: 4px 0 18px;
}
.avatar {
  width: 42px; height: 42px; border-radius: 12px; background: var(--teal); color: #fff;
  display: grid; place-items: center; font-weight: 650; flex: none;
}
.store-main { min-width: 0; }
.store-title { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.store-title strong { font-size: 16px; }
.store-main p { margin: 4px 0 0; color: var(--ink-2); font-size: 13px; }
.guarantee { color: var(--ink-3) !important; }
.online { color: var(--green); font-size: 12px; white-space: nowrap; }
.online::before { content: '●'; margin-right: 4px; }
.gate {
  box-sizing: border-box;
  background: var(--panel); border: 1px solid var(--line); border-radius: 20px;
  padding: 32px; margin: 12px auto 0; max-width: 640px; width: 100%;
  box-shadow: 0 12px 40px rgba(20, 22, 26, 0.07);
  text-align: center;
}
.eyebrow { margin: 0 0 8px; color: var(--teal); font-size: 12px; font-weight: 600; letter-spacing: 0.08em; }
.gate h1 { margin: 0 0 10px; font-size: 24px; line-height: 1.3; }
.lede { margin: 0 auto 20px; max-width: 520px; color: var(--ink-2); font-size: 14px; }
.gate-actions { display: flex; align-items: center; justify-content: center; gap: 14px; flex-wrap: wrap; }
.big { padding: 10px 20px; font-size: 15px; }
.login {
  display: grid; gap: 10px; max-width: 320px; margin: 20px auto 0; text-align: left;
}
.login label { display: grid; gap: 4px; font-size: 13px; color: var(--ink-2); }
.login input { width: 100%; box-sizing: border-box; }
.login button { margin-top: 2px; }
.link {
  border: none; background: none; color: var(--teal); padding: 0; cursor: pointer;
  font-size: 13px; text-decoration: underline; text-underline-offset: 2px;
}
.link:hover:not(:disabled) { color: var(--teal); }
.fine { color: var(--ink-3); font-size: 12px; margin: 12px 0 0; }
.buyer-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 22px;
  align-items: stretch;
  flex: 1;
  min-height: calc(100vh - 200px);
}
.buyer-main {
  min-width: 0; display: flex; flex-direction: column;
  height: calc(100vh - 200px); min-height: 520px;
}
.buyer-side {
  position: sticky; top: 76px; display: grid; gap: 12px; align-content: start;
  max-height: calc(100vh - 200px); overflow-y: auto; padding-right: 2px;
}
.side-card {
  background: var(--panel); border: 1px solid var(--line); border-radius: 14px;
  padding: 14px; box-sizing: border-box;
}
.side-card h2 { margin: 0 0 10px; font-size: 14px; }
.side-tabs {
  display: flex; gap: 4px; padding: 3px; margin: 0 0 12px;
  background: var(--bg); border-radius: 10px;
}
.side-tab {
  flex: 1; border: none; background: transparent; color: var(--ink-3);
  border-radius: 8px; padding: 7px 8px; cursor: pointer; font-size: 13px;
  display: flex; align-items: center; justify-content: center; gap: 6px;
}
.side-tab.active { background: var(--panel); color: var(--ink); box-shadow: 0 1px 4px rgba(20, 22, 26, .08); font-weight: 600; }
.tab-count {
  min-width: 18px; padding: 0 5px; border-radius: 999px; background: #e7f4f2;
  color: var(--teal); font-size: 11px; line-height: 18px; text-align: center;
}
.side-tab:not(.active) .tab-count { background: var(--line); color: var(--ink-3); }
.side-activity .section-head { margin-bottom: 8px; }
.side-more { display: block; margin: 8px auto 0; font-size: 12px; }
.order-list { display: grid; gap: 8px; max-height: 360px; overflow-y: auto; padding-right: 2px; }
.order-card {
  border: 1px solid var(--line); border-radius: 10px; padding: 9px 10px;
  cursor: pointer; transition: border-color .15s ease, box-shadow .15s ease;
}
.order-card:hover { border-color: #b7ddd8; box-shadow: 0 3px 12px rgba(15, 118, 110, .07); }
.order-card:focus-visible { outline: 2px solid var(--teal); outline-offset: 2px; }
.order-top { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.order-top strong { font-size: 12px; overflow-wrap: anywhere; }
.order-meta { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: 5px; font-size: 12px; color: var(--ink-2); }
.order-meta strong { color: var(--ink); font-size: 13px; }
.order-foot { margin: 5px 0 0; color: var(--ink-3); font-size: 11px; }
.service-card ul { list-style: none; margin: 0; padding: 0; display: grid; gap: 9px; }
.service-card li { display: grid; gap: 1px; }
.service-card strong { font-size: 13px; }
.service-card li span { color: var(--ink-3); font-size: 12px; }
.side-fine { margin: 12px 0 0; color: var(--ink-3); font-size: 12px; line-height: 1.5; }
.welcome {
  background: var(--panel); border: 1px solid var(--line); border-radius: 18px;
  padding: 26px; box-shadow: 0 10px 32px rgba(20, 22, 26, .05);
  flex: 1;
}
.welcome h1 { margin: 0 0 8px; font-size: 26px; line-height: 1.25; }
.welcome p { margin: 0 0 16px; color: var(--ink-2); }
.welcome-lede { max-width: 520px; line-height: 1.7; }
.welcome-points { display: flex; flex-wrap: wrap; gap: 8px; margin: 0 0 18px; }
.welcome-points span {
  font-size: 12px; color: var(--teal); background: #eef8f6;
  border: 1px solid #b7ddd8; border-radius: 999px; padding: 3px 9px;
}
.quick { display: flex; gap: 8px; flex-wrap: wrap; }
.chip {
  border: 1px solid var(--line); background: var(--panel); color: var(--ink);
  border-radius: 999px; padding: 6px 12px; font-size: 13px; cursor: pointer;
}
.chip:hover:not(:disabled) { border-color: var(--teal); color: var(--teal); }
.chip:disabled { opacity: 0.55; cursor: default; }
.thread { display: flex; flex-direction: column; gap: 14px; padding: 8px 4px 4px 0; flex: 1; min-height: 0; overflow-y: auto; }
.msg { max-width: 88%; }
.msg.user { align-self: flex-end; text-align: right; }
.msg-who { font-size: 12px; color: var(--ink-3); margin-bottom: 3px; }
.bubble {
  border: 1px solid var(--line); border-radius: 14px; padding: 9px 13px;
  white-space: pre-wrap; overflow-wrap: anywhere; background: var(--panel); text-align: left;
}
.msg.user .bubble { background: #e7f4f2; border-color: #c9e6e1; }
.bubble.streaming::after { content: '▍'; color: var(--teal); margin-left: 2px; animation: blink 1s steps(2, start) infinite; }
@keyframes blink { 50% { opacity: 0; } }
.typing { color: var(--ink-3); }
.typing::after { content: '…'; }
.agent-run {
  margin: 8px 0 0; text-align: left; border: 1px solid var(--line);
  border-radius: 12px; background: var(--panel); font-size: 12px; overflow: hidden;
}
.agent-run summary {
  cursor: pointer; color: var(--ink-2); display: flex; align-items: center; gap: 8px;
  user-select: none; list-style: none; padding: 10px 12px;
}
.agent-run summary::-webkit-details-marker { display: none; }
.agent-run[open] summary { border-bottom: 1px solid var(--line); }
.agent-dot {
  width: 8px; height: 8px; border-radius: 50%; background: var(--teal);
  box-shadow: 0 0 0 3px #eef8f6; flex: none;
}
.agent-dot.running { animation: pulse 1.2s ease-in-out infinite; }
@keyframes pulse { 50% { box-shadow: 0 0 0 6px rgba(15, 118, 110, .12); } }
.agent-title { color: var(--ink); font-weight: 600; }
.agent-meta { color: var(--ink-3); font-size: 11px; }
.agent-chevron { margin-left: auto; color: var(--ink-3); transition: transform .15s ease; }
.agent-run[open] .agent-chevron { transform: rotate(90deg); }
.agent-body { padding: 10px 12px 12px; display: grid; gap: 12px; }
.agent-plan {
  list-style: none; margin: 0; padding: 8px 10px; display: grid; gap: 5px;
  background: #f4faf9; border: 1px solid #d9eeeb; border-radius: 9px;
}
.agent-plan li { display: flex; align-items: flex-start; gap: 7px; color: var(--ink-2); line-height: 1.45; }
.plan-dot {
  width: 5px; height: 5px; border-radius: 50%; background: var(--teal); flex: none; margin-top: 6px;
}
.agent-phase header { display: flex; align-items: baseline; gap: 8px; margin: 0 0 5px; }
.phase-title { color: var(--ink); font-weight: 600; font-size: 12px; }
.phase-hint { color: var(--ink-3); font-size: 11px; }
.agent-phase ol { list-style: none; margin: 0; padding: 0 0 0 3px; display: grid; gap: 4px; }
.agent-phase li { display: flex; align-items: flex-start; gap: 8px; color: var(--ink-2); line-height: 1.45; }
.step-dot {
  width: 5px; height: 5px; border-radius: 50%; background: #b7ddd8; flex: none; margin-top: 6px;
}
.agent-phase li.ok .step-dot { background: var(--green); }
.agent-phase li.warn .step-dot { background: var(--amber); }
.agent-phase li.danger .step-dot { background: var(--rose); }
.agent-raw { border-top: 1px dashed var(--line); padding-top: 8px; }
.agent-raw summary { cursor: pointer; color: var(--ink-3); font-size: 11px; padding: 0; border: none; }
.agent-raw ol { list-style: none; margin: 6px 0 0; padding: 0 0 0 12px; display: grid; gap: 4px; border-left: 1px dashed #c9e6e1; }
.agent-raw li { position: relative; color: var(--ink-3); line-height: 1.45; }
.agent-raw li::before {
  content: ''; position: absolute; left: -16px; top: 6px; width: 4px; height: 4px;
  border-radius: 50%; background: #b7ddd8;
}
.msg-actions { display: flex; align-items: center; gap: 10px; margin-top: 6px; text-align: left; }
.msg-actions .fine { margin: 0; }
.fallback {
  color: #9a3412; background: #fff7ed; border: 1px solid #fed7aa;
  border-radius: 10px; padding: 8px 10px; font-size: 13px; margin: 8px 0 0;
  text-align: left;
}
.cites { margin: 8px 0 0; text-align: left; font-size: 13px; color: var(--ink-2); }
.cites summary { cursor: pointer; color: var(--ink-3); }
.cites ul { margin: 6px 0 0; padding-left: 18px; }
.cite-h { color: var(--ink-2); }
.cite-doc { color: var(--ink-3); margin-left: 6px; font-family: var(--mono); font-size: 12px; }
.tickets { margin: 0; }
.section-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
h2 { font-size: 15px; margin: 0 0 10px; }
.ticket-list { display: grid; gap: 10px; }
.ticket-card {
  box-sizing: border-box;
  background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 12px;
}
.ticket-card.clickable { cursor: pointer; transition: border-color 0.15s ease, box-shadow 0.15s ease; }
.ticket-card.clickable:hover { border-color: #b7ddd8; box-shadow: 0 4px 16px rgba(15, 118, 110, 0.08); }
.ticket-card.clickable:focus-visible { outline: 2px solid var(--teal); outline-offset: 2px; }
.ticket-open { margin-left: auto; color: var(--teal); white-space: nowrap; }
.ticket-top { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.ticket-top strong { font-size: 13px; overflow-wrap: anywhere; }
.pill {
  flex: none; font-size: 12px; color: var(--teal); background: #eef8f6;
  border: 1px solid #b7ddd8; border-radius: 999px; padding: 1px 8px;
}
.ticket-query { margin: 6px 0 8px; color: var(--ink); overflow-wrap: anywhere; }
.ticket-timeline { display: flex; gap: 12px; flex-wrap: wrap; margin: 0 0 8px; color: var(--ink-3); font-size: 12px; }
.step { position: relative; padding-left: 10px; }
.step::before { content: ''; position: absolute; left: 0; top: 6px; width: 5px; height: 5px; border-radius: 50%; background: var(--line); }
.step.done::before { background: var(--teal); }
.step.warn { color: #9a3412; }
.step.warn::before { background: var(--amber); }
.step time { margin-left: 4px; }
.ticket-meta { display: flex; gap: 14px; flex-wrap: wrap; color: var(--ink-3); font-size: 12px; }
.mono { font-family: var(--mono); }
.composer {
  position: sticky; bottom: 0; z-index: 10;
  display: flex; gap: 10px; align-items: center;
  padding: 12px 0 18px; margin-top: auto;
  background: linear-gradient(180deg, rgba(242, 243, 245, 0), var(--bg) 34%);
}
.composer input {
  flex: 1; min-width: 0; height: 42px; box-sizing: border-box;
  border-radius: 10px; padding: 0 12px;
}
.composer button { height: 42px; padding: 0 18px; flex: none; }
.stop { background: var(--panel); border-color: var(--line); color: var(--rose); }
.err { color: var(--rose); font-size: 13px; margin: 10px 0 0; }
.notice { color: var(--ink-3); font-size: 13px; margin: 0; }
.empty { color: var(--ink-3); font-size: 13px; margin: 0; }
.mono { font-family: ui-monospace, monospace; overflow-wrap: anywhere; }

.drawer-mask {
  position: fixed; inset: 0; z-index: 50;
  display: flex; justify-content: flex-end;
  background: rgba(20, 22, 26, 0.36);
}
.drawer {
  width: min(460px, 100%); height: 100%; overflow-y: auto;
  box-sizing: border-box; padding: 22px;
  background: var(--panel); box-shadow: -12px 0 40px rgba(20, 22, 26, 0.16);
}
.drawer-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.drawer-head h2 { margin: 2px 0 0; font-size: 16px; overflow-wrap: anywhere; }
.detail-status { display: flex; align-items: center; gap: 10px; margin: 18px 0 8px; color: var(--ink-3); font-size: 13px; }
.detail-query { margin: 0 0 18px; padding: 10px 12px; background: var(--bg); border-radius: 10px; overflow-wrap: anywhere; }
.detail-timeline { list-style: none; margin: 0 0 18px; padding: 0; display: grid; gap: 10px; }
.detail-timeline li { position: relative; padding-left: 18px; color: var(--ink-3); font-size: 13px; }
.detail-timeline li::before { content: ''; position: absolute; left: 0; top: 6px; width: 7px; height: 7px; border-radius: 50%; background: var(--line); }
.detail-timeline li.done::before { background: var(--teal); }
.detail-timeline li.warn { color: #9a3412; }
.detail-timeline li.warn::before { background: var(--amber); }
.detail-timeline time { margin-left: 6px; color: var(--ink-3); }
.detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin: 0 0 18px; }
.detail-grid dt { color: var(--ink-3); font-size: 12px; }
.detail-grid dd { margin: 2px 0 0; font-size: 13px; overflow-wrap: anywhere; }
.detail-note { color: var(--ink-2); font-size: 13px; line-height: 1.6; }
.resolution {
  background: #f0faf8; border: 1px solid #c9e6e1; border-radius: 12px;
  padding: 12px 14px; margin: 0 0 14px;
}
.resolution h3 { margin: 0 0 6px; font-size: 13px; color: var(--teal); }
.resolution p { margin: 0 0 6px; font-size: 13px; line-height: 1.6; overflow-wrap: anywhere; }
.resolution p:last-child { margin-bottom: 0; }
.muted { color: var(--ink-3); }
.progress {
  color: var(--teal); background: #eef8f6; border: 1px solid #b7ddd8;
  border-radius: 10px; padding: 8px 10px; font-size: 13px; margin: 12px 0 0;
}

@media (max-width: 640px) {
  .drawer-mask { align-items: flex-end; }
  .drawer { width: 100%; height: auto; max-height: 86vh; border-radius: 16px 16px 0 0; padding: 18px; }
  .detail-grid { grid-template-columns: 1fr; }
}

@media (max-width: 960px) {
  .buyer-layout { grid-template-columns: 1fr; min-height: 0; }
  .buyer-side { position: static; grid-template-columns: 1fr; max-height: none; overflow: visible; }
  .buyer-main { height: auto; min-height: 0; }
  .thread { max-height: 62vh; }
}

@media (max-width: 640px) {
  .wrap { padding: 0 12px 92px; }
  .topbar { padding: 10px 0; }
  .store-card { padding: 12px; border-radius: 12px; margin-bottom: 14px; }
  .welcome { padding: 20px 16px; border-radius: 16px; }
  .welcome h1 { font-size: 22px; }
  .buyer-layout { gap: 14px; }
  .gate { padding: 22px 16px; margin: 10px 0 0; border-radius: 16px; }
  .gate h1 { font-size: 21px; }
  .gate-actions { flex-direction: column; align-items: stretch; }
  .gate-actions .link { align-self: center; margin-top: 10px; }
  .msg { max-width: 96%; }
  .composer { padding: 10px 0 14px; }
  .composer button { padding: 0 14px; }
}
</style>
