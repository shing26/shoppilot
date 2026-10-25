<script setup lang="ts">
/**
 * 坐席工作台（UX-04）：队列 + 工单详情 + 动作，一次只处理一张单。
 *
 * <p>保留三条旧口径：
 * 1. 失败不冻结——读不到就写清「读不到 + 为什么」；
 * 2. 动作防连点——一次只允许一个动作在飞；
 * 3. 署名取自登录令牌，页面不能改。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  claimTicket,
  demoLogin,
  describe,
  listPendingRefunds,
  listTickets,
  login,
  messageOf,
  qualityReport,
  releaseTicket,
  resolveTicket,
  reviewRefund,
  type QualityReport,
  type RefundItem,
  type Session,
} from './api'
import { PRIORITY_LABEL, QUEUE_LABEL, QUEUES, SOURCE_LABEL, STATUS_LABEL, minutesLeft, priorityRank, type Ticket } from './types'

const STORAGE_KEY = 'shoppilot.session'

const tenant = ref('T001')
const username = ref('agent')
const password = ref('')
const showLogin = ref(false)
const demoLoading = ref(false)
const session = ref<Session | null>(readStoredSession())
const loginError = ref('')
const queue = ref<string>('ALL')
type StatusFilter = 'ALL' | 'OPEN' | 'ASSIGNED' | 'OVERDUE'
const statusFilter = ref<StatusFilter>('ALL')
const tickets = ref<Ticket[]>([])
const loadError = ref('')
const loading = ref(false)
const actionNote = ref('')
const actionError = ref('')
const inFlight = ref<string>('')
const selectedId = ref('')
const now = ref(Date.now())

const resolveTarget = ref<Ticket | null>(null)
const resolveNote = ref('')
const resolveError = ref('')

const refunds = ref<RefundItem[]>([])
const refundError = ref('')
const refundNote = ref('')
const refundInFlight = ref<number>(0)
const refundTarget = ref<RefundItem | null>(null)
const refundDecision = ref<'APPROVE' | 'REJECT'>('APPROVE')
const refundDialogNote = ref('')
const refundDialogError = ref('')

// ADR 0073：工作台是三视图控制台；质量视图只读，不做任何干预动作。
type WorkspaceView = 'queue' | 'refunds' | 'quality'
const view = ref<WorkspaceView>('queue')
const quality = ref<QualityReport | null>(null)
const qualityLoading = ref(false)
const qualityError = ref('')

let timer = 0

const who = computed(() =>
  session.value ? `${session.value.username}（${session.value.role}）@ ${session.value.tenantId}` : '',
)

const counts = computed(() => ({
  open: tickets.value.filter((t) => t.status === 'OPEN').length,
  assigned: tickets.value.filter((t) => t.status === 'ASSIGNED').length,
  overdue: tickets.value.filter((t) => isOverdue(t)).length,
}))

const filtered = computed(() => {
  const rows = tickets.value.filter((ticket) => {
    if (statusFilter.value === 'ALL') return true
    if (statusFilter.value === 'OPEN') return ticket.status === 'OPEN'
    if (statusFilter.value === 'ASSIGNED') return ticket.status === 'ASSIGNED'
    return isOverdue(ticket)
  })
  return rows.sort((a, b) => {
    const byPriority = priorityRank(a.priority) - priorityRank(b.priority)
    return byPriority !== 0 ? byPriority : b.createdAt.localeCompare(a.createdAt)
  })
})

const selected = computed(() => tickets.value.find((ticket) => ticket.id === selectedId.value) ?? null)

const blockedReasonEntries = computed(() => entriesOf(quality.value?.handoff.blockedByReason))
const auditActionEntries = computed(() => entriesOf(quality.value?.audit.actions))
const handoffContextLossCount = computed(() =>
  (quality.value?.friction.handoffContextLosses ?? []).reduce((sum, row) => sum + (Number(row.count) || 0), 0),
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
  await acceptSession(result.body as Session)
}

async function doDemoLogin(role: 'agent' | 'admin') {
  loginError.value = ''
  demoLoading.value = true
  const result = await demoLogin(tenant.value.trim(), role)
  demoLoading.value = false
  if (result.status !== 200) {
    loginError.value = result.status === 404
      ? '当前部署不提供一键体验登录，请用坐席账号进入'
      : (messageOf(result.body) ?? `一键登录失败（HTTP ${result.status}）`)
    return
  }
  await acceptSession(result.body as Session)
}

async function acceptSession(logged: Session) {
  session.value = logged
  password.value = ''
  showLogin.value = false
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify(logged))
  await Promise.all([refresh(), refreshRefunds()])
}

function signOut() {
  session.value = null
  clearStoredSession()
  tickets.value = []
  selectedId.value = ''
  loadError.value = ''
  actionNote.value = ''
  actionError.value = ''
  resolveTarget.value = null
  resolveNote.value = ''
  resolveError.value = ''
  refunds.value = []
  refundError.value = ''
  refundNote.value = ''
  refundTarget.value = null
}

async function refresh() {
  loadError.value = ''
  if (!session.value) {
    tickets.value = []
    loading.value = false
    return
  }
  loading.value = true
  const result = await listTickets(queue.value, session.value)
  loading.value = false
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
  const keep = selectedId.value
  if (!keep || !tickets.value.some((ticket) => ticket.id === keep)) {
    selectedId.value = filtered.value[0]?.id ?? ''
  }
}

async function run(ticket: Ticket, kind: 'claim' | 'release') {
  if (!session.value || inFlight.value) return
  inFlight.value = `${ticket.id}:${kind}`
  actionNote.value = ''
  actionError.value = ''
  const result = kind === 'claim'
    ? await claimTicket(ticket.id, session.value)
    : await releaseTicket(ticket.id, session.value)
  const message = describe(result, { claim: '已领取', release: '已释放' }[kind])
  if (result.status >= 200 && result.status < 300) {
    actionNote.value = message
  } else {
    actionError.value = message
  }
  inFlight.value = ''
  await refresh()
}

function openResolve(ticket: Ticket) {
  if (!session.value || inFlight.value) return
  resolveTarget.value = ticket
  resolveNote.value = ''
  resolveError.value = ''
}

function closeResolve() {
  if (inFlight.value) return
  resolveTarget.value = null
  resolveNote.value = ''
  resolveError.value = ''
}

async function confirmResolve() {
  const ticket = resolveTarget.value
  if (!ticket || !session.value || inFlight.value) return
  const note = resolveNote.value.trim()
  if (!note) {
    resolveError.value = '请填写处理结论——它会随结果回流送到买家'
    return
  }
  inFlight.value = `${ticket.id}:resolve`
  resolveError.value = ''
  const result = await resolveTicket(ticket.id, session.value, note)
  inFlight.value = ''
  if (result.status >= 200 && result.status < 300) {
    resolveTarget.value = null
    resolveNote.value = ''
    actionNote.value = '已处理完成，结论会显示在买家工单详情'
    await refresh()
    return
  }
  resolveError.value = describe(result, '')
}

async function refreshQuality() {
  qualityError.value = ''
  if (!session.value) {
    quality.value = null
    return
  }
  qualityLoading.value = true
  const result = await qualityReport(session.value)
  qualityLoading.value = false
  if (result.status !== 200) {
    qualityError.value = describe(result, '')
    quality.value = null
    return
  }
  quality.value = result.body as QualityReport
}

function selectView(next: WorkspaceView) {
  view.value = next
  if (next === 'refunds') void refreshRefunds()
  if (next === 'quality' && (!quality.value || qualityError.value)) void refreshQuality()
}

async function refreshRefunds() {
  refundError.value = ''
  if (!session.value) {
    refunds.value = []
    return
  }
  const result = await listPendingRefunds(session.value)
  if (result.status === 0) {
    refundError.value = messageOf(result.body) ?? '网关不可达'
    refunds.value = []
    return
  }
  if (result.status === 401 || result.status === 403) {
    refundError.value = result.status === 401 ? '登录已失效，请重新登录' : '这个账号没有坐席权限'
    refunds.value = []
    return
  }
  if (!Array.isArray(result.body)) {
    refundError.value = messageOf(result.body) ?? `退款队列读取失败（HTTP ${result.status}）`
    refunds.value = []
    return
  }
  refunds.value = result.body
}

/** 资金动作的确认框：把金额、订单、责任人摆出来，不用浏览器原生 confirm。 */
function openRefund(refund: RefundItem, decision: 'APPROVE' | 'REJECT') {
  if (!session.value || refundInFlight.value) return
  refundTarget.value = refund
  refundDecision.value = decision
  refundDialogNote.value = ''
  refundDialogError.value = ''
}

function closeRefund() {
  if (refundInFlight.value) return
  refundTarget.value = null
  refundDialogNote.value = ''
  refundDialogError.value = ''
}

async function confirmRefund() {
  const refund = refundTarget.value
  if (!refund || !session.value || refundInFlight.value) return
  refundInFlight.value = refund.refundId
  refundDialogError.value = ''
  const result = await reviewRefund(refund.refundId, refundDecision.value, refundDialogNote.value.trim(), session.value)
  refundInFlight.value = 0
  if (result.status >= 200 && result.status < 300) {
    refundTarget.value = null
    refundDialogNote.value = ''
    refundNote.value = describe(result, refundDecision.value === 'APPROVE' ? '已放行' : '已驳回')
    await refreshRefunds()
    return
  }
  refundDialogError.value = describe(result, '')
}

onMounted(() => {
  if (session.value) {
    void refresh()
    void refreshRefunds()
  }
  timer = window.setInterval(() => {
    now.value = Date.now()
  }, 10_000)
})

onUnmounted(() => {
  window.clearInterval(timer)
})

function isOverdue(ticket: Ticket): boolean {
  if (ticket.escalatedAt) return true
  const left = minutesLeft(ticket.slaDeadline, now.value)
  return left !== null && left < 0
}

function slaText(ticket: Ticket): string {
  if (ticket.escalatedAt) return '已超时'
  const left = minutesLeft(ticket.slaDeadline, now.value)
  if (left === null) return '无 SLA'
  return left >= 0 ? `剩 ${left} 分钟` : `超时 ${-left} 分钟`
}

function formatTime(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function shortId(id: string): string {
  return id.length > 18 ? `…${id.slice(-12)}` : id
}

function ratioText(value: number | undefined | null): string {
  return `${((value ?? 0) * 100).toFixed(1)}%`
}

function secondsText(value: number | undefined | null): string {
  const millis = (value ?? 0) * 1000
  return millis >= 1 ? `${millis.toFixed(0)}ms` : `${millis.toFixed(2)}ms`
}

function entriesOf(map: Record<string, number> | undefined | null): Array<[string, number]> {
  return Object.entries(map ?? {}).sort((a, b) => b[1] - a[1])
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
  <main class="shell">
    <header class="topbar">
      <div class="brand">
        <span class="logo">SP</span>
        <div>
          <strong>ShopPilot</strong>
          <small>坐席工作台</small>
        </div>
      </div>
      <div v-if="session" class="top-right">
        <span class="identity">{{ who }}</span>
        <button class="ghost" type="button" @click="refresh">刷新队列</button>
        <button class="link" type="button" @click="signOut">退出</button>
      </div>
    </header>

    <section v-if="!session" class="login-wrap">
      <div class="login-card">
        <p class="eyebrow">坐席工作台</p>
        <h1>先登录，再开始接单</h1>
        <p class="lede">队列、会话原文、结单与退款审核都在这里；所有动作记审计，署名取自登录账号。</p>
        <div class="login-actions">
          <button class="primary big" type="button" :disabled="demoLoading" @click="doDemoLogin('agent')">
            {{ demoLoading ? '正在进入…' : '一键体验坐席' }}
          </button>
          <button class="ghost" type="button" :disabled="demoLoading" @click="doDemoLogin('admin')">管理员体验</button>
        </div>
        <button class="link" type="button" @click="showLogin = !showLogin">
          {{ showLogin ? '收起账号登录' : '用账号口令登录' }}
        </button>
        <form v-if="showLogin" class="login-form" @submit.prevent="doLogin">
          <label>店铺编号<input v-model="tenant" type="text" placeholder="例如 T001" /></label>
          <label>坐席账号<input v-model="username" type="text" placeholder="例如 agent" /></label>
          <label>口令<input v-model="password" type="password" placeholder="演示口令见 .env" /></label>
          <button class="primary" type="submit">登录</button>
        </form>
        <p v-if="loginError" class="err">{{ loginError }}</p>
        <p class="fine">一键体验只在回环演示栈可用；正式环境请用账号口令登录。</p>
      </div>
    </section>

    <template v-else>
      <div class="console">
        <aside class="side-nav">
          <p class="nav-title">工作区</p>
          <button class="nav-item" :class="{ active: view === 'queue' }" type="button" @click="view = 'queue'">
            <span>工单队列</span><em>{{ tickets.length }}</em>
          </button>
          <button class="nav-item" :class="{ active: view === 'refunds' }" type="button" @click="selectView('refunds')">
            <span>退款审核</span><em>{{ refunds.length }}</em>
          </button>
          <button class="nav-item" :class="{ active: view === 'quality' }" type="button" @click="selectView('quality')">
            <span>编排质量</span><em aria-hidden="true">●</em>
          </button>
          <div class="nav-foot">
            <strong>{{ session.tenantId }}</strong>
            <span>SLA 每 10 秒刷新</span>
          </div>
        </aside>

        <div class="work-area">
      <section v-if="view === 'queue'" class="view-panel">
      <section class="summary">
        <button class="kpi" :class="{ active: statusFilter === 'ALL' }" type="button" @click="statusFilter = 'ALL'">
          <span>当前队列</span><strong>{{ tickets.length }}</strong>
        </button>
        <button class="kpi" :class="{ active: statusFilter === 'OPEN' }" type="button" @click="statusFilter = 'OPEN'">
          <span>待处理</span><strong>{{ counts.open }}</strong>
        </button>
        <button class="kpi" :class="{ active: statusFilter === 'ASSIGNED' }" type="button" @click="statusFilter = 'ASSIGNED'">
          <span>处理中</span><strong>{{ counts.assigned }}</strong>
        </button>
        <button class="kpi danger" :class="{ active: statusFilter === 'OVERDUE' }" type="button" @click="statusFilter = 'OVERDUE'">
          <span>已超时</span><strong>{{ counts.overdue }}</strong>
        </button>
        <button class="kpi refund" type="button" @click="selectView('refunds')">
          <span>待审退款</span><strong>{{ refunds.length }}</strong>
        </button>
      </section>

      <section class="toolbar">
        <label>队列
          <select v-model="queue" @change="refresh">
            <option v-for="q in QUEUES" :key="q" :value="q">{{ QUEUE_LABEL[q] ?? q }}</option>
          </select>
        </label>
        <span class="toolbar-hint">按优先级排序；SLA 每 10 秒刷新</span>
        <button class="ghost" type="button" @click="refresh">刷新</button>
      </section>

      <p v-if="loadError" class="err">{{ loadError }}</p>
      <p v-if="actionError" class="err">{{ actionError }}</p>
      <p v-if="actionNote" class="note">{{ actionNote }}</p>

      <div class="workspace">
        <section class="queue-pane">
          <div class="pane-head">
            <h2>工单队列</h2>
            <span class="count">{{ filtered.length }}</span>
          </div>
          <p v-if="loading" class="empty">正在读取队列…</p>
          <p v-else-if="!loadError && !filtered.length" class="empty">这一档队列里没有待处理工单</p>
          <div v-else class="ticket-list" role="list">
            <article
              v-for="t in filtered"
              :key="t.id"
              class="ticket-card"
              :class="{ active: selectedId === t.id, overdue: isOverdue(t), urgent: t.priority === 'high' }"
              role="listitem"
              tabindex="0"
              @click="selectedId = t.id"
              @keyup.enter="selectedId = t.id"
            >
              <div class="ticket-top">
                <span class="priority">{{ PRIORITY_LABEL[t.priority ?? 'normal'] ?? t.priority }}</span>
                <span class="status-pill">{{ STATUS_LABEL[t.status] ?? t.status }}</span>
              </div>
              <p class="ticket-query">{{ t.userQuery }}</p>
              <div class="ticket-meta">
                <span>{{ SOURCE_LABEL[t.source ?? ''] ?? t.source ?? '—' }}</span>
                <span>{{ QUEUE_LABEL[t.queue ?? ''] ?? t.queue ?? '—' }}</span>
                <span :class="{ danger: isOverdue(t) }">{{ slaText(t) }}</span>
              </div>
              <div class="ticket-foot">
                <span>{{ t.assignee ?? '待分配' }}</span>
                <span class="mono">{{ shortId(t.id) }}</span>
              </div>
            </article>
          </div>
        </section>

        <aside v-if="selected" class="detail-pane">
          <header class="detail-head">
            <div>
              <p class="eyebrow">工单详情</p>
              <h2 class="mono">{{ selected.id }}</h2>
            </div>
            <span class="status-pill">{{ STATUS_LABEL[selected.status] ?? selected.status }}</span>
          </header>

          <p class="detail-query">{{ selected.userQuery }}</p>

          <div class="detail-meta">
            <div><span>来源</span><strong>{{ SOURCE_LABEL[selected.source ?? ''] ?? selected.source ?? '—' }}</strong></div>
            <div><span>队列</span><strong>{{ QUEUE_LABEL[selected.queue ?? ''] ?? selected.queue ?? '—' }}</strong></div>
            <div><span>优先级</span><strong>{{ PRIORITY_LABEL[selected.priority ?? 'normal'] ?? selected.priority }}</strong></div>
            <div><span>SLA</span><strong :class="{ danger: isOverdue(selected) }">{{ slaText(selected) }}</strong></div>
          </div>

          <div class="detail-actions">
            <button class="primary" type="button" :disabled="!!inFlight || !!selected.assignee || selected.status === 'RESOLVED'" @click="run(selected, 'claim')">
              {{ inFlight === `${selected.id}:claim` ? '领取中…' : '领取' }}
            </button>
            <button class="ghost" type="button" :disabled="!!inFlight || !selected.assignee || selected.status === 'RESOLVED'" @click="run(selected, 'release')">
              {{ inFlight === `${selected.id}:release` ? '释放中…' : '释放' }}
            </button>
            <button class="primary" type="button" :disabled="!!inFlight || selected.status === 'RESOLVED'" @click="openResolve(selected)">
              处理完成
            </button>
          </div>

          <section class="block">
            <h3>处理进度</h3>
            <ol class="timeline">
              <li class="done"><span>已提交</span><time>{{ formatTime(selected.createdAt) }}</time></li>
              <li v-if="selected.assignee" class="done"><span>坐席 {{ selected.assignee }} 已接入</span></li>
              <li v-if="selected.escalatedAt" class="warn"><span>已超时升级</span><time>{{ formatTime(selected.escalatedAt) }}</time></li>
              <li :class="{ done: selected.status === 'RESOLVED' }">
                <span>{{ STATUS_LABEL[selected.status] ?? selected.status }}</span>
                <time v-if="selected.status !== 'RESOLVED'">{{ slaText(selected) }}</time>
              </li>
            </ol>
          </section>

          <section class="block">
            <h3>转人工上下文</h3>
            <pre v-if="selected.transcript" class="transcript">{{ selected.transcript }}</pre>
            <p v-else class="empty">这张单没有记录失败/升级上下文</p>
          </section>

          <section v-if="selected.status === 'RESOLVED'" class="resolution">
            <h3>处理结论</h3>
            <p>{{ selected.resolutionNote || '本次没有填写结单说明' }}</p>
          </section>
        </aside>
        <aside v-else class="detail-pane empty-pane">选择左侧一张工单查看详情</aside>
      </div>
      </section>

      <section v-else-if="view === 'refunds'" class="view-panel">
        <div class="pane-head">
          <h2>退款审核</h2>
          <span class="count">{{ refunds.length }}</span>
        </div>
        <p class="hint">资金动作的责任人必须是已验签账号；放行/驳回都会记进审计。</p>
        <p v-if="refundError" class="err">{{ refundError }}</p>
        <p v-if="refundNote" class="note">{{ refundNote }}</p>
        <p v-if="!refunds.length && !refundError" class="empty">当前没有待审核的退款申请</p>
        <div v-else class="refund-list">
          <article v-for="r in refunds" :key="r.refundId" class="refund-card">
            <div class="refund-top">
              <strong>退款 #{{ r.refundId }}</strong>
              <span class="amount">{{ (r.amountFen / 100).toFixed(2) }} 元</span>
            </div>
            <p>订单 {{ r.orderNo }} · {{ r.reason }}</p>
            <div class="refund-actions">
              <button class="primary" type="button" :disabled="!!refundInFlight" @click="openRefund(r, 'APPROVE')">放行</button>
              <button class="ghost" type="button" :disabled="!!refundInFlight" @click="openRefund(r, 'REJECT')">驳回</button>
            </div>
          </article>
        </div>
      </section>

      <section v-else class="view-panel quality-panel">
        <div class="pane-head">
          <h2>编排质量</h2>
          <button class="ghost" type="button" :disabled="qualityLoading" @click="refreshQuality">
            {{ qualityLoading ? '读取中…' : '刷新报表' }}
          </button>
        </div>
        <p class="hint">只读旁路报表：Handoff、工具风险与摩擦模式来自本进程累计指标；坐席动作来自审计流最近 15 分钟。这里不做任何干预动作。</p>

        <p v-if="qualityError" class="err">{{ qualityError }}</p>
        <p v-if="qualityLoading" class="empty">正在读取编排质量报表…</p>

        <template v-else-if="quality">
          <div class="quality-kpis">
            <article class="quality-kpi">
              <span>请求 / 降级</span>
              <strong>{{ quality.requests.total }} / {{ quality.requests.fallbacks }}</strong>
              <small>降级率 {{ ratioText(quality.requests.fallbackRatio) }}</small>
            </article>
            <article class="quality-kpi">
              <span>Handoff 允许 / 拒绝</span>
              <strong>{{ quality.handoff.allowed }} / {{ quality.handoff.blocked }}</strong>
              <small>拒绝率 {{ ratioText(quality.handoff.blockedRatio) }}</small>
            </article>
            <article class="quality-kpi">
              <span>决策耗时</span>
              <strong>{{ secondsText(quality.handoff.decisionSeconds.meanSeconds) }}</strong>
              <small>最大 {{ secondsText(quality.handoff.decisionSeconds.maxSeconds) }}</small>
            </article>
            <article class="quality-kpi danger-kpi">
              <span>上下文丢失</span>
              <strong>{{ handoffContextLossCount }}</strong>
              <small>Handoff 后重复追问同一槽位</small>
            </article>
          </div>

          <div class="quality-grid">
            <section class="quality-block">
              <h3>Blocked 原因分布</h3>
              <ul v-if="blockedReasonEntries.length" class="distribution">
                <li v-for="[reason, count] in blockedReasonEntries" :key="reason">
                  <span class="mono">{{ reason }}</span><strong>{{ count }}</strong>
                </li>
              </ul>
              <p v-else class="empty">还没有被拒绝的 Handoff</p>
            </section>

            <section class="quality-block">
              <h3>摩擦模式</h3>
              <ul class="distribution">
                <li><span>工具成功后仍降级</span><strong>{{ quality.friction.toolSuccessFallbacks }}</strong></li>
                <li><span>重复追问槽位</span><strong>{{ quality.friction.repeatedSlotAsks }}</strong></li>
                <li><span>Handoff 上下文丢失</span><strong>{{ handoffContextLossCount }}</strong></li>
              </ul>
              <p v-if="quality.friction.repeatedSlotAsksByPhase.length" class="fine">
                已按 same_role / cross_handoff 分相统计；只有 cross_handoff + sameSlot 会进入告警。
              </p>
            </section>

            <section class="quality-block">
              <h3>工具风险面</h3>
              <table v-if="quality.toolRisk.rows.length">
                <thead><tr><th>工具</th><th>风险</th><th>HITL</th><th>角色</th><th>次数</th></tr></thead>
                <tbody>
                  <tr v-for="(row, index) in quality.toolRisk.rows" :key="index">
                    <td class="mono">{{ String(row.tool ?? '—') }}</td>
                    <td>{{ String(row.risk ?? '—') }}</td>
                    <td>{{ String(row.hitl ?? '—') }}</td>
                    <td>{{ String(row.role ?? '—') }}</td>
                    <td>{{ row.count }}</td>
                  </tr>
                </tbody>
              </table>
              <p v-else class="empty">还没有工具尝试</p>
            </section>

            <section class="quality-block">
              <h3>最近坐席动作</h3>
              <p v-if="!quality.audit.available" class="empty">审计流暂时读不到；指标部分仍可读。</p>
              <ul v-else-if="auditActionEntries.length" class="distribution">
                <li v-for="[action, count] in auditActionEntries" :key="action">
                  <span class="mono">{{ action }}</span><strong>{{ count }}</strong>
                </li>
              </ul>
              <p v-else class="empty">最近 {{ quality.audit.windowMinutes ?? 15 }} 分钟没有坐席留痕动作</p>
            </section>
          </div>
          <p class="fine">数据时间：{{ formatTime(quality.generatedAt) }} · 口径：{{ quality.scope }}</p>
        </template>
        <p v-else class="empty">点击“刷新报表”读取编排质量数据。</p>
      </section>
        </div>
      </div>
    </template>

    <div v-if="resolveTarget" class="scrim" @click.self="closeResolve">
      <section class="dialog" role="dialog" aria-modal="true" aria-labelledby="resolve-title">
        <h2 id="resolve-title">处理完成</h2>
        <p class="dialog-sub">工单 <span class="mono">{{ resolveTarget.id }}</span> · {{ resolveTarget.userQuery }}</p>
        <label class="field">
          处理结论（必填，会显示在买家工单详情并随结果回流）
          <textarea v-model="resolveNote" rows="4" placeholder="例如：已核实商品破损，按实付金额退款，3 个工作日内到账" />
        </label>
        <p v-if="resolveError" class="err">{{ resolveError }}</p>
        <p class="fine">结单后不能再流转；操作会记进审计，责任人是当前登录账号。</p>
        <div class="dialog-actions">
          <button class="ghost" type="button" :disabled="!!inFlight" @click="closeResolve">取消</button>
          <button class="primary" type="button" :disabled="!!inFlight" @click="confirmResolve">
            {{ inFlight ? '正在提交…' : '确认结单' }}
          </button>
        </div>
      </section>
    </div>

    <div v-if="refundTarget" class="scrim" @click.self="closeRefund">
      <section class="dialog" role="dialog" aria-modal="true" aria-labelledby="refund-title">
        <h2 id="refund-title">{{ refundDecision === 'APPROVE' ? '放行退款' : '驳回退款' }}</h2>
        <p class="dialog-sub">
          退款 #{{ refundTarget.refundId }} · 订单 {{ refundTarget.orderNo }} ·
          <strong>{{ (refundTarget.amountFen / 100).toFixed(2) }} 元</strong>
        </p>
        <p class="dialog-warn">这是不可逆的资金动作，责任人是当前登录账号；操作会记进审计。</p>
        <label class="field">
          备注（可选）
          <textarea v-model="refundDialogNote" rows="3" placeholder="例如：已核对订单与凭证" />
        </label>
        <p v-if="refundDialogError" class="err">{{ refundDialogError }}</p>
        <div class="dialog-actions">
          <button class="ghost" type="button" :disabled="!!refundInFlight" @click="closeRefund">取消</button>
          <button class="primary" type="button" :disabled="!!refundInFlight" @click="confirmRefund">
            {{ refundInFlight ? '正在提交…' : (refundDecision === 'APPROVE' ? '确认放行' : '确认驳回') }}
          </button>
        </div>
      </section>
    </div>
  </main>
</template>

<style scoped>
.shell {
  max-width: 1280px; margin: 0 auto; padding: 0 20px 40px;
  min-height: 100vh; display: flex; flex-direction: column;
}
.topbar {
  position: sticky; top: 0; z-index: 20;
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  padding: 14px 0; background: rgba(242, 243, 245, .94);
  backdrop-filter: blur(10px);
}
.brand { display: flex; align-items: center; gap: 10px; }
.logo { width: 32px; height: 32px; border-radius: 10px; background: var(--teal); color: #fff;
  display: grid; place-items: center; font-size: 12px; font-weight: 700; }
.brand strong { display: block; font-size: 15px; line-height: 1.2; }
.brand small { color: var(--ink-3); font-size: 12px; }
.top-right { display: flex; align-items: center; gap: 12px; }
.identity { color: var(--ink-2); font-size: 13px; }
.ghost { background: var(--panel); border: 1px solid var(--line); border-radius: 8px; padding: 6px 12px; cursor: pointer; }
.ghost:hover:not(:disabled) { border-color: var(--ink-3); }
.link { border: none; background: none; color: var(--teal); padding: 0; cursor: pointer; font-size: 13px;
  text-decoration: underline; text-underline-offset: 2px; }
.login-wrap { flex: 1; display: grid; place-items: center; padding: 40px 0; }
.login-card { width: min(620px, 100%); box-sizing: border-box; background: var(--panel);
  border: 1px solid var(--line); border-radius: 20px; padding: 32px;
  box-shadow: 0 12px 40px rgba(20, 22, 26, .08); text-align: center; }
.eyebrow { margin: 0 0 8px; color: var(--teal); font-size: 12px; font-weight: 600; letter-spacing: .08em; }
.login-card h1 { margin: 0 0 10px; font-size: 25px; }
.lede { color: var(--ink-2); margin: 0 auto 22px; max-width: 500px; }
.login-actions { display: flex; justify-content: center; gap: 12px; flex-wrap: wrap; }
.big { padding: 10px 20px; font-size: 15px; }
.login-form { display: grid; gap: 10px; max-width: 340px; margin: 18px auto 0; text-align: left; }
.login-form label { display: grid; gap: 4px; font-size: 13px; color: var(--ink-2); }
.login-form input { width: 100%; box-sizing: border-box; }
.fine { color: var(--ink-3); font-size: 12px; margin: 12px 0 0; }
.console {
  flex: 1;
  display: grid;
  grid-template-columns: 212px minmax(0, 1fr);
  gap: 18px;
  align-items: start;
  padding-top: 18px;
}
.side-nav {
  position: sticky;
  top: 76px;
  display: grid;
  gap: 6px;
  padding: 14px 12px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 16px;
  box-shadow: 0 8px 28px rgba(20, 22, 26, .05);
}
.nav-title {
  margin: 0 4px 8px;
  color: var(--ink-3);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: .08em;
}
.nav-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  padding: 9px 10px;
  border: 1px solid transparent;
  border-radius: 10px;
  background: none;
  text-align: left;
  color: var(--ink-2);
}
.nav-item:hover:not(:disabled) { background: var(--bg); border-color: var(--line); }
.nav-item.active {
  color: var(--ink);
  background: #edf7f5;
  border-color: #b7ddd8;
  font-weight: 600;
}
.nav-item em { font-style: normal; color: var(--ink-3); font-size: 12px; }
.nav-item.active em { color: var(--teal); }
.nav-foot {
  margin-top: 14px;
  padding: 10px;
  border-top: 1px solid var(--line-2);
  display: grid;
  gap: 2px;
}
.nav-foot strong { font-size: 13px; }
.nav-foot span { color: var(--ink-3); font-size: 12px; }
.work-area { min-width: 0; display: grid; gap: 14px; }
.view-panel { display: grid; gap: 14px; animation: rise .18s ease; }
@keyframes rise { from { opacity: .55; transform: translateY(4px); } to { opacity: 1; transform: none; } }

.quality-kpis { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.quality-kpi {
  background: var(--panel); border: 1px solid var(--line); border-radius: 14px;
  padding: 14px; display: grid; gap: 4px;
}
.quality-kpi span { color: var(--ink-3); font-size: 12px; }
.quality-kpi strong { font-size: 24px; line-height: 1.15; }
.quality-kpi small { color: var(--ink-3); font-size: 12px; }
.danger-kpi strong { color: var(--rose); }
.quality-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.quality-block { background: var(--panel); border: 1px solid var(--line); border-radius: 14px; padding: 14px; }
.quality-block h3 { margin: 0 0 10px; font-size: 13px; }
.distribution { list-style: none; margin: 0; padding: 0; display: grid; gap: 7px; }
.distribution li {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  padding: 7px 8px; background: var(--bg); border-radius: 8px; font-size: 12px;
}
.distribution span { color: var(--ink-2); overflow-wrap: anywhere; }
.distribution strong { color: var(--ink); }

.summary { display: grid; grid-template-columns: repeat(5, 1fr); gap: 10px; margin: 0; }
.kpi { text-align: left; background: var(--panel); border: 1px solid var(--line); border-radius: 12px;
  padding: 10px 12px; cursor: pointer; }
.kpi span { display: block; color: var(--ink-3); font-size: 12px; }
.kpi strong { display: block; font-size: 22px; line-height: 1.2; margin-top: 2px; }
.kpi.active { border-color: var(--teal); box-shadow: 0 0 0 1px var(--teal) inset; }
.kpi.danger strong { color: var(--rose); }
.kpi.refund strong { color: var(--amber); }
.toolbar { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; margin: 0 0 14px; }
.toolbar label { font-size: 13px; color: var(--ink-2); display: flex; align-items: center; gap: 8px; }
.toolbar select { padding: 5px 8px; }
.toolbar-hint { color: var(--ink-3); font-size: 12px; }
.workspace { display: grid; grid-template-columns: minmax(0, 1fr) minmax(360px, 440px); gap: 16px; align-items: start; }
.queue-pane, .detail-pane, .refund-section { background: var(--panel); border: 1px solid var(--line); border-radius: 16px; padding: 16px; }
.detail-pane { position: sticky; top: 74px; max-height: calc(100vh - 96px); overflow: auto; }
.pane-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 10px; }
.pane-head h2 { margin: 0; font-size: 15px; }
.count { color: var(--ink-3); font-size: 12px; border: 1px solid var(--line); border-radius: 999px; padding: 1px 8px; }
.ticket-list { display: grid; gap: 8px; }
.ticket-card { border: 1px solid var(--line); border-radius: 12px; padding: 11px 12px; cursor: pointer;
  transition: border-color .15s ease, box-shadow .15s ease; }
.ticket-card:hover { border-color: #b7ddd8; }
.ticket-card.active { border-color: var(--teal); box-shadow: 0 0 0 1px var(--teal) inset; background: #fbfefd; }
.ticket-card.overdue { border-left: 3px solid var(--rose); }
.ticket-top { display: flex; justify-content: space-between; align-items: center; gap: 10px; }
.priority { font-size: 12px; color: var(--ink-3); }
.ticket-card.urgent .priority { color: var(--rose); font-weight: 600; }
.status-pill { font-size: 12px; color: var(--teal); background: #eef8f6; border: 1px solid #b7ddd8; border-radius: 999px; padding: 1px 8px; }
.ticket-query { margin: 6px 0 8px; font-size: 14px; overflow-wrap: anywhere; }
.ticket-meta { display: flex; gap: 12px; flex-wrap: wrap; color: var(--ink-3); font-size: 12px; }
.ticket-foot { display: flex; justify-content: space-between; gap: 10px; margin-top: 8px; color: var(--ink-3); font-size: 12px; }
.danger { color: var(--rose); }
.mono { font-family: var(--mono); overflow-wrap: anywhere; }
.empty { color: var(--ink-3); font-size: 13px; margin: 6px 0; }
.detail-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.detail-head h2 { margin: 2px 0 0; font-size: 15px; }
.detail-query { margin: 12px 0; background: var(--bg); border-radius: 10px; padding: 10px 12px; overflow-wrap: anywhere; }
.detail-meta { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-bottom: 14px; }
.detail-meta div { display: grid; gap: 2px; }
.detail-meta span { color: var(--ink-3); font-size: 12px; }
.detail-meta strong { font-size: 13px; }
.detail-actions { display: flex; gap: 8px; flex-wrap: wrap; margin: 0 0 16px; }
.block { border-top: 1px solid var(--line-2); padding-top: 14px; margin-top: 14px; }
.block h3 { font-size: 13px; margin: 0 0 8px; }
.timeline { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
.timeline li { position: relative; padding-left: 16px; color: var(--ink-3); font-size: 13px; }
.timeline li::before { content: ''; position: absolute; left: 0; top: 6px; width: 6px; height: 6px; border-radius: 50%; background: var(--line); }
.timeline li.done::before { background: var(--teal); }
.timeline li.warn { color: #9a3412; }
.timeline li.warn::before { background: var(--amber); }
.timeline time { margin-left: 6px; }
.transcript { max-height: 260px; overflow: auto; margin: 0; padding: 10px; background: var(--bg);
  border-radius: 10px; white-space: pre-wrap; overflow-wrap: anywhere; font: 12px/1.55 var(--mono); }
.resolution { border-top: 1px solid var(--line-2); margin-top: 14px; padding-top: 14px; }
.resolution h3 { font-size: 13px; margin: 0 0 6px; color: var(--teal); }
.resolution p { margin: 0; font-size: 13px; line-height: 1.6; overflow-wrap: anywhere; }
.empty-pane { display: grid; place-items: center; color: var(--ink-3); min-height: 220px; }
.refund-section { margin-top: 16px; }
.hint { color: var(--ink-3); font-size: 12px; margin: 0 0 10px; }
.refund-list { display: grid; gap: 10px; }
.refund-card { border: 1px solid var(--line); border-radius: 12px; padding: 12px; }
.refund-top { display: flex; justify-content: space-between; gap: 12px; }
.amount { color: var(--amber); font-weight: 600; }
.refund-card p { color: var(--ink-2); margin: 6px 0 10px; }
.refund-actions { display: flex; gap: 8px; }
.scrim { position: fixed; inset: 0; background: rgba(20, 22, 26, .38); display: flex; align-items: center;
  justify-content: center; padding: 16px; z-index: 30; }
.dialog { background: var(--panel); border-radius: 14px; padding: 22px; width: min(540px, 100%);
  box-shadow: 0 18px 48px rgba(20, 22, 26, .22); }
.dialog h2 { font-size: 17px; margin: 0 0 6px; }
.dialog-sub { color: var(--ink-2); margin: 0 0 14px; overflow-wrap: anywhere; }
.dialog-warn { color: #9a3412; background: #fff7ed; border: 1px solid #fed7aa; border-radius: 10px;
  padding: 8px 10px; font-size: 13px; }
.field { display: block; font-size: 13px; color: var(--ink-2); }
.field textarea { display: block; width: 100%; margin-top: 6px; padding: 8px; font: inherit;
  border: 1px solid var(--line); border-radius: 8px; resize: vertical; box-sizing: border-box; }
.dialog-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 14px; }
.err { color: var(--rose); font-size: 13px; }
.note { color: var(--green); font-size: 13px; }

@media (max-width: 960px) {
  .workspace { grid-template-columns: 1fr; }
  .detail-pane { position: static; max-height: none; }
  .summary { grid-template-columns: repeat(3, 1fr); }
  .console { grid-template-columns: 1fr; }
  .side-nav { position: static; grid-template-columns: repeat(3, 1fr); align-items: center; }
  .nav-title, .nav-foot { display: none; }
  .nav-item { justify-content: center; }
  .quality-kpis, .quality-grid { grid-template-columns: 1fr; }
}
@media (max-width: 640px) {
  .shell { padding: 0 12px 24px; }
  .topbar { padding: 10px 0; }
  .top-right .identity { display: none; }
  .login-card { padding: 22px 16px; border-radius: 16px; }
  .summary { grid-template-columns: repeat(2, 1fr); }
  .detail-meta { grid-template-columns: 1fr; }
  .side-nav { grid-template-columns: 1fr; }
  .quality-block { overflow-x: auto; }
}
</style>
