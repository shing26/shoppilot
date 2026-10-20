<script setup lang="ts">
/**
 * 买家中心（round27 票 93 / ADR 0057；round36 票 151 产品化）。
 *
 * <p>三条口径不变，与另两个前端同族：
 * <ol>
 *   <li><b>失败不冻结</b>：读不到就写清「读不到 + 为什么」，不留空态、不抛未捕获 Promise；</li>
 *   <li><b>登录态在 sessionStorage</b>：关掉标签页就登出（令牌 30 分钟 TTL）；</li>
 *   <li><b>口令登录完立刻清掉</b>，不落任何存储。</li>
 * </ol>
 *
 * <p>round36 的三处产品化：
 * <ul>
 *   <li><b>对话流</b>：消息数组而不是单个 `answer`——此前每次提问先清空，页面永远只有最后一轮；</li>
 *   <li><b>店铺入口</b>：不再要求买家输入内部租户号，店铺码走 URL（`?store=T001`），
 *       登录后用店名显示（ADR 0070 取 (a) 方案，不新增未鉴权端点）；</li>
 *   <li><b>加载态</b>：读取中不再渲染成「你还没有需要人工处理的工单」。</li>
 * </ul>
 *
 * <p>本页面**只读**工单：它不触发投递、不改工单状态。
 */
import { computed, onMounted, ref } from 'vue'
import { describe, login, messageOf, myTickets, streamAnswer, stores } from './api'
import { PRIORITY_LABEL, STATUS_LABEL, minutesLeft, type CitationDetail, type Session, type Ticket } from './types'

const STORAGE_KEY = 'shoppilot.buyer.session'

/** 店铺码来自 URL；缺省时不猜、也不退回内部 id 输入框（ADR 0070 B2）。 */
const STORE_CODE = (new URLSearchParams(window.location.search).get('store') ?? '').trim()

const tenant = ref(STORE_CODE)
const storeName = ref('')
const username = ref('')
const password = ref('')
const session = ref<Session | null>(readStoredSession())
const loginError = ref('')

const query = ref('')
const asking = ref(false)

interface ChatMessage {
  role: 'user' | 'assistant'
  text: string
  pending?: boolean
  /** 本轮的降级说明；只挂在它所属的那条回答上，不串到下一轮。 */
  note?: string
  citations?: CitationDetail[]
}

const messages = ref<ChatMessage[]>([])

const tickets = ref<Ticket[]>([])
const ticketsLoading = ref(false)
const ticketError = ref('')
const now = ref(Date.now())
let conversation = ''

const who = computed(() =>
  session.value ? `${session.value.displayName ?? session.value.username}（${session.value.role}）` : '',
)

/** 登录后把内部租户号换成店名；取不到就继续显示店铺码，不编一个名字。 */
async function loadStoreName() {
  if (!session.value) return
  const result = await stores(session.value.token)
  if (result.status !== 200 || !Array.isArray(result.body)) return
  const found = result.body.find((s) => s.tenantId === session.value?.tenantId)
  if (found?.name) storeName.value = found.name
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
  password.value = ''
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify(result.body))
  await Promise.all([refreshTickets(), loadStoreName()])
}

function signOut() {
  session.value = null
  clearStoredSession()
  tickets.value = []
  ticketError.value = ''
  messages.value = []
  conversation = ''
  asking.value = false
}

/** 三态分开：读取中不得渲染成空态（round36 / 票 151）。 */
async function refreshTickets() {
  ticketError.value = ''
  if (!session.value) {
    ticketError.value = '先登录买家账号——工单列表要买家身份才能读'
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
    // 必须穿过数组代理写，直接改 push 进去的原对象不会触发渲染
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
  // 降级也是一种结局：说清楚发生了什么，并给出工单号，而不是给一句「稍后」
  if (body.fallbackReason) {
    messages.value[index].note = body.ticketId
      ? `这次没能直接答出来，已经转人工，工单号 ${body.ticketId}（在下方「我的工单」里能看到进度）`
      : '这次没能直接答出来，已经转人工处理'
  }
  if (body.ticketId) {
    await refreshTickets()
  }
}

onMounted(() => {
  void refreshTickets()
  void loadStoreName()
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
    <header>
      <h1>买家中心</h1>
      <p class="sub">
        <span v-if="storeName || STORE_CODE" class="store">{{ storeName || STORE_CODE }}</span>
        对话与售后进度 · 工单由人工处理，结论会回到你问的那个渠道
      </p>
    </header>

    <section v-if="!session" class="ctl">
      <label>账号 <input v-model="username" type="text" placeholder="买家账号" /></label>
      <label>口令 <input v-model="password" type="password" placeholder="口令" @keyup.enter="doLogin" /></label>
      <button type="button" :disabled="!STORE_CODE" @click="doLogin">登录</button>
    </section>

    <!--
      未登录时「我的工单」整段不渲染（没有身份就没有「我的」），但**必须说清为什么**。
      店铺码缺失是另一种「为什么」，单独说，别让页面看上去像坏了。
    -->
    <p v-if="!STORE_CODE" class="notice">
      缺少店铺码：请通过店铺链接进入（例如 <span class="mono">/buyer/?store=T001</span>）
    </p>
    <p v-else-if="!session" class="notice">登录后这里会显示你的工单与售后进度</p>

    <section v-else class="ctl">
      <span class="who">当前身份：{{ who }}<span class="hint">（@ {{ session.tenantId }}）</span></span>
      <button type="button" @click="signOut">退出登录</button>
    </section>

    <p v-if="loginError" class="err">{{ loginError }}</p>

    <template v-if="session">
      <section class="ask">
        <label>我要问
          <input
            v-model="query"
            type="text"
            placeholder="七天无理由退货怎么操作"
            @keyup.enter="ask"
          />
        </label>
        <button type="button" :disabled="asking || !query.trim()" @click="ask">
          {{ asking ? '正在回答…' : '提问' }}
        </button>
      </section>

      <!-- 对话流：每条回答带自己的引用与降级说明，不串到下一轮。 -->
      <section v-if="messages.length" class="thread">
        <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
          <div class="msg-who">{{ m.role === 'user' ? '我' : '客服' }}</div>
          <div class="bubble">
            <span v-if="m.pending && !m.text" class="typing">正在回答</span>
            <template v-else>{{ m.text }}</template>
          </div>
          <p v-if="m.note" class="warn">{{ m.note }}</p>
          <ul v-if="m.citations?.length" class="cites">
            <li v-for="(c, j) in m.citations" :key="c.ruleId || j" :title="c.ruleId">
              <span class="cite-h">{{ c.heading || c.doc || '政策条款' }}</span>
              <span v-if="c.doc" class="cite-doc">{{ c.doc }}</span>
            </li>
          </ul>
        </div>
      </section>

      <section class="tickets">
        <h2>我的工单</h2>
        <p v-if="ticketError" class="err">{{ ticketError }}</p>
        <p v-if="ticketsLoading" class="empty">正在读取…</p>
        <div v-if="tickets.length" class="table-wrap">
          <table>
            <thead>
              <tr><th>工单号</th><th>我问了什么</th><th>状态</th><th>优先级</th><th>预计时限</th><th>处理人</th></tr>
            </thead>
            <tbody>
              <tr v-for="t in tickets" :key="t.id">
                <td class="mono">{{ t.id }}</td>
                <td class="query">{{ t.userQuery }}</td>
                <td :class="{ done: t.status === 'RESOLVED' }">{{ STATUS_LABEL[t.status] ?? t.status }}</td>
                <td :class="{ urgent: t.priority === 'high' }">{{ PRIORITY_LABEL[t.priority ?? 'normal'] ?? t.priority }}</td>
                <td :class="{ overdue: !!t.escalatedAt }">{{ slaText(t) }}</td>
                <td>{{ t.assignee ?? '待分配' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else-if="!ticketError && !ticketsLoading" class="empty">你还没有需要人工处理的工单</p>
      </section>
    </template>
  </main>
</template>

<style scoped>
.wrap { font: 14px/1.6 system-ui, sans-serif; margin: 24px auto; max-width: 900px; padding: 0 16px; }
h1 { font-size: 20px; margin: 0 0 4px; }
h2 { font-size: 15px; margin: 24px 0 8px; }
.sub { color: #666; margin: 0 0 16px; }
.store { color: #14161a; font-weight: 600; margin-right: 8px; }
.ctl { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; margin-bottom: 12px; }
.ctl input, .ask input { padding: 4px 6px; }
.who { font-weight: 600; }
.hint { color: #666; font-weight: 400; margin-left: 6px; }
.ask { display: flex; gap: 12px; align-items: center; margin: 8px 0; }
.ask input { flex: 1; }
.thread { margin: 16px 0; display: flex; flex-direction: column; gap: 14px; }
.msg { max-width: 86%; }
.msg.user { align-self: flex-end; text-align: right; }
.msg-who { font-size: 12px; color: #666; margin-bottom: 3px; }
.bubble { border: 1px solid #e0e3e7; border-radius: 10px; padding: 8px 11px; white-space: pre-wrap;
  overflow-wrap: anywhere; background: #fff; text-align: left; }
.msg.user .bubble { background: #f2f4f7; border-color: #f2f4f7; }
.typing { color: #666; }
.typing::after { content: '…'; }
.warn { color: #b54708; font-size: 13px; margin: 6px 0 0; }
.cites { color: #666; font-size: 13px; padding-left: 18px; margin: 6px 0 0; text-align: left; }
/* 引用主文本是标题，出处是 slug；ruleId 是内容哈希，只留在 title 里供溯源。 */
.cite-h { color: var(--ink-2); }
.cite-doc { color: var(--ink-3); margin-left: 6px; font-family: var(--mono); font-size: 12px; }
.mono { font-family: ui-monospace, monospace; margin-right: 6px; overflow-wrap: anywhere; }
/* 窄屏（round36 / 票 151）：宽表格在容器内横向滚动，页面本身不横向溢出 */
.table-wrap { overflow-x: auto; }
table { border-collapse: collapse; width: 100%; min-width: 560px; }
th, td { border-bottom: 1px solid #e5e5e5; padding: 6px 8px; text-align: left; vertical-align: top; }
.query { max-width: 320px; }
.overdue { color: #b42318; }
.urgent { color: #b42318; font-weight: 600; }
.done { color: #146c2e; }
.err { color: #b42318; }
.empty { color: #666; }
.notice { color: #666; margin: 4px 0 0; }
</style>
