<script setup lang="ts">
/**
 * 买家中心（round27 票 93 / ADR 0057；round44 票 188 拆成组件）。
 *
 * <p>这一版只做一件事：让买家打开页面就看到「这是哪家店、能帮什么、怎么开始」，
 * 而不是一张需要先问运维要账号的空白表单。容器演示档用一键体验买家进入；
 * 非演示档自动回落到口令登录，不把「端点没开」写成一句死路。
 *
 * <p>拆分后的状态归属：**所有状态都在这个文件里**。子组件只收 `props`、只发 `emits`，
 * 不持有副本——`session` 一份喂 `isGuest`、每个 API 调用和 gate/layout 切换，
 * 复制出去就会出现「登录了但没登录」。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  answerFeedback,
  demoBuyerLogin,
  describe,
  guestSession,
  login,
  messageOf,
  myOrders,
  myTicket,
  myTickets,
  orderDetail as loadOrderDetail,
  orderLogistics as loadOrderLogistics,
  storeProfile,
  streamAnswer,
} from './api'
import { STATUS_LABEL, type CitationDetail, type Session, type Ticket } from './types'
import type { MyOrder, OrderDetail, OrderLogistics } from './api'
import { appendAgentStep, type ChatMessage } from './lib/format'
import TopBar from './components/TopBar.vue'
import StoreCard from './components/StoreCard.vue'
import LoginGate from './components/LoginGate.vue'
import ChatThread from './components/ChatThread.vue'
import Composer from './components/Composer.vue'
import SideActivity from './components/SideActivity.vue'
import ServiceCard from './components/ServiceCard.vue'
import TicketDrawer from './components/TicketDrawer.vue'

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
/** 展开中的订单号（round44 票 189）：空串表示全部收起。 */
const expandedOrderNo = ref('')
const orderDetail = ref<OrderDetail | null>(null)
const orderLogistics = ref<OrderLogistics | null>(null)
const orderDetailLoading = ref(false)
const orderDetailError = ref('')
const sideTab = ref<'orders' | 'tickets'>('orders')
const showAllOrders = ref(false)
const showAllTickets = ref(false)
const now = ref(Date.now())
let conversation = ''

// ── 显式反馈（round45 票 191）───────────────────────────────────────────────
// 一条回答下面的「有用 / 没用」。点踩落 biz-mock 的 feedback 表 → 进复核队列 →
// 工作台的知识飞轮，这是 round43 那条链在产品里的**唯一真实起点**（门禁脚本此前是直接 fetch）。
// 表单草稿（评哪一条、投什么票、写了什么原因）是页面级状态，所以留在根组件。
const feedbackDraft = ref<{ index: number; verdict: 'UP' | 'DOWN' } | null>(null)
const feedbackReason = ref('')
const feedbackInFlight = ref(false)

// UX-03：工单详情抽屉。选中后轮询进度，结单后停止。
const selectedTicket = ref<Ticket | null>(null)
const detailLoading = ref(false)
const detailError = ref('')
const progressNote = ref('')
let detailTimer = 0

/** SLA 文案要跟着时间走；这个时钟以前没有句柄也没有清理，是实打实的泄漏（票 188 修）。 */
let clockTimer = 0

// 登录前拿不到店名时显示「本店」，不把内部租户号 T001 当产品文案。
const storeLabel = computed(() => storeName.value || '本店')

/** 顶栏署名：取自令牌里的账号，页面上没有可编辑入口。 */
const who = computed(() => session.value?.username ?? '')

const QUICK_QUESTIONS = computed(() => {
  if (tenant.value === 'T004') {
    return ['你们酒店是卖什么的？', '入住和退房时间是几点？', '帮我查一下订单 90001 的物流']
  }
  if (tenant.value === 'T002') {
    return ['你们店是卖什么的？', '生鲜坏了怎么赔', '帮我查一下订单 90001 的物流']
  }
  return ['你们店是卖什么的？', '七天无理由怎么退', '帮我查一下订单 90001 的物流']
})

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
  expandedOrderNo.value = ''
  orderDetail.value = null
  orderLogistics.value = null
  orderDetailError.value = ''
  messages.value = []
  conversation = ''
  asking.value = false
  feedbackDraft.value = null
  feedbackReason.value = ''
  feedbackInFlight.value = false
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
  if (expandedOrderNo.value && !orders.value.some((order) => order.orderNo === expandedOrderNo.value)) {
    expandedOrderNo.value = ''
    orderDetail.value = null
    orderLogistics.value = null
    orderDetailError.value = ''
  }
}

async function askOrder(order: MyOrder) {
  query.value = `帮我查一下订单 ${order.orderNo} 的物流`
  await ask()
}

/**
 * 展开 / 收起一张订单的深度卡（round44 票 189）。
 *
 * <p>详情与物流并行拉；期间用户切到别的单，回来的响应直接丢弃——否则慢响应会把
 * 当前展开的卡片覆盖成上一张的内容。
 */
async function toggleOrder(orderNo: string) {
  if (expandedOrderNo.value === orderNo) {
    expandedOrderNo.value = ''
    orderDetail.value = null
    orderLogistics.value = null
    orderDetailError.value = ''
    orderDetailLoading.value = false
    return
  }
  expandedOrderNo.value = orderNo
  orderDetail.value = null
  orderLogistics.value = null
  orderDetailError.value = ''
  if (!session.value) return
  const token = session.value.token
  orderDetailLoading.value = true
  const [detail, logistics] = await Promise.all([
    loadOrderDetail(token, orderNo),
    loadOrderLogistics(token, orderNo),
  ])
  if (expandedOrderNo.value !== orderNo) return
  orderDetailLoading.value = false
  if (detail.status === 200) {
    orderDetail.value = detail.body as OrderDetail
  } else {
    orderDetailError.value = describe(detail, '')
  }
  if (logistics.status === 200) {
    orderLogistics.value = logistics.body as OrderLogistics
  }
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
  if (clockTimer) window.clearInterval(clockTimer)
})

/** 一次流式回答。ask 与 retry 共用，保证「停止 / 重试」不会产生两套状态机。 */
async function runAnswer(text: string, index: number) {
  if (!session.value || asking.value) return
  asking.value = true
  // 注意：这是 messages 里的**同一个对象**，流式分片原地写进去，不 clone。
  const target = messages.value[index]
  target.pending = true
  target.stopped = false
  target.failed = false
  target.note = undefined
  target.citations = undefined
  target.text = ''
  target.trace = []
  // 重试是一次新的回答：上一票作废，否则旧票会挂在新答案上。
  target.feedback = undefined
  target.feedbackState = undefined
  target.feedbackError = undefined
  if (feedbackDraft.value?.index === index) {
    feedbackDraft.value = null
    feedbackReason.value = ''
  }

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
  const index = messages.value.push({
    role: 'assistant',
    text: '',
    pending: true,
    retryQuery: text,
    // 会话 ID 跟着这条回答走（round45 票 191）：点踩要回指**这一次**回答，
    // 而不是页面级「最后一次问」——一次打开里可能已经问了好几轮。
    conversationId: conversation,
  }) - 1
  await runAnswer(text, index)
}

function stopAnswer() {
  inFlight.value?.abort()
}

/** 「有用 / 没用」的入口。赞直接投；踩先展开原因框——原因正是复核人要读的那句话。 */
function rateMessage(index: number, verdict: 'UP' | 'DOWN') {
  const target = messages.value[index]
  if (!target || target.role !== 'assistant' || target.pending || target.failed || target.stopped) return
  if (target.feedback || feedbackInFlight.value) return
  feedbackDraft.value = { index, verdict }
  feedbackReason.value = ''
  if (verdict === 'UP') void submitFeedback()
}

function cancelFeedback() {
  if (feedbackInFlight.value) return
  feedbackDraft.value = null
  feedbackReason.value = ''
}

/**
 * 投出这一票。
 *
 * <p>失败不吞：把下游那句话写在消息下面、把状态退回「未评」，用户可以再点一次。
 * 成功后才把 `feedback` 写进消息对象——先写再发会让失败看起来像成功。
 */
async function submitFeedback() {
  const pending = feedbackDraft.value
  if (!pending || !session.value || feedbackInFlight.value) return
  const target = messages.value[pending.index]
  if (!target?.conversationId) return
  feedbackInFlight.value = true
  target.feedbackError = ''
  target.feedbackState = 'sending'
  const result = await answerFeedback(
    session.value.token,
    target.conversationId,
    pending.verdict,
    feedbackReason.value.trim(),
  )
  feedbackInFlight.value = false
  if (result.status < 200 || result.status >= 300) {
    target.feedbackState = undefined
    target.feedbackError = describe(result, '反馈没发出去，可以再试一次')
    return
  }
  target.feedback = pending.verdict
  target.feedbackState = 'done'
  target.feedbackError = ''
  feedbackDraft.value = null
  feedbackReason.value = ''
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
  clockTimer = window.setInterval(() => {
    now.value = Date.now()
  }, 30_000)
})

/**
 * 读回本标签页的登录态。
 *
 * <p>round45 票 191：**店铺码来自 URL，会话也记着自己属于哪家店**。两者不一致时必须丢弃，
 * 否则同一标签页从 A 店跳到 B 店会沿用 A 店的令牌——令牌是真的，只是店不对：店卡、快捷问题
 * 与助手回答会分裂成三家店（首页四个店铺链接就是按这个路径点的）。
 *
 * <p>没有店铺码时不做判断：那说明用户是直接打开 `/buyer/`，沿用上一次的身份比默默登出更合理。
 */
function readStoredSession(): Session | null {
  const raw = sessionStorage.getItem(STORAGE_KEY)
  if (!raw) return null
  try {
    const stored = JSON.parse(raw) as Session
    if (STORE_CODE && stored.tenantId !== STORE_CODE) {
      sessionStorage.removeItem(STORAGE_KEY)
      return null
    }
    return stored
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
    <TopBar
      :demo-mode="demoMode"
      :guest-mode="guestMode"
      :has-session="!!session"
      :who="who"
      @sign-out="signOut"
    />

    <StoreCard :label="storeLabel" :business="storeBusiness" :guarantees="storeGuarantees" />

    <template v-if="!session">
      <LoginGate
        v-model:username="username"
        v-model:password="password"
        v-model:show-login="showLogin"
        :store-label="storeLabel"
        :demo-loading="demoLoading"
        :has-store-code="!!STORE_CODE"
        :auto-guest="AUTO_GUEST"
        :guest-error="guestError"
        :login-error="loginError"
        @demo-enter="enterAsDemoBuyer"
        @submit="doLogin"
      />
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

          <ChatThread
            v-if="messages.length"
            v-model:feedback-reason="feedbackReason"
            :messages="messages"
            :asking="asking"
            :feedback-draft="feedbackDraft"
            :feedback-in-flight="feedbackInFlight"
            @retry="retry"
            @rate="rateMessage"
            @submit-feedback="submitFeedback"
            @cancel-feedback="cancelFeedback"
          />

          <Composer v-model:query="query" :asking="asking" @submit="ask" @stop="stopAnswer" />
        </div>

        <aside class="buyer-side">
          <SideActivity
            v-model:side-tab="sideTab"
            v-model:show-all-orders="showAllOrders"
            v-model:show-all-tickets="showAllTickets"
            :orders="orders"
            :tickets="tickets"
            :orders-loading="ordersLoading"
            :tickets-loading="ticketsLoading"
            :order-error="orderError"
            :ticket-error="ticketError"
            :is-guest="isGuest"
            :now="now"
            :expanded-order-no="expandedOrderNo"
            :order-detail="orderDetail"
            :order-logistics="orderLogistics"
            :order-detail-loading="orderDetailLoading"
            :order-detail-error="orderDetailError"
            @refresh-orders="refreshOrders"
            @refresh-tickets="refreshTickets"
            @toggle-order="toggleOrder"
            @ask-order="askOrder"
            @open-ticket="openTicket"
          />
          <ServiceCard />
        </aside>
      </div>
    </template>

    <TicketDrawer
      v-if="selectedTicket"
      :ticket="selectedTicket"
      :detail-loading="detailLoading"
      :detail-error="detailError"
      :progress-note="progressNote"
      :now="now"
      @close="closeTicket"
      @refresh="loadTicketDetail()"
    />
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
  font-size: 12px; color: var(--teal); background: var(--teal-soft);
  border: 1px solid var(--teal-line); border-radius: 999px; padding: 3px 9px;
}
.quick { display: flex; gap: 8px; flex-wrap: wrap; }
.chip {
  border: 1px solid var(--line); background: var(--panel); color: var(--ink);
  border-radius: 999px; padding: 6px 12px; font-size: 13px; cursor: pointer;
}
.chip:hover:not(:disabled) { border-color: var(--teal); color: var(--teal); }
.chip:disabled { opacity: 0.55; cursor: default; }

@media (max-width: 960px) {
  .buyer-layout { grid-template-columns: 1fr; min-height: 0; }
  .buyer-side { position: static; grid-template-columns: 1fr; max-height: none; overflow: visible; }
  .buyer-main { height: auto; min-height: 0; }
}

@media (max-width: 640px) {
  .wrap { padding: 0 12px 92px; }
  .welcome { padding: 20px 16px; border-radius: 16px; }
  .welcome h1 { font-size: 22px; }
  .buyer-layout { gap: 14px; }
}
</style>
