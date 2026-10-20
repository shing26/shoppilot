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
import {
  claimTicket,
  describe,
  listPendingRefunds,
  listTickets,
  login,
  messageOf,
  releaseTicket,
  resolveTicket,
  reviewRefund,
  type RefundItem,
  type Session,
} from './api'
import { QUEUE_LABEL, PRIORITY_LABEL, QUEUES, SOURCE_LABEL, STATUS_LABEL, minutesLeft, priorityRank, type Ticket } from './types'

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

// 详情展开（round36 / 票 151）：会话原文在 TicketView 里，默认收起——一张 8000 字的原文
// 摊在表格里会把队列读面挤没。
const expandedId = ref('')

// 结单意见（round36 / 票 151 / ADR 0059）：note 会同时进审计与出站正文，空串等于让买家
// 收到一句「已处理完成」。所以结单改成带文本框的对话框，空意见在提交前拦住。
const resolveTarget = ref<Ticket | null>(null)
const resolveNote = ref('')
const resolveError = ref('')

// ── 退款审核（B3 / ADR 0063）────────────────────────────────────────────────
const refunds = ref<RefundItem[]>([])
const refundError = ref('')
const refundNote = ref('')
const refundInFlight = ref<number>(0)

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
  await refreshRefunds()
}

function signOut() {
  session.value = null
  clearStoredSession()
  tickets.value = []
  loadError.value = ''
  actionNote.value = ''
  expandedId.value = ''
  resolveTarget.value = null
  resolveNote.value = ''
  resolveError.value = ''
  refunds.value = []
  refundError.value = ''
  refundNote.value = ''
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

async function run(ticket: Ticket, kind: 'claim' | 'release') {
  if (!session.value || inFlight.value) return // 防连点：一次只允许一个动作在飞
  inFlight.value = `${ticket.id}:${kind}`
  actionNote.value = ''
  const result =
    kind === 'claim'
      ? await claimTicket(ticket.id, session.value)
      : await releaseTicket(ticket.id, session.value)
  actionNote.value = describe(result, { claim: '已领取', release: '已释放' }[kind])
  inFlight.value = ''
  await refresh()
}

/** 打开结单对话框。真正的确认在 `confirmResolve`，不在浏览器原生 confirm 里。 */
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

/**
 * 结单：意见必填。
 *
 * <p>空串不是「没写」这么简单——`WorkItemService.resolve` 拿它当审计说明与出站正文，
 * ADR 0059 承诺的「结单结论送回原渠道」正落在这段文字上。
 */
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
    actionNote.value = '已处理完成，结论已进入结果回流'
    await refresh()
    return
  }
  resolveError.value = describe(result, '')
}

// ── 退款审核（B3 / ADR 0063）────────────────────────────────────────────────

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
    refundError.value = messageOf(result.body) ?? `队列读取失败（HTTP ${result.status}）`
    refunds.value = []
    return
  }
  refunds.value = result.body
}

async function runRefundReview(refund: RefundItem, decision: 'APPROVE' | 'REJECT') {
  if (!session.value || refundInFlight.value) return
  // 资金放行是不可逆的，且责任人必须是已验签账号（ADR 0063）。
  if (
    !window.confirm(
      `确认${decision === 'APPROVE' ? '放行' : '驳回'}退款 #${refund.refundId}？\n` +
        `订单 ${refund.orderNo}，金额 ${(refund.amountFen / 100).toFixed(2)} 元。\n` +
        `此操作会记进审计，责任人是当前登录账号。`,
    )
  ) {
    return
  }
  refundInFlight.value = refund.refundId
  refundNote.value = ''
  const result = await reviewRefund(refund.refundId, decision, '', session.value)
  refundNote.value = describe(result, decision === 'APPROVE' ? '已放行' : '已驳回')
  refundInFlight.value = 0
  await refreshRefunds()
}

onMounted(() => {
  void refresh()
  void refreshRefunds()
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
      <p class="sub">待处理工单与资金审核 · 署名取自登录账号，所有动作记进审计</p>
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
          <option v-for="q in QUEUES" :key="q" :value="q">{{ QUEUE_LABEL[q] ?? q }}</option>
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
        <template v-for="t in sorted" :key="t.id">
          <tr>
            <td class="mono">{{ t.id }}</td>
            <td>{{ SOURCE_LABEL[t.source ?? ''] ?? t.source ?? '—' }}</td>
            <td>{{ QUEUE_LABEL[t.queue ?? ''] ?? t.queue ?? '—' }}</td>
            <td :class="{ urgent: t.priority === 'high' }">{{ PRIORITY_LABEL[t.priority ?? 'normal'] ?? t.priority }}</td>
            <td class="query">{{ t.userQuery }}</td>
            <td :class="{ overdue: !!t.escalatedAt }">{{ slaText(t) }}</td>
            <td>{{ STATUS_LABEL[t.status] ?? t.status }}<span v-if="t.assignee"> / {{ t.assignee }}</span></td>
            <td class="ops">
              <button type="button" @click="expandedId = expandedId === t.id ? '' : t.id">
                {{ expandedId === t.id ? '收起' : '详情' }}
              </button>
              <button type="button" :disabled="!!inFlight" @click="run(t, 'claim')">领取</button>
              <button type="button" :disabled="!!inFlight || t.status === 'OPEN'" @click="run(t, 'release')">释放</button>
              <button type="button" :disabled="!!inFlight || t.status === 'RESOLVED'" @click="openResolve(t)">处理完成</button>
            </td>
          </tr>
          <!-- 会话原文（round36 / 票 151）：默认收起，展开后限高滚动——单张单可达 8000 字。 -->
          <tr v-if="expandedId === t.id" class="detail-row">
            <td colspan="8">
              <div class="detail">
                <div class="detail-cap">会话原文</div>
                <pre v-if="t.transcript">{{ t.transcript }}</pre>
                <p v-else class="empty">这张单没有可显示的会话原文</p>
              </div>
            </td>
          </tr>
        </template>
      </tbody>
    </table>
    <p v-else-if="!loadError" class="empty">这一档队列里没有待处理工单</p>

    <!-- 退款审核面板（B3 / ADR 0063） -->
    <section v-if="session" class="refund-panel">
      <h2>退款审核</h2>
      <p class="hint">资金动作的责任人必须是已验签账号（ADR 0063）。放行/驳回都会记进审计。</p>
      <p v-if="refundError" class="err">{{ refundError }}</p>
      <p v-if="refundNote" class="note">{{ refundNote }}</p>
      <table v-if="refunds.length">
        <thead>
          <tr><th>退款号</th><th>订单号</th><th>店铺</th><th>金额</th><th>理由</th><th>状态</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="r in refunds" :key="r.refundId">
            <td class="mono">{{ r.refundId }}</td>
            <td class="mono">{{ r.orderNo }}</td>
            <td>{{ r.tenantId }}</td>
            <td>{{ (r.amountFen / 100).toFixed(2) }} 元</td>
            <td class="query">{{ r.reason }}</td>
            <td>{{ r.status }}</td>
            <td class="ops">
              <button type="button" :disabled="!!refundInFlight" @click="runRefundReview(r, 'APPROVE')">放行</button>
              <button type="button" :disabled="!!refundInFlight" @click="runRefundReview(r, 'REJECT')">驳回</button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else-if="!refundError" class="empty">当前没有待审核的退款申请</p>
    </section>

    <!-- 结单对话框（round36 / 票 151）：原生 confirm 装不下意见文本框，而意见是结果回流的正文。 -->
    <div v-if="resolveTarget" class="scrim" @click.self="closeResolve">
      <section class="dialog" role="dialog" aria-modal="true" aria-labelledby="resolve-title">
        <h2 id="resolve-title">处理完成</h2>
        <p class="dialog-sub">
          工单 <span class="mono">{{ resolveTarget.id }}</span> ·
          {{ resolveTarget.userQuery }}
        </p>
        <label class="dialog-field">
          处理结论（必填，会随结果回流送到买家）
          <textarea v-model="resolveNote" rows="4" placeholder="例如：已核实商品破损，按实付金额退款，3 个工作日内到账" />
        </label>
        <p v-if="resolveError" class="err">{{ resolveError }}</p>
        <p class="hint">结单后不能再流转，操作会记进审计，责任人是当前登录账号。</p>
        <div class="dialog-actions">
          <button type="button" :disabled="!!inFlight" @click="closeResolve">取消</button>
          <button type="button" class="primary" :disabled="!!inFlight" @click="confirmResolve">
            {{ inFlight ? '正在提交…' : '确认结单' }}
          </button>
        </div>
      </section>
    </div>
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
.refund-panel { margin-top: 32px; }
.refund-panel h2 { font-size: 16px; margin: 0 0 4px; }
.refund-panel .hint { color: #666; margin: 0 0 12px; }

/* 会话原文：限高滚动，避免一张 8000 字的单把队列读面挤走 */
.detail-row td { background: #fafafa; }
.detail { max-height: 320px; overflow: auto; }
.detail-cap { font-size: 12px; color: #666; margin-bottom: 4px; }
.detail pre { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; font: 13px/1.55 ui-monospace, monospace; }

/* 结单对话框 */
.scrim { position: fixed; inset: 0; background: rgba(20, 22, 26, .38); display: flex; align-items: center;
  justify-content: center; padding: 16px; z-index: 30; }
.dialog { background: #fff; border-radius: 10px; padding: 20px; width: min(520px, 100%);
  box-shadow: 0 18px 48px rgba(20, 22, 26, .22); }
.dialog h2 { font-size: 17px; margin: 0 0 6px; }
.dialog-sub { color: #444; margin: 0 0 14px; overflow-wrap: anywhere; }
.dialog-field { display: block; font-size: 13px; color: #444; }
.dialog-field textarea { display: block; width: 100%; margin-top: 6px; padding: 8px; font: inherit;
  border: 1px solid #cfd3d9; border-radius: 6px; resize: vertical; box-sizing: border-box; }
.dialog-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 14px; }
.dialog-actions .primary { background: #14161a; color: #fff; border: 1px solid #14161a; border-radius: 6px;
  padding: 6px 14px; cursor: pointer; }
.dialog-actions button { padding: 6px 14px; cursor: pointer; }
</style>