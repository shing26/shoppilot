<script setup lang="ts">
/**
 * 买家中心（round27 票 93 / ADR 0057；UX-01 首屏收口）。
 *
 * <p>这一版只做一件事：让买家打开页面就看到「这是哪家店、能帮什么、怎么开始」，
 * 而不是一张需要先问运维要账号的空白表单。容器演示档用一键体验买家进入；
 * 非演示档自动回落到口令登录，不把「端点没开」写成一句死路。
 */
import { computed, onMounted, ref } from 'vue'
import {
  demoBuyerLogin,
  describe,
  guestSession,
  login,
  messageOf,
  myTickets,
  storeProfile,
  streamAnswer,
} from './api'
import { PRIORITY_LABEL, STATUS_LABEL, minutesLeft, type CitationDetail, type Session, type Ticket } from './types'

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
const messages = ref<ChatMessage[]>([])
const tickets = ref<Ticket[]>([])
const ticketsLoading = ref(false)
const ticketError = ref('')
const now = ref(Date.now())
let conversation = ''

// 登录前拿不到店名时显示「本店」，不把内部租户号 T001 当产品文案。
const storeLabel = computed(() => storeName.value || '本店')

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
  await Promise.all([refreshTickets(), loadStoreInfo()])
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
  await Promise.all([refreshTickets(), loadStoreInfo()])
}

function signOut() {
  session.value = null
  demoMode.value = false
  guestMode.value = false
  showLogin.value = true
  clearStoredSession()
  tickets.value = []
  ticketError.value = ''
  messages.value = []
  conversation = ''
  asking.value = false
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
}

async function ask() {
  const text = query.value.trim()
  if (!session.value || asking.value || !text) return
  asking.value = true
  query.value = ''
  if (!conversation) conversation = `buyer-${Date.now()}`

  messages.value.push({ role: 'user', text })
  const index = messages.value.push({ role: 'assistant', text: '', pending: true }) - 1

  const result = await streamAnswer(session.value.token, text, conversation, (chunk) => {
    messages.value[index].text += chunk
  })
  asking.value = false
  messages.value[index].pending = false

  if (result.status !== 200) {
    messages.value[index].text = describe(result, '')
    return
  }
  const body = result.body as {
    ticketId: string | null
    citationDetails: CitationDetail[]
    fallbackReason: string | null
  }
  messages.value[index].citations = body.citationDetails ?? []
  if (!messages.value[index].text.trim()) {
    messages.value[index].text = '这次没有收到完整回答，请重试，或直接转人工。'
  }
  if (body.fallbackReason) {
    const ticketInText = !!body.ticketId && messages.value[index].text.includes(body.ticketId)
    messages.value[index].note = body.ticketId
      ? (isGuest.value
        ? `已转人工，工单号 ${body.ticketId}（游客模式不展示工单）`
        : ticketInText
          ? '已转人工，进度在下方「我的工单」里。'
          : `已转人工：${body.ticketId}。进度在下方「我的工单」里。`)
      : '这次没能直接答出来，已转人工处理'
  }
  if (body.ticketId && !isGuest.value) {
    await refreshTickets()
  }
}

onMounted(() => {
  if (session.value) {
    if (!isGuest.value) void refreshTickets()
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
      <section v-if="!messages.length" class="welcome">
        <h1>有什么可以帮你？</h1>
        <p>我会先查店铺规则和订单；需要人工时直接转，不让你重复描述。</p>
        <div class="quick">
          <button v-for="q in QUICK_QUESTIONS" :key="q" type="button" class="chip" :disabled="asking" @click="askQuick(q)">
            {{ q }}
          </button>
        </div>
      </section>

      <section v-if="messages.length" class="thread">
        <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
          <div class="msg-who">{{ m.role === 'user' ? '我' : '客服' }}</div>
          <div class="bubble">
            <span v-if="m.pending && !m.text" class="typing">正在回答</span>
            <template v-else>{{ m.text }}</template>
          </div>
          <p v-if="m.note" class="fallback">{{ m.note }}</p>
          <details v-if="m.citations?.length" class="cites">
            <summary>依据 {{ m.citations.length }} 条</summary>
            <ul>
              <li v-for="(c, j) in m.citations" :key="c.ruleId || j">
                <span class="cite-h">{{ c.heading || c.doc || '政策条款' }}</span>
                <span v-if="c.doc" class="cite-doc">{{ c.doc }}</span>
              </li>
            </ul>
          </details>
        </div>
      </section>

      <section class="tickets">
        <div class="section-head">
          <h2>我的工单</h2>
          <button v-if="!isGuest" class="link" type="button" @click="refreshTickets">刷新</button>
        </div>
        <p v-if="isGuest" class="notice">游客模式不展示工单：先登录买家账号，才能看到自己的售后进度。</p>
        <p v-else-if="ticketError" class="err">{{ ticketError }}</p>
        <p v-else-if="ticketsLoading" class="empty">正在读取…</p>
        <div v-else-if="tickets.length" class="ticket-list">
          <article v-for="t in tickets" :key="t.id" class="ticket-card">
            <div class="ticket-top">
              <strong class="mono">{{ t.id }}</strong>
              <span class="pill">{{ STATUS_LABEL[t.status] ?? t.status }}</span>
            </div>
            <p class="ticket-query">{{ t.userQuery }}</p>
            <div class="ticket-meta">
              <span>{{ PRIORITY_LABEL[t.priority ?? 'normal'] ?? t.priority }}</span>
              <span>{{ slaText(t) }}</span>
              <span>{{ t.assignee ?? '待分配' }}</span>
            </div>
          </article>
        </div>
        <p v-else class="empty">你还没有需要人工处理的工单</p>
      </section>

      <section class="composer">
        <input
          v-model="query"
          type="text"
          placeholder="输入问题，例如：订单 90001 到哪了"
          aria-label="输入问题"
          @keyup.enter="ask"
        />
        <button class="primary" type="button" :disabled="asking || !query.trim()" @click="ask">
          {{ asking ? '回答中…' : '发送' }}
        </button>
      </section>
    </template>
  </main>
</template>

<style scoped>
.wrap {
  max-width: 880px;
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
.welcome { padding: 18px 0 10px; }
.welcome h1 { margin: 0 0 8px; font-size: 22px; }
.welcome p { margin: 0 0 16px; color: var(--ink-2); }
.quick { display: flex; gap: 8px; flex-wrap: wrap; }
.chip {
  border: 1px solid var(--line); background: var(--panel); color: var(--ink);
  border-radius: 999px; padding: 6px 12px; font-size: 13px; cursor: pointer;
}
.chip:hover:not(:disabled) { border-color: var(--teal); color: var(--teal); }
.chip:disabled { opacity: 0.55; cursor: default; }
.thread { display: flex; flex-direction: column; gap: 14px; padding: 8px 0 4px; }
.msg { max-width: 88%; }
.msg.user { align-self: flex-end; text-align: right; }
.msg-who { font-size: 12px; color: var(--ink-3); margin-bottom: 3px; }
.bubble {
  border: 1px solid var(--line); border-radius: 14px; padding: 9px 13px;
  white-space: pre-wrap; overflow-wrap: anywhere; background: var(--panel); text-align: left;
}
.msg.user .bubble { background: #e7f4f2; border-color: #c9e6e1; }
.typing { color: var(--ink-3); }
.typing::after { content: '…'; }
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
.tickets { margin: 26px 0 8px; }
.section-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
h2 { font-size: 15px; margin: 0 0 10px; }
.ticket-list { display: grid; gap: 10px; }
.ticket-card {
  box-sizing: border-box;
  background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 12px;
}
.ticket-top { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.ticket-top strong { font-size: 13px; overflow-wrap: anywhere; }
.pill {
  flex: none; font-size: 12px; color: var(--teal); background: #eef8f6;
  border: 1px solid #b7ddd8; border-radius: 999px; padding: 1px 8px;
}
.ticket-query { margin: 6px 0 8px; color: var(--ink); overflow-wrap: anywhere; }
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
.err { color: var(--rose); font-size: 13px; margin: 10px 0 0; }
.notice { color: var(--ink-3); font-size: 13px; margin: 0; }
.empty { color: var(--ink-3); font-size: 13px; margin: 0; }
.mono { font-family: ui-monospace, monospace; overflow-wrap: anywhere; }

@media (max-width: 640px) {
  .wrap { padding: 0 12px 92px; }
  .topbar { padding: 10px 0; }
  .store-card { padding: 12px; border-radius: 12px; margin-bottom: 14px; }
  .gate { padding: 22px 16px; margin: 10px 0 0; border-radius: 16px; }
  .gate h1 { font-size: 21px; }
  .gate-actions { flex-direction: column; align-items: stretch; }
  .gate-actions .link { align-self: center; margin-top: 10px; }
  .msg { max-width: 96%; }
  .composer { padding: 10px 0 14px; }
  .composer button { padding: 0 14px; }
}
</style>
