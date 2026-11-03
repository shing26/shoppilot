<script setup lang="ts">
/**
 * 坐席工作台（UX-04）：队列 + 工单详情 + 动作，一次只处理一张单。
 *
 * <p>保留三条旧口径：
 * 1. 失败不冻结——读不到就写清「读不到 + 为什么」；
 * 2. 动作防连点——一次只允许一个动作在飞；
 * 3. 署名取自登录令牌，页面不能改。
 *
 * <p>round44 票 188 的拆分边界：**状态、副作用与视图切换的全部逻辑都在这个文件里**。
 * 子组件只画与发事件；SSE 流、`Notification` 权限、`AudioContext`、push 订阅、
 * service worker 消息都留在这里，因为它们各只有一份。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  channelPushPublicKey,
  claimTicket,
  demoLogin,
  describe,
  describeKnowledge,
  feedbackReviewQueue,
  fileRevision,
  listChannelConversations,
  listChannels,
  listKnowledgeRevisions,
  listPendingRefunds,
  listTickets,
  markConversationRead,
  publishRevision,
  rejectRevision,
  releaseConversation,
  replyConversation,
  reviewFeedback,
  streamChannelConversations,
  subscribeChannelPush,
  takeoverConversation,
  unsubscribeChannelPush,
  login,
  saveFeishu,
  testFeishu,
  messageOf,
  qualityReport,
  releaseTicket,
  resolveTicket,
  reviewRefund,
  type ChannelConnection,
  type ChannelConversation,
  type ChannelProbe,
  type FeedbackItem,
  type KnowledgeRevision,
  type QualityReport,
  type RefundItem,
  type RevisionDraft,
  type Session,
} from './api'
import { setUnauthorizedHandler } from 'frontend-shared'
import { priorityRank, type Ticket } from './types'
import { entriesOf, isOverdue } from './lib/format'
import { DOMAINS, POLICY_INTENTS, SCOPES, type ChannelForm, type StatusFilter, type WorkspaceView } from './lib/ui'
import TopBar from './components/TopBar.vue'
import LoginCard from './components/LoginCard.vue'
import SideNav from './components/SideNav.vue'
import QueueView from './components/QueueView.vue'
import ConversationsView from './components/ConversationsView.vue'
import RefundsView from './components/RefundsView.vue'
import ChannelsView from './components/ChannelsView.vue'
import QualityView from './components/QualityView.vue'
import KnowledgeView from './components/KnowledgeView.vue'
import ResolveDialog from './components/ResolveDialog.vue'
import RefundDialog from './components/RefundDialog.vue'

const STORAGE_KEY = 'shoppilot.session'

const tenant = ref('T001')
const username = ref('agent')
const password = ref('')
const showLogin = ref(false)
const demoLoading = ref(false)
const session = ref<Session | null>(readStoredSession())
const loginError = ref('')
const queue = ref<string>('ALL')
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
const view = ref<WorkspaceView>('queue')

const conversations = ref<ChannelConversation[]>([])
const conversationLoading = ref(false)
const conversationError = ref('')
const conversationNote = ref('')
const conversationInFlight = ref('')
const selectedConversationId = ref('')
const conversationReply = ref('')
const selectedConversation = computed(() =>
  conversations.value.find((c) => c.conversationId === selectedConversationId.value) ?? null,
)
const conversationUnread = computed(() =>
  conversations.value.reduce((sum, c) => sum + (c.unread ?? 0), 0),
)
let conversationStreamAbort: AbortController | null = null
let conversationRetry = 0

const channel = ref<ChannelConnection | null>(null)
const channelLoading = ref(false)
const channelError = ref('')
const channelNote = ref('')
const channelSaving = ref(false)
const channelTesting = ref(false)
const channelProbe = ref<ChannelProbe | null>(null)
const channelForm = ref<ChannelForm>({
  enabled: true,
  appId: '',
  appSecret: '',
  tenant: 'T001',
  customer: 'C001',
  identityFile: '',
})

const NOTIFY_KEY = 'shoppilot.channel.notify'
const notificationsEnabled = ref(
  localStorage.getItem(NOTIFY_KEY) === '1' &&
    typeof window !== 'undefined' &&
    'Notification' in window &&
    Notification.permission === 'granted',
)
const notificationError = ref('')
const SOUND_KEY = 'shoppilot.channel.sound'
const soundEnabled = ref(localStorage.getItem(SOUND_KEY) === '1')
let audioContext: AudioContext | null = null
const pushSubscribed = ref(false)
let serviceWorkerRegistration: ServiceWorkerRegistration | null = null
let pendingConversation = new URLSearchParams(window.location.search).get('conversation') ?? ''
const quality = ref<QualityReport | null>(null)
const qualityLoading = ref(false)
const qualityError = ref('')

// ── 知识飞轮（round44 票 190；round46 票 194 改为服务端真源）────────────────
// `feedback` 是待复核（PENDING）；`reviewedFeedback` 是已复核、还能附稿的（REVIEWED）。
// round46 之前后者只是会话内副本，刷新即失联；现在刷新时按 `status=REVIEWED` 从服务端取回，
// 并用已有修订稿把「已经附过稿的」滤掉，避免同一反馈重复出现在可附稿列表里。
const feedback = ref<FeedbackItem[]>([])
const feedbackLoading = ref(false)
const feedbackError = ref('')
const reviewedFeedback = ref<FeedbackItem[]>([])
/**
 * 服务端 REVIEWED 反馈的总数（round47 票 197）：五步条的「人工复核」用它，
 * 不用 `reviewedFeedback.length`——附稿会把后者从列表里移走，但复核事实没有消失。
 */
const reviewedTotal = ref(0)
const revisions = ref<KnowledgeRevision[]>([])
const revisionsLoading = ref(false)
const revisionsError = ref('')
const draftFeedbackId = ref('')
const draft = ref<RevisionDraft>(emptyDraft())
const kbNote = ref('')
const kbError = ref('')

let timer = 0

const who = computed(() =>
  session.value ? `${session.value.username}（${session.value.role}）@ ${session.value.tenantId}` : '',
)

const counts = computed(() => ({
  open: tickets.value.filter((t) => t.status === 'OPEN').length,
  assigned: tickets.value.filter((t) => t.status === 'ASSIGNED').length,
  overdue: tickets.value.filter((t) => isOverdue(t, now.value)).length,
}))

const filtered = computed(() => {
  const rows = tickets.value.filter((ticket) => {
    if (statusFilter.value === 'ALL') return true
    if (statusFilter.value === 'OPEN') return ticket.status === 'OPEN'
    if (statusFilter.value === 'ASSIGNED') return ticket.status === 'ASSIGNED'
    return isOverdue(ticket, now.value)
  })
  return rows.sort((a, b) => {
    const byPriority = priorityRank(a.priority) - priorityRank(b.priority)
    return byPriority !== 0 ? byPriority : b.createdAt.localeCompare(a.createdAt)
  })
})

/** 详情只从**当前筛选结果**里取（round47 票 197）：筛到空档时不能继续展示上一张单。 */
const selected = computed(() => filtered.value.find((ticket) => ticket.id === selectedId.value) ?? null)

/** 退款审批工单：退款队列靠 `payload.refundId` 与它们对上（票 189）。 */
const refundTickets = computed(() => tickets.value.filter((ticket) => ticket.source === 'REFUND_APPROVAL'))

const blockedReasonEntries = computed(() => entriesOf(quality.value?.handoff.blockedByReason))
const auditActionEntries = computed(() => entriesOf(quality.value?.audit.actions))
const handoffContextLossCount = computed(() =>
  (quality.value?.friction.handoffContextLosses ?? []).reduce((sum, row) => sum + (Number(row.count) || 0), 0),
)

/** 知识视图的待办：待复核 + 待发布。侧栏徽标据此显示，不用另跑一次请求。 */
const knowledgeTodo = computed(
  () => feedback.value.length + revisions.value.filter((revision) => revision.status === 'DRAFT').length,
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
  startConversationStream()
  await Promise.all([refresh(), refreshRefunds(), refreshConversations(), refreshKnowledge()])
}

/**
 * 会话失效（round47 票 195）：带令牌的请求收到 401 时停渠道流、清工作台、回登录卡。
 *
 * <p>工作台没有「本标签页对话」可保留，清空与主动退出同口径；原因写成登录卡上的
 * `loginError`，不让用户面对一张没有解释的登录表单。
 */
function onUnauthorized() {
  signOut()
  loginError.value = '登录已失效，请重新登录'
}

function signOut() {
  stopConversationStream()
  session.value = null
  loginError.value = ''
  clearStoredSession()
  conversations.value = []
  selectedConversationId.value = ''
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
  feedback.value = []
  feedbackError.value = ''
  reviewedFeedback.value = []
  reviewedTotal.value = 0
  revisions.value = []
  revisionsError.value = ''
  draftFeedbackId.value = ''
  draft.value = emptyDraft()
  kbNote.value = ''
  kbError.value = ''
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
  if (!keep || !filtered.value.some((ticket) => ticket.id === keep)) {
    selectedId.value = filtered.value[0]?.id ?? ''
  }
}

/** 换队列：先落值再拉数据，不依赖同一事件上两个处理器的先后顺序。 */
function changeQueue(next: string) {
  if (queue.value === next) return
  queue.value = next
  void refresh()
}

function setFilter(next: StatusFilter) {
  if (statusFilter.value === next) return
  statusFilter.value = next
  // 筛选切换后详情跟着走：当前单不在结果里就选第一条，结果为空则清空详情。
  const rows = filtered.value
  if (!rows.some((ticket) => ticket.id === selectedId.value)) {
    selectedId.value = rows[0]?.id ?? ''
  }
}

function selectTicket(id: string) {
  selectedId.value = id
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
  // 产品规则（round47 票 197）：先领取再结单。后端状态机不变，这里挡住入口。
  if (!ticket.assignee) {
    actionError.value = '先领取这张工单，再处理完成'
    return
  }
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

/**
 * 附稿表单的初始值。
 *
 * <p>作用范围默认 `SHOP`：把店里的条款写成平台通用，是比「少写一点」更贵的错。
 */
function emptyDraft(): RevisionDraft {
  return { title: '', body: '', intent: POLICY_INTENTS[0], domain: DOMAINS[0], scope: SCOPES[0] }
}

async function refreshKnowledge() {
  if (!session.value) {
    feedback.value = []
    reviewedFeedback.value = []
    reviewedTotal.value = 0
    revisions.value = []
    return
  }
  // 先拿修订稿：下面要用 `revisions.feedbackId` 把已附稿的反馈从可附稿列表里滤掉。
  await refreshKnowledgeRevisions()
  await refreshFeedbackQueue()
}

async function refreshFeedbackQueue() {
  feedbackError.value = ''
  if (!session.value) {
    feedback.value = []
    reviewedFeedback.value = []
    reviewedTotal.value = 0
    feedbackLoading.value = false
    return
  }
  feedbackLoading.value = true
  const [pending, reviewed] = await Promise.all([
    feedbackReviewQueue(session.value),
    feedbackReviewQueue(session.value, 'REVIEWED'),
  ])
  feedbackLoading.value = false
  if (pending.status !== 200 || !Array.isArray(pending.body)) {
    feedbackError.value = describeKnowledge(pending, '')
    feedback.value = []
    return
  }
  feedback.value = pending.body
  if (reviewed.status === 200 && Array.isArray(reviewed.body)) {
    // 五步条读的是「复核过多少条」，不是「还剩多少条可附稿」。
    reviewedTotal.value = reviewed.body.length
    // 有 DRAFT/PUBLISHED 修订稿的 = 已经附过稿，不再列；REJECTED 的留给用户重新起草。
    const drafted = new Set(
      revisions.value.filter((revision) => revision.status !== 'REJECTED').map((revision) => revision.feedbackId),
    )
    reviewedFeedback.value = reviewed.body.filter((item) => !drafted.has(item.id))
  }
  // REVIEWED 读失败时保留会话内已有的副本：刷新的主职责（待复核队列）已经成功，
  // 不该因为第二个读口不可用就把可附稿列表清空。
}

async function refreshKnowledgeRevisions() {
  revisionsError.value = ''
  if (!session.value) {
    revisions.value = []
    revisionsLoading.value = false
    return
  }
  revisionsLoading.value = true
  const result = await listKnowledgeRevisions(session.value)
  revisionsLoading.value = false
  if (result.status !== 200 || !Array.isArray(result.body)) {
    revisionsError.value = describeKnowledge(result, '')
    revisions.value = []
    return
  }
  revisions.value = result.body
}

/**
 * 复核一条反馈：PENDING → REVIEWED。
 *
 * <p>成功后立刻把表单展开到这条反馈上——复核的下一步就是附稿，不让人再到列表里找一遍。
 * 服务端队列里这一行随即消失，所以副本另存 `reviewedFeedback`。
 */
async function reviewFeedbackItem(feedbackId: string) {
  if (!session.value || inFlight.value) return
  inFlight.value = `kb:review:${feedbackId}`
  kbNote.value = ''
  kbError.value = ''
  const result = await reviewFeedback(feedbackId, session.value)
  inFlight.value = ''
  if (result.status >= 200 && result.status < 300) {
    const reviewed = (result.body as FeedbackItem | undefined)
    if (reviewed?.id && !reviewedFeedback.value.some((item) => item.id === reviewed.id)) {
      reviewedFeedback.value = [reviewed, ...reviewedFeedback.value]
    }
    kbNote.value = `已复核 ${feedbackId}。下一步：给它附一版修订稿（正文由你写）。`
    draftFeedbackId.value = feedbackId
    draft.value = emptyDraft()
    await refreshFeedbackQueue()
    return
  }
  kbError.value = describeKnowledge(result, '')
}

function pickFeedbackToDraft(feedbackId: string) {
  draftFeedbackId.value = feedbackId
  draft.value = emptyDraft()
  kbError.value = ''
  kbNote.value = ''
}

function cancelDraft() {
  if (inFlight.value) return
  draftFeedbackId.value = ''
  draft.value = emptyDraft()
}

async function submitDraft() {
  const feedbackId = draftFeedbackId.value
  if (!session.value || !feedbackId || inFlight.value) return
  inFlight.value = 'kb:file'
  kbNote.value = ''
  kbError.value = ''
  const result = await fileRevision(
    feedbackId,
    {
      title: draft.value.title.trim(),
      body: draft.value.body.trim(),
      intent: draft.value.intent,
      domain: draft.value.domain,
      scope: draft.value.scope,
    },
    session.value,
  )
  inFlight.value = ''
  if (result.status >= 200 && result.status < 300) {
    kbNote.value = '已附稿。它是草稿：发布前不进语料，发布后也要重跑 ingest 才生效。'
    reviewedFeedback.value = reviewedFeedback.value.filter((item) => item.id !== feedbackId)
    draftFeedbackId.value = ''
    draft.value = emptyDraft()
    await refreshKnowledgeRevisions()
    return
  }
  kbError.value = describeKnowledge(result, '')
}

async function publishKnowledgeRevision(revisionId: string) {
  if (!session.value || inFlight.value) return
  inFlight.value = `kb:publish:${revisionId}`
  kbNote.value = ''
  kbError.value = ''
  const result = await publishRevision(revisionId, session.value)
  inFlight.value = ''
  if (result.status >= 200 && result.status < 300) {
    kbNote.value = '已发布。发布不立即生效：需要重跑 ingest 才会进入检索语料。'
    await refreshKnowledgeRevisions()
    return
  }
  kbError.value = describeKnowledge(result, '')
}

async function rejectKnowledgeRevision(revisionId: string) {
  if (!session.value || inFlight.value) return
  inFlight.value = `kb:reject:${revisionId}`
  kbNote.value = ''
  kbError.value = ''
  const result = await rejectRevision(revisionId, session.value)
  inFlight.value = ''
  if (result.status >= 200 && result.status < 300) {
    kbNote.value = '已驳回。这一版不会进语料；反馈仍然停在「已复核」。'
    await refreshKnowledgeRevisions()
    return
  }
  kbError.value = describeKnowledge(result, '')
}

/**
 * 从工单队列跳到知识视图去复核某条反馈（round46 票 194）。
 *
 * <p>只切视图 + 留一句指路，不预设「正在复核」：复核仍是知识视图里的显式动作，
 * 工单详情只是把入口从「翻队列找反馈号」变成一次点击。
 */
function openFeedbackReview(feedbackId: string) {
  kbError.value = ''
  kbNote.value = `来自工单队列：在待复核列表里找到反馈 ${feedbackId}，复核后再附稿。`
  selectView('knowledge')
}

function selectView(next: WorkspaceView) {
  view.value = next
  if (next === 'refunds') void refreshRefunds()
  if (next === 'quality' && (!quality.value || qualityError.value)) void refreshQuality()
  if (next === 'channels') void refreshChannels()
  if (next === 'conversations') void refreshConversations()
  if (next === 'knowledge') void refreshKnowledge()
}

async function refreshConversations() {
  conversationError.value = ''
  if (!session.value) {
    conversations.value = []
    return
  }
  conversationLoading.value = true
  const result = await listChannelConversations(session.value)
  conversationLoading.value = false
  if (result.status !== 200) {
    conversationError.value = describe(result, '')
    conversations.value = []
    return
  }
  const list = (result.body as { conversations?: ChannelConversation[] }).conversations ?? []
  conversations.value = list
  const pendingMatches = pendingConversation && list.some((c) => c.conversationId === pendingConversation)
  if (pendingMatches) {
    selectedConversationId.value = pendingConversation
    pendingConversation = ''
    conversationReply.value = ''
  } else if (!list.some((c) => c.conversationId === selectedConversationId.value)) {
    selectedConversationId.value = list[0]?.conversationId ?? ''
    conversationReply.value = ''
  }
  const chosen = list.find((c) => c.conversationId === selectedConversationId.value)
  if (chosen?.unread) void readConversation(chosen.conversationId)
}

function selectConversation(id: string) {
  selectedConversationId.value = id
  conversationReply.value = ''
  conversationError.value = ''
  conversationNote.value = ''
  void readConversation(id)
}

function base64UrlToArrayBuffer(value: string): ArrayBuffer {
  const padding = '='.repeat((4 - (value.length % 4)) % 4)
  const base64 = (value + padding).replace(/-/g, '+').replace(/_/g, '/')
  const raw = window.atob(base64)
  return Uint8Array.from([...raw].map((c) => c.charCodeAt(0))).buffer as ArrayBuffer
}

async function registerServiceWorker(): Promise<ServiceWorkerRegistration | null> {
  if (!('serviceWorker' in navigator)) return null
  if (serviceWorkerRegistration) return serviceWorkerRegistration
  try {
    serviceWorkerRegistration = await navigator.serviceWorker.register('/workspace/sw.js')
    await navigator.serviceWorker.ready
    return serviceWorkerRegistration
  } catch {
    return null
  }
}

async function enablePush(): Promise<boolean> {
  if (!session.value || !('PushManager' in window)) return false
  const registration = await registerServiceWorker()
  if (!registration) return false
  try {
    let subscription = await registration.pushManager.getSubscription()
    if (!subscription) {
      const key = await channelPushPublicKey(session.value)
      const publicKey = key.status === 200 ? (key.body as { publicKey?: string }).publicKey : undefined
      if (!publicKey) return false
      subscription = await registration.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: base64UrlToArrayBuffer(publicKey),
      })
    }
    const json = subscription.toJSON()
    const p256dh = json.keys?.p256dh
    const auth = json.keys?.auth
    if (!p256dh || !auth) return false
    const result = await subscribeChannelPush(session.value, {
      endpoint: subscription.endpoint,
      p256dh,
      auth,
    })
    if (result.status < 200 || result.status >= 300) return false
    pushSubscribed.value = true
    return true
  } catch {
    return false
  }
}

async function restorePush() {
  if (!notificationsEnabled.value || !session.value) return
  const registration = await registerServiceWorker()
  if (!registration) return
  const existing = await registration.pushManager.getSubscription()
  if (existing) await enablePush()
}

function handleServiceWorkerMessage(event: MessageEvent) {
  const data = event.data as { type?: string; conversationId?: string } | null
  if (data?.type !== 'channel-notification-click' || !data.conversationId) return
  view.value = 'conversations'
  selectedConversationId.value = data.conversationId
  void refreshConversations()
}

async function toggleNotifications() {
  if (notificationsEnabled.value) {
    notificationsEnabled.value = false
    pushSubscribed.value = false
    localStorage.removeItem(NOTIFY_KEY)
    const registration = await registerServiceWorker()
    const subscription = await registration?.pushManager.getSubscription()
    if (subscription) {
      if (session.value) await unsubscribeChannelPush(session.value, subscription.endpoint)
      await subscription.unsubscribe()
    }
    return
  }
  await enableNotifications()
}

async function enableNotifications() {
  notificationError.value = ''
  if (typeof window === 'undefined' || !('Notification' in window)) {
    notificationError.value = '当前浏览器不支持桌面通知'
    return
  }
  if (Notification.permission === 'denied') {
    notificationError.value = '浏览器已拒绝通知，请在站点权限里改为允许'
    return
  }
  const permission = await Notification.requestPermission()
  if (permission === 'granted') {
    notificationsEnabled.value = true
    localStorage.setItem(NOTIFY_KEY, '1')
    await enablePush()
    return
  }
  notificationsEnabled.value = false
  localStorage.removeItem(NOTIFY_KEY)
  notificationError.value = '未获得通知权限'
}

async function toggleSound() {
  notificationError.value = ''
  if (soundEnabled.value) {
    soundEnabled.value = false
    localStorage.removeItem(SOUND_KEY)
    return
  }
  try {
    audioContext ??= new AudioContext()
    await audioContext.resume()
    soundEnabled.value = true
    localStorage.setItem(SOUND_KEY, '1')
  } catch {
    notificationError.value = '浏览器未能启用音频，请检查站点声音权限'
  }
}

function playChime() {
  if (!soundEnabled.value || !audioContext) return
  const oscillator = audioContext.createOscillator()
  const gain = audioContext.createGain()
  oscillator.type = 'sine'
  oscillator.frequency.value = 880
  gain.gain.setValueAtTime(0.08, audioContext.currentTime)
  gain.gain.exponentialRampToValueAtTime(0.001, audioContext.currentTime + 0.25)
  oscillator.connect(gain)
  gain.connect(audioContext.destination)
  oscillator.start()
  oscillator.stop(audioContext.currentTime + 0.26)
}

function notifyConversation(conversation: ChannelConversation) {
  if (!conversation || (conversation.unread ?? 0) <= 0) return
  const last = conversation.messages[conversation.messages.length - 1]
  if (!last || last.role !== 'user') return
  if (conversation.conversationId === selectedConversationId.value && document.visibilityState === 'visible') {
    return
  }
  playChime()
  if (pushSubscribed.value || !notificationsEnabled.value) return
  const notification = new Notification(`新的渠道消息 · ${conversation.channel}`, {
    body: conversation.lastMessage || '买家发来一条新消息',
    tag: conversation.conversationId,
  })
  notification.onclick = () => {
    window.focus()
    view.value = 'conversations'
    selectedConversationId.value = conversation.conversationId
    void readConversation(conversation.conversationId)
    notification.close()
  }
}

function applyConversationStream(updated: ChannelConversation) {
  notifyConversation(updated)
  replaceConversation(updated)
  if (updated.conversationId === selectedConversationId.value && (updated.unread ?? 0) > 0) {
    void readConversation(updated.conversationId)
  }
}

async function readConversation(id: string) {
  if (!session.value) return
  const current = conversations.value.find((c) => c.conversationId === id)
  if (!current || !current.unread) return
  const result = await markConversationRead(session.value, id)
  if (result.status >= 200 && result.status < 300) {
    const body = result.body as { conversation?: ChannelConversation }
    if (body.conversation) replaceConversation(body.conversation)
  }
}

function startConversationStream() {
  if (!session.value || conversationStreamAbort) return
  const controller = new AbortController()
  conversationStreamAbort = controller
  void streamChannelConversations(session.value, applyConversationStream, controller.signal)
    .catch(() => {
      // 断开后由 finally 统一重连
    })
    .finally(() => {
      if (controller.signal.aborted) return
      conversationStreamAbort = null
      window.clearTimeout(conversationRetry)
      conversationRetry = window.setTimeout(startConversationStream, 5_000)
    })
}

function stopConversationStream() {
  window.clearTimeout(conversationRetry)
  conversationStreamAbort?.abort()
  conversationStreamAbort = null
}

function replaceConversation(updated: ChannelConversation) {
  const index = conversations.value.findIndex((c) => c.conversationId === updated.conversationId)
  if (index >= 0) {
    conversations.value[index] = updated
  } else {
    conversations.value = [updated, ...conversations.value]
  }
}

async function runConversation(action: 'takeover' | 'release') {
  if (!session.value || !selectedConversation.value || conversationInFlight.value) return
  const id = selectedConversation.value.conversationId
  conversationInFlight.value = `${id}:${action}`
  conversationError.value = ''
  conversationNote.value = ''
  const result =
    action === 'takeover'
      ? await takeoverConversation(session.value, id)
      : await releaseConversation(session.value, id)
  conversationInFlight.value = ''
  if (result.status >= 200 && result.status < 300) {
    const body = result.body as { conversation?: ChannelConversation }
    if (body.conversation) replaceConversation(body.conversation)
    conversationNote.value = action === 'takeover' ? '已接管会话' : '已释放回智能助手'
    return
  }
  conversationError.value = describe(result, '')
}

async function sendConversationReply() {
  const conversation = selectedConversation.value
  const text = conversationReply.value.trim()
  if (!session.value || !conversation || conversationInFlight.value) return
  if (!text) {
    conversationError.value = '回复内容不能为空'
    return
  }
  conversationInFlight.value = `${conversation.conversationId}:reply`
  conversationError.value = ''
  conversationNote.value = ''
  const result = await replyConversation(session.value, conversation.conversationId, text)
  conversationInFlight.value = ''
  if (result.status >= 200 && result.status < 300) {
    const body = result.body as { conversation?: ChannelConversation }
    if (body.conversation) replaceConversation(body.conversation)
    conversationReply.value = ''
    conversationNote.value = '已发送到飞书'
    return
  }
  conversationError.value = describe(result, '')
}

async function refreshChannels() {
  channelError.value = ''
  channelProbe.value = null
  if (!session.value) {
    channel.value = null
    return
  }
  channelLoading.value = true
  const result = await listChannels(session.value)
  channelLoading.value = false
  if (result.status !== 200) {
    channelError.value = describe(result, '')
    channel.value = null
    return
  }
  const list = (result.body as { channels?: ChannelConnection[] }).channels ?? []
  channel.value = list[0] ?? null
  if (channel.value) {
    channelForm.value = {
      enabled: channel.value.enabled,
      appId: '',
      appSecret: '',
      tenant: channel.value.tenant,
      customer: channel.value.customer,
      identityFile: '',
    }
  }
}

async function testChannel() {
  if (!session.value || channelTesting.value) return
  channelTesting.value = true
  channelProbe.value = null
  channelError.value = ''
  const result = await testFeishu(
    session.value,
    channelForm.value.appId.trim(),
    channelForm.value.appSecret.trim(),
  )
  channelTesting.value = false
  if (result.status !== 200) {
    channelError.value = describe(result, '')
    return
  }
  channelProbe.value = result.body as ChannelProbe
}

async function saveChannel() {
  if (!session.value || channelSaving.value) return
  channelSaving.value = true
  channelNote.value = ''
  channelError.value = ''
  const result = await saveFeishu(session.value, {
    enabled: channelForm.value.enabled,
    appId: channelForm.value.appId.trim(),
    appSecret: channelForm.value.appSecret.trim(),
    tenant: channelForm.value.tenant.trim(),
    customer: channelForm.value.customer.trim(),
    identityFile: channelForm.value.identityFile.trim(),
  })
  channelSaving.value = false
  if (result.status >= 200 && result.status < 300) {
    channelForm.value.appSecret = ''
    channelForm.value.appId = ''
    channelNote.value = '已保存。重启网关后生效。'
    await refreshChannels()
    return
  }
  channelError.value = describe(result, '')
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
  setUnauthorizedHandler(onUnauthorized)
  void registerServiceWorker().then(() => {
    if (notificationsEnabled.value) void restorePush()
  })
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.addEventListener('message', handleServiceWorkerMessage)
  }
  if (session.value) {
    void refresh()
    void refreshRefunds()
    void refreshConversations()
    void refreshKnowledge()
    startConversationStream()
  }
  timer = window.setInterval(() => {
    now.value = Date.now()
  }, 10_000)
})

onUnmounted(() => {
  setUnauthorizedHandler(null)
  window.clearInterval(timer)
  stopConversationStream()
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.removeEventListener('message', handleServiceWorkerMessage)
  }
})

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
    <TopBar :who="who" :has-session="!!session" @refresh="refresh" @sign-out="signOut" />

    <LoginCard
      v-if="!session"
      v-model:tenant="tenant"
      v-model:username="username"
      v-model:password="password"
      v-model:show-login="showLogin"
      :demo-loading="demoLoading"
      :login-error="loginError"
      @demo-login="doDemoLogin"
      @submit="doLogin"
    />

    <template v-else>
      <div class="console">
        <SideNav
          :view="view"
          :ticket-count="tickets.length"
          :unread="conversationUnread"
          :refund-count="refunds.length"
          :knowledge-count="knowledgeTodo"
          :tenant-id="session.tenantId"
          @select="selectView"
        />

        <div class="work-area">
          <QueueView
            v-if="view === 'queue'"
            :queue="queue"
            :status-filter="statusFilter"
            :selected-id="selectedId"
            :tickets="tickets"
            :filtered="filtered"
            :counts="counts"
            :selected="selected"
            :refund-count="refunds.length"
            :loading="loading"
            :load-error="loadError"
            :action-error="actionError"
            :action-note="actionNote"
            :in-flight="inFlight"
            :now="now"
            @change-queue="changeQueue"
            @set-filter="setFilter"
            @select="selectTicket"
            @refresh="refresh"
            @claim="run($event, 'claim')"
            @release="run($event, 'release')"
            @resolve="openResolve"
            @open-refunds="selectView('refunds')"
            @review-feedback="openFeedbackReview"
          />

          <ConversationsView
            v-else-if="view === 'conversations'"
            v-model:reply="conversationReply"
            :conversations="conversations"
            :selected-conversation="selectedConversation"
            :selected-conversation-id="selectedConversationId"
            :conversation-loading="conversationLoading"
            :conversation-error="conversationError"
            :conversation-note="conversationNote"
            :notification-error="notificationError"
            :notifications-enabled="notificationsEnabled"
            :sound-enabled="soundEnabled"
            :conversation-in-flight="conversationInFlight"
            @refresh="refreshConversations"
            @toggle-notifications="toggleNotifications"
            @toggle-sound="toggleSound"
            @select="selectConversation"
            @takeover="runConversation('takeover')"
            @release="runConversation('release')"
            @reply="sendConversationReply"
          />

          <RefundsView
            v-else-if="view === 'refunds'"
            :refunds="refunds"
            :refund-error="refundError"
            :refund-note="refundNote"
            :refund-in-flight="refundInFlight"
            :refund-tickets="refundTickets"
            @open="openRefund"
          />

          <KnowledgeView
            v-else-if="view === 'knowledge'"
            v-model:draft="draft"
            :feedback="feedback"
            :feedback-loading="feedbackLoading"
            :feedback-error="feedbackError"
            :reviewed="reviewedFeedback"
            :reviewed-total="reviewedTotal"
            :draft-feedback-id="draftFeedbackId"
            :revisions="revisions"
            :revisions-loading="revisionsLoading"
            :revisions-error="revisionsError"
            :action-note="kbNote"
            :action-error="kbError"
            :in-flight="inFlight"
            @refresh="refreshKnowledge"
            @review="reviewFeedbackItem"
            @pick-feedback="pickFeedbackToDraft"
            @cancel-draft="cancelDraft"
            @file="submitDraft"
            @publish="publishKnowledgeRevision"
            @reject="rejectKnowledgeRevision"
          />

          <ChannelsView
            v-else-if="view === 'channels'"
            :channel="channel"
            :channel-form="channelForm"
            :channel-loading="channelLoading"
            :channel-error="channelError"
            :channel-note="channelNote"
            :channel-saving="channelSaving"
            :channel-testing="channelTesting"
            :channel-probe="channelProbe"
            @refresh="refreshChannels"
            @save="saveChannel"
            @test="testChannel"
          />

          <QualityView
            v-else
            :quality="quality"
            :quality-loading="qualityLoading"
            :quality-error="qualityError"
            :blocked-reason-entries="blockedReasonEntries"
            :audit-action-entries="auditActionEntries"
            :handoff-context-loss-count="handoffContextLossCount"
            @refresh="refreshQuality"
          />
        </div>
      </div>
    </template>

    <ResolveDialog
      v-if="resolveTarget"
      v-model:note="resolveNote"
      :target="resolveTarget"
      :error="resolveError"
      :in-flight="inFlight"
      @close="closeResolve"
      @confirm="confirmResolve"
    />

    <RefundDialog
      v-if="refundTarget"
      v-model:note="refundDialogNote"
      :target="refundTarget"
      :decision="refundDecision"
      :error="refundDialogError"
      :in-flight="refundInFlight"
      @close="closeRefund"
      @confirm="confirmRefund"
    />
  </main>
</template>

<style scoped>
.shell {
  max-width: 1280px; margin: 0 auto; padding: 0 20px 40px;
  min-height: 100vh; display: flex; flex-direction: column;
}
.console {
  flex: 1;
  display: grid;
  grid-template-columns: 212px minmax(0, 1fr);
  gap: 18px;
  align-items: start;
  padding-top: 18px;
}
.work-area { min-width: 0; display: grid; gap: 14px; }

@media (max-width: 960px) {
  .console { grid-template-columns: 1fr; }
}

@media (max-width: 640px) {
  .shell { padding: 0 12px 24px; }
}
</style>
