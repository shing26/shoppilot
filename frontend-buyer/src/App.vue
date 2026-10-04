<script setup lang="ts">
/**
 * 买家中心（round27 票 93 / ADR 0057）。
 *
 * <p>三条口径，与另两个前端同族：
 * <ol>
 *   <li><b>失败不冻结</b>：读不到就写清「读不到 + 为什么」，不留空态、不抛未捕获 Promise
 *       （调试台那轮修过的坑）；</li>
 *   <li><b>登录态在 sessionStorage</b>：关掉标签页就登出。令牌只有 30 分钟 TTL，
 *       而 localStorage 里的令牌活到有人手清缓存为止；</li>
 *   <li><b>口令登录完立刻清掉</b>，不落任何存储。</li>
 * </ol>
 *
 * <p>本页面**只读**：它不触发投递、不改工单状态——「处理完成 → 结论送出站」那条链的
 * 终点在网关消费端（round26），不在这里。
 */
import { computed, onMounted, ref } from 'vue'
import { describe, login, messageOf, myTickets, streamAnswer } from './api'
import { PRIORITY_LABEL, STATUS_LABEL, minutesLeft, type Citation, type Session, type Ticket } from './types'

const STORAGE_KEY = 'shoppilot.buyer.session'

const tenant = ref('T001')
const username = ref('')
const password = ref('')
const session = ref<Session | null>(readStoredSession())
const loginError = ref('')

const query = ref('')
const answer = ref('')
const citations = ref<Citation[]>([])
const fallbackNote = ref('')
const asking = ref(false)

const tickets = ref<Ticket[]>([])
const ticketError = ref('')
const now = ref(Date.now())
let conversation = ''

const who = computed(() =>
  session.value ? `${session.value.displayName ?? session.value.username}（${session.value.role}）` : '',
)

async function doLogin() {
  loginError.value = ''
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
  await refreshTickets()
}

function signOut() {
  session.value = null
  clearStoredSession()
  tickets.value = []
  ticketError.value = ''
  answer.value = ''
  citations.value = []
}

async function refreshTickets() {
  ticketError.value = ''
  if (!session.value) {
    ticketError.value = '先登录买家账号——工单列表要买家身份才能读'
    tickets.value = []
    return
  }
  const result = await myTickets(session.value.token)
  if (result.status !== 200 || !Array.isArray(result.body)) {
    ticketError.value = describe(result, '')
    tickets.value = []
    return
  }
  tickets.value = result.body
}

async function ask() {
  if (!session.value || asking.value || !query.value.trim()) return
  asking.value = true
  fallbackNote.value = ''
  citations.value = []
  answer.value = ''
  if (!conversation) conversation = `buyer-${Date.now()}`
  const result = await streamAnswer(session.value.token, query.value.trim(), conversation, (chunk) => {
    answer.value += chunk
  })
  asking.value = false
  if (result.status !== 200) {
    answer.value = describe(result, '')
    return
  }
  const body = result.body as { ticketId: string | null; citations: Citation[]; fallbackReason: string | null }
  citations.value = body.citations ?? []
  // 降级也是一种结局：说清楚发生了什么，并给出工单号，而不是给一句「稍后」
  if (body.fallbackReason) {
    fallbackNote.value = body.ticketId
      ? `这次没能直接答出来，已经转人工，工单号 ${body.ticketId}（在下方「我的工单」里能看到进度）`
      : '这次没能直接答出来，已经转人工处理'
  }
  if (body.ticketId) {
    await refreshTickets()
  }
}

onMounted(() => {
  void refreshTickets()
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
      <p class="sub">对话与售后进度 · 工单由人工处理，结论会回到你问的那个渠道</p>
    </header>

    <section v-if="!session" class="ctl">
      <label>店铺 <input v-model="tenant" type="text" placeholder="店铺编号，如 T001" /></label>
      <label>账号 <input v-model="username" type="text" placeholder="买家账号" /></label>
      <label>口令 <input v-model="password" type="password" placeholder="口令" @keyup.enter="doLogin" /></label>
      <button type="button" @click="doLogin">登录</button>
    </section>

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

      <section v-if="answer || fallbackNote" class="answer">
        <p v-if="fallbackNote" class="warn">{{ fallbackNote }}</p>
        <p v-if="answer" class="body">{{ answer }}</p>
        <ul v-if="citations.length" class="cites">
          <li v-for="(c, i) in citations" :key="c.ruleId ?? i">
            <span class="mono">{{ c.ruleId }}</span>
            <span>{{ c.heading ?? c.doc ?? '政策条款' }}</span>
          </li>
        </ul>
      </section>

      <section class="tickets">
        <h2>我的工单</h2>
        <p v-if="ticketError" class="err">{{ ticketError }}</p>
        <table v-if="tickets.length">
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
        <p v-else-if="!ticketError" class="empty">你还没有需要人工处理的工单</p>
      </section>
    </template>
  </main>
</template>

<style scoped>
.wrap { font: 14px/1.6 system-ui, sans-serif; margin: 24px auto; max-width: 900px; padding: 0 16px; }
h1 { font-size: 20px; margin: 0 0 4px; }
h2 { font-size: 15px; margin: 24px 0 8px; }
.sub { color: #666; margin: 0 0 16px; }
.ctl { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; margin-bottom: 12px; }
.ctl input, .ask input { padding: 4px 6px; }
.who { font-weight: 600; }
.hint { color: #666; font-weight: 400; margin-left: 6px; }
.ask { display: flex; gap: 12px; align-items: center; margin: 8px 0; }
.ask input { flex: 1; }
.answer { border-left: 3px solid #d0d0d0; padding-left: 12px; margin: 12px 0; }
.answer .body { margin: 4px 0; white-space: pre-wrap; }
.warn { color: #b54708; }
.cites { color: #666; font-size: 13px; padding-left: 18px; }
.mono { font-family: ui-monospace, monospace; margin-right: 6px; }
table { border-collapse: collapse; width: 100%; }
th, td { border-bottom: 1px solid #e5e5e5; padding: 6px 8px; text-align: left; vertical-align: top; }
.query { max-width: 320px; }
.overdue { color: #b42318; }
.urgent { color: #b42318; font-weight: 600; }
.done { color: #146c2e; }
.err { color: #b42318; }
.empty { color: #666; }
</style>