<script setup lang="ts">
/**
 * 坐席工作台（round23 票 73 / ADR 0057；票 83 换成真登录）。
 *
 * <p>三条刻意的口径：
 * <ol>
 *   <li><b>失败不冻结</b>：读不到就写清「读不到 + 为什么」，不留空态、不抛未捕获 Promise
 *       （同调试台那轮修的七处里的一条）；</li>
 *   <li><b>动作防连点</b>：处理是不可逆的资金/责任动作，一次点击只发一次请求
 *       （票 61 在退款面板上踩过这个坑）；</li>
 *   <li><b>坐席身份不再自报</b>（票 83）：页面不再有「坐席名」输入框，署名取自登录令牌。
 *       此前那个框其实已经不起作用——网关会用令牌里的账号覆盖它——留着只会让人以为
 *       改个名字就能换个人（ADR 0058 第 4 条）。</li>
 * </ol>
 */
import { computed, onMounted, ref } from 'vue'
import { claimTicket, describe, listTickets, login, messageOf, releaseTicket, resolveTicket, type Session } from './api'
import { PRIORITY_LABEL, QUEUES, minutesLeft, priorityRank, type Ticket } from './types'

/**
 * 登录态放 sessionStorage 而不是 localStorage。
 *
 * <p>理由是「关掉标签页就登出」：令牌有 30 分钟有效期，而共享机器上 localStorage 里的
 * 令牌活到有人手清缓存为止。演示账号口令仍然可一键填入（下面那个按钮），但**不预填**——
 * 预填一个能直接用的口令，等于把凭证形态又退回成「打开就有」。
 */
const STORAGE_KEY = 'shoppilot.session'

const tenant = ref('T001')
const username = ref('agent')
const password = ref('')
const session = ref<Session | null>(readStoredSession())
const loginError = ref('')
const queue = ref<string>('ALL')
const tickets = ref<Ticket[]>([])
const loadError = ref('')
const actionNote = ref('')
const inFlight = ref<string>('')
const now = ref(Date.now())

let timer = 0

const who = computed(() =>
  session.value ? `${session.value.username}（${session.value.role}）@ ${session.value.tenantId}` : '',
)

const sorted = computed(() =>
  [...tickets.value].sort((a, b) => {
    const byPriority = priorityRank(a.priority) - priorityRank(b.priority)
    return byPriority !== 0 ? byPriority : b.createdAt.localeCompare(a.createdAt)
  }),
)

async function doLogin() {
  loginError.value = ''
  const result = await login(tenant.value.trim(), username.value.trim(), password.value)
  if (result.status !== 200) {
    loginError.value =
      result.status === 0
        ? (messageOf(result.body) ?? '网关不可达')
        : (messageOf(result.body) ?? `登录失败（HTTP ${result.status}）`)
    session.value = null
    clearStoredSession()
    return
  }
  const logged = result.body as Session
  session.value = logged
  // 口令不留痕：登录完立刻清掉输入框，浏览器里也不落。
  password.value = ''
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify(logged))
  await refresh()
}

function signOut() {
  session.value = null
  clearStoredSession()
  tickets.value = []
  loadError.value = ''
  actionNote.value = ''
}

/** 三种失败各有各的说法——混成一句「加载失败」正是调试台修掉的那个毛病。 */
async function refresh() {
  loadError.value = ''
  if (!session.value) {
    loadError.value = '先登录坐席账号——工单队列要坐席身份才能读'
    tickets.value = []
    return
  }
  const result = await listTickets(queue.value, session.value)
  if (result.status === 0) {
    loadError.value = messageOf(result.body) ?? '网关不可达'
    tickets.value = []
    return
  }
  if (result.status === 401 || result.status === 403) {
    loadError.value = result.status === 401 ? '登录已失效，请重新登录' : '这个账号没有坐席权限'
    tickets.value = []
    return
  }
  if (!Array.isArray(result.body)) {
    loadError.value = messageOf(result.body) ?? `队列读取失败（HTTP ${result.status}）`
    tickets.value = []
    return
  }
  tickets.value = result.body
}

async function run(ticket: Ticket, kind: 'claim' | 'release' | 'resolve') {
  if (!session.value || inFlight.value) return // 防连点：一次只允许一个动作在飞
  // 处理完成是不可逆的一步（谁结的它是责任链的终点证据），所以要人点一次确认。
  if (kind === 'resolve' && !window.confirm(`确认处理完 ${ticket.id}？\n结单后不能再流转，且会记进审计。`)) {
    return
  }
  inFlight.value = `${ticket.id}:${kind}`
  actionNote.value = ''
  const result =
    kind === 'claim'
      ? await claimTicket(ticket.id, session.value)
      : kind === 'release'
        ? await releaseTicket(ticket.id, session.value)
        : await resolveTicket(ticket.id, session.value, '')
  actionNote.value = describe(result, { claim: '已领取', release: '已释放', resolve: '已处理完成' }[kind])
  inFlight.value = ''
  await refresh()
}

onMounted(() => {
  void refresh()
  // SLA 倒计时只需要分钟粒度，10 秒一跳足够；不跳得更勤是为了不给浏览器白烧 CPU。
  timer = window.setInterval(() => {
    now.value = Date.now()
  }, 10_000)
  window.addEventListener('beforeunload', () => window.clearInterval(timer))
})

function slaText(ticket: Ticket): string {
  if (ticket.escalatedAt) return '已超时（状态不变）'
  const left = minutesLeft(ticket.slaDeadline, now.value)
  if (left === null) return '无 SLA'
  return left >= 0 ? `剩 ${left} 分钟` : `超时 ${-left} 分钟`
}

function readStoredSession(): Session | null {
  const raw = sessionStorage.getItem(STORAGE_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as Session
  } catch (unreadable) {
    // 会话存储被人手改过：清掉并当未登录，而不是拿一个半截对象去发请求
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
      <h1>坐席工作台</h1>
      <p class="sub">工单与坐席服务（round23 票 72）· 凭证形态：坐席账号登录（票 83 / ADR 0056）</p>
    </header>

    <section v-if="!session" class="ctl">
      <label>店铺 <input v-model="tenant" type="text" placeholder="店铺编号，如 T001" /></label>
      <label>坐席账号 <input v-model="username" type="text" placeholder="坐席账号" /></label>
      <label>口令 <input v-model="password" type="password" placeholder="口令" @keyup.enter="doLogin" /></label>
      <button type="button" @click="doLogin">登录</button>
    </section>

    <!--
      未登录时队列整段不渲染（没有身份就没有「本店的队列」），但**必须说清为什么**，
      否则页面看上去像坏了。买家端那页的同一句提示是清场日当天补的（票 94），
      这里当时漏了——两页由同一轮写成，行为却不一致，这正是那句话存在的理由。
    -->
    <p v-if="!session" class="notice">登录坐席账号后才能看到队列</p>

    <section v-else class="ctl">
      <span class="who">当前身份：{{ who }}<span class="hint">（署名取自登录令牌，页面上不能改）</span></span>
      <label>队列
        <select v-model="queue" @change="refresh">
          <option v-for="q in QUEUES" :key="q" :value="q">{{ q }}</option>
        </select>
      </label>
      <button type="button" @click="refresh">刷新</button>
      <button type="button" @click="signOut">退出登录</button>
    </section>

    <p v-if="loginError" class="err">{{ loginError }}</p>
    <p v-if="loadError" class="err">{{ loadError }}</p>
    <p v-if="actionNote" class="note">{{ actionNote }}</p>

    <table v-if="sorted.length">
      <thead>
        <tr><th>工单号</th><th>来源</th><th>队列</th><th>优先级</th><th>诉求</th><th>SLA</th><th>状态</th><th>操作</th></tr>
      </thead>
      <tbody>
        <tr v-for="t in sorted" :key="t.id">
          <td class="mono">{{ t.id }}</td>
          <td>{{ t.source }}</td>
          <td>{{ t.queue }}</td>
          <td :class="{ urgent: t.priority === 'high' }">{{ PRIORITY_LABEL[t.priority ?? 'normal'] ?? t.priority }}</td>
          <td class="query">{{ t.userQuery }}</td>
          <td :class="{ overdue: !!t.escalatedAt }">{{ slaText(t) }}</td>
          <td>{{ t.status }}<span v-if="t.assignee"> / {{ t.assignee }}</span></td>
          <td class="ops">
            <button type="button" :disabled="!!inFlight" @click="run(t, 'claim')">领取</button>
            <button type="button" :disabled="!!inFlight || t.status === 'OPEN'" @click="run(t, 'release')">释放</button>
            <button type="button" :disabled="!!inFlight || t.status === 'RESOLVED'" @click="run(t, 'resolve')">处理完成</button>
          </td>
        </tr>
      </tbody>
    </table>
    <p v-else-if="!loadError" class="empty">这一档队列里没有待处理工单</p>
  </main>
</template>

<style scoped>
.wrap { font: 14px/1.6 system-ui, sans-serif; margin: 24px auto; max-width: 1100px; padding: 0 16px; }
h1 { font-size: 20px; margin: 0 0 4px; }
.sub { color: #666; margin: 0 0 16px; }
.ctl { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; margin-bottom: 12px; }
.ctl input, .ctl select { padding: 4px 6px; }
.who { font-weight: 600; }
.hint { color: #666; font-weight: 400; margin-left: 6px; }
table { border-collapse: collapse; width: 100%; }
th, td { border-bottom: 1px solid #e5e5e5; padding: 6px 8px; text-align: left; vertical-align: top; }
.mono { font-family: ui-monospace, monospace; }
.query { max-width: 320px; }
.overdue { color: #b42318; }
.urgent { color: #b42318; font-weight: 600; }
.err { color: #b42318; }
.note { color: #146c2e; }
.empty { color: #666; }
.notice { color: #666; margin: 4px 0 0; }
.ops button { margin-right: 6px; }
</style>