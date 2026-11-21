<script setup lang="ts">
/**
 * 工单队列视图（round44 票 188 从 `App.vue` 拆出）：KPI、筛选、队列与详情。
 *
 * <p>`queue` / `statusFilter` / `selectedId` 都用**显式 prop + 事件**而不是 `v-model`：
 * 换队列要先更新值再拉数据，依赖同一事件上两个处理器的先后顺序太脆。
 */
import { computed, ref } from 'vue'
import { shortId } from 'frontend-shared'
import { PRIORITY_LABEL, QUEUE_LABEL, QUEUES, SOURCE_LABEL, STATUS_LABEL, type Ticket } from '../types'
import { formatTime, isOverdue, payloadField, slaText } from '../lib/format'
import type { StatusFilter } from '../lib/ui'

const props = defineProps<{
  queue: string
  statusFilter: StatusFilter
  selectedId: string
  tickets: Ticket[]
  filtered: Ticket[]
  counts: { open: number; assigned: number; overdue: number }
  selected: Ticket | null
  refundCount: number
  loading: boolean
  loadError: string
  actionError: string
  actionNote: string
  inFlight: string
  now: number
}>()

const emit = defineEmits<{
  changeQueue: [queue: string]
  setFilter: [filter: StatusFilter]
  select: [id: string]
  refresh: []
  claim: [ticket: Ticket]
  release: [ticket: Ticket]
  resolve: [ticket: Ticket]
  openRefunds: []
  reviewFeedback: [feedbackId: string]
}>()

/**
 * 复核单指回的那条反馈（round46 票 194）：`payload.feedbackId` 由 biz-mock 开单时写入。
 * 解析不出来就不显示入口——宁可少一个按钮，也不要跳到一个猜出来的单号上。
 */
const reviewFeedbackId = computed(() => {
  if (props.selected?.source !== 'FEEDBACK_REVIEW') return null
  return payloadField(props.selected, 'feedbackId')
})

// 筛选条是视图内状态：不改根组件的队列与状态筛选，只收窄当前列表。
const keyword = ref('')
const sourceFilter = ref('ALL')
const SOURCE_FILTERS = ['ALL', ...Object.keys(SOURCE_LABEL)]

const visibleTickets = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  return props.filtered.filter((ticket) => {
    if (sourceFilter.value !== 'ALL' && (ticket.source ?? '') !== sourceFilter.value) return false
    if (!q) return true
    return [ticket.id, ticket.userQuery, ticket.assignee ?? '', ticket.queue ?? '']
      .some((value) => value.toLowerCase().includes(q))
  })
})

const selectedOrderNo = computed(() => {
  if (!props.selected) return ''
  return payloadField(props.selected, 'orderNo') ?? ''
})

function priorityTone(priority: string | null | undefined) {
  if (priority === 'high') return 'urgent'
  if (priority === 'money') return 'money'
  return 'normal'
}

function syncSelection() {
  if (!visibleTickets.value.some((ticket) => ticket.id === props.selectedId)) {
    emit('select', visibleTickets.value[0]?.id ?? '')
  }
}

function onKeyword(event: Event) {
  keyword.value = (event.target as HTMLInputElement).value
  syncSelection()
}

function onSourceChange(event: Event) {
  sourceFilter.value = (event.target as HTMLSelectElement).value
  syncSelection()
}

function onQueueChange(event: Event) {
  emit('changeQueue', (event.target as HTMLSelectElement).value)
}
</script>

<template>
  <section class="view-panel">
    <section class="summary">
      <button class="kpi" :class="{ active: statusFilter === 'ALL' }" type="button" @click="emit('setFilter', 'ALL')">
        <span>当前队列</span><strong>{{ tickets.length }}</strong>
      </button>
      <button class="kpi" :class="{ active: statusFilter === 'OPEN' }" type="button" @click="emit('setFilter', 'OPEN')">
        <span>待处理</span><strong>{{ counts.open }}</strong>
      </button>
      <button class="kpi" :class="{ active: statusFilter === 'ASSIGNED' }" type="button" @click="emit('setFilter', 'ASSIGNED')">
        <span>处理中</span><strong>{{ counts.assigned }}</strong>
      </button>
      <button class="kpi danger" :class="{ active: statusFilter === 'OVERDUE' }" type="button" @click="emit('setFilter', 'OVERDUE')">
        <span>已超时</span><strong>{{ counts.overdue }}</strong>
      </button>
      <button class="kpi refund" type="button" @click="emit('openRefunds')">
        <span>待审退款</span><strong>{{ refundCount }}</strong>
      </button>
    </section>

    <section class="toolbar">
      <label class="filter-field">队列
        <select :value="queue" @change="onQueueChange">
          <option v-for="q in QUEUES" :key="q" :value="q">{{ QUEUE_LABEL[q] ?? q }}</option>
        </select>
      </label>
      <label class="filter-field">来源
        <select :value="sourceFilter" @change="onSourceChange">
          <option v-for="source in SOURCE_FILTERS" :key="source" :value="source">
            {{ source === 'ALL' ? '全部来源' : (SOURCE_LABEL[source] ?? source) }}
          </option>
        </select>
      </label>
      <label class="search-field">
        <span aria-hidden="true">⌕</span>
        <input
          :value="keyword"
          type="search"
          placeholder="搜索工单号、买家或问题关键词"
          aria-label="搜索工单"
          @input="onKeyword"
        />
      </label>
      <button class="ghost" type="button" @click="emit('refresh')">刷新</button>
    </section>
    <p class="toolbar-hint">
      共 {{ visibleTickets.length }} 张；按优先级排序，SLA 只标记超时，不承诺解决时限。
    </p>

    <p v-if="loadError" class="err">{{ loadError }}</p>
    <p v-if="actionError" class="err">{{ actionError }}</p>
    <p v-if="actionNote" class="note">{{ actionNote }}</p>

    <div class="workspace">
      <section class="queue-pane">
        <div class="pane-head">
          <h2>工单队列</h2>
          <span class="count">{{ visibleTickets.length }}</span>
        </div>
        <p v-if="loading" class="empty">正在读取队列…</p>
        <p v-else-if="!loadError && !visibleTickets.length" class="empty">当前筛选下没有工单</p>
        <div v-else class="ticket-list" role="list">
          <article
            v-for="t in visibleTickets"
            :key="t.id"
            class="ticket-card"
            :class="{ active: selectedId === t.id, overdue: isOverdue(t, now), urgent: t.priority === 'high' }"
            role="listitem"
            tabindex="0"
            @click="emit('select', t.id)"
            @keyup.enter="emit('select', t.id)"
          >
            <div class="ticket-top">
              <span class="priority" :class="priorityTone(t.priority)">
                <i class="prio-dot" aria-hidden="true"></i>
                {{ PRIORITY_LABEL[t.priority ?? 'normal'] ?? t.priority }}
              </span>
              <span class="source-badge">{{ SOURCE_LABEL[t.source ?? ''] ?? t.source ?? '未标注' }}</span>
              <span class="status-pill">{{ STATUS_LABEL[t.status] ?? t.status }}</span>
            </div>
            <p class="ticket-query">{{ t.userQuery }}</p>
            <div class="ticket-meta">
              <span>{{ QUEUE_LABEL[t.queue ?? ''] ?? t.queue ?? '—' }}</span>
              <span class="sla" :class="{ danger: isOverdue(t, now) }">{{ slaText(t, now) }}</span>
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

        <section class="detail-query quote">
          <p class="quote-label">买家原始诉求</p>
          <p class="quote-text">{{ selected.userQuery }}</p>
        </section>

        <div class="detail-meta">
          <div><span>来源</span><strong>{{ SOURCE_LABEL[selected.source ?? ''] ?? selected.source ?? '—' }}</strong></div>
          <div><span>队列</span><strong>{{ QUEUE_LABEL[selected.queue ?? ''] ?? selected.queue ?? '—' }}</strong></div>
          <div><span>优先级</span><strong>{{ PRIORITY_LABEL[selected.priority ?? 'normal'] ?? selected.priority }}</strong></div>
          <div><span>SLA</span><strong :class="{ danger: isOverdue(selected, now) }">{{ slaText(selected, now) }}</strong></div>
          <div><span>责任人</span><strong>{{ selected.assignee ?? '待领取' }}</strong></div>
          <div v-if="selectedOrderNo"><span>关联订单</span><strong class="mono">{{ selectedOrderNo }}</strong></div>
        </div>

        <div class="detail-actions">
          <button class="primary" type="button" :disabled="!!inFlight || !!selected.assignee || selected.status === 'RESOLVED'" @click="emit('claim', selected)">
            {{ inFlight === `${selected.id}:claim` ? '领取中…' : '领取' }}
          </button>
          <button class="ghost" type="button" :disabled="!!inFlight || !selected.assignee || selected.status === 'RESOLVED'" @click="emit('release', selected)">
            {{ inFlight === `${selected.id}:release` ? '释放中…' : '释放' }}
          </button>
          <button
            class="primary"
            type="button"
            :disabled="!!inFlight || !selected.assignee || selected.status === 'RESOLVED'"
            :title="!selected.assignee ? '先领取这张工单，再处理完成' : undefined"
            @click="emit('resolve', selected)"
          >
            处理完成
          </button>
          <button
            v-if="reviewFeedbackId"
            class="ghost"
            type="button"
            @click="emit('reviewFeedback', reviewFeedbackId)"
          >
            去复核这条反馈
          </button>
        </div>
        <p v-if="!selected.assignee && selected.status !== 'RESOLVED'" class="action-hint">
          先领取这张工单，再处理完成
        </p>
        <p class="actor-stamp">领取与结单署名取自登录账号，页面不能手填。</p>

        <section class="block">
          <h3>生命周期</h3>
          <p class="lifecycle-tags">
            <span>{{ SOURCE_LABEL[selected.source ?? ''] ?? selected.source ?? '—' }}</span>
            <span>{{ QUEUE_LABEL[selected.queue ?? ''] ?? selected.queue ?? '—' }}</span>
            <span v-if="selected.escalatedAt" class="danger">已升级</span>
          </p>
          <ol class="timeline">
            <li class="done"><span>已创建</span><time>{{ formatTime(selected.createdAt) }}</time></li>
            <li v-if="selected.escalatedAt" class="warn"><span>已超时升级</span><time>{{ formatTime(selected.escalatedAt) }}</time></li>
            <li :class="{ done: !!selected.assignee }">
              <span>{{ selected.assignee ? `坐席 ${selected.assignee} 已领取` : '待领取' }}</span>
            </li>
            <li :class="{ done: selected.status === 'RESOLVED' }">
              <span>{{ selected.status === 'RESOLVED' ? '已结单' : (STATUS_LABEL[selected.status] ?? selected.status) }}</span>
              <time v-if="selected.status !== 'RESOLVED'">{{ slaText(selected, now) }}</time>
            </li>
          </ol>
        </section>

        <section class="block">
          <h3>转人工上下文</h3>
          <div v-if="selected.transcript" class="transcript">
            <div class="bubble buyer">
              <span class="bubble-role">买家提问</span>
              <p>{{ selected.userQuery }}</p>
            </div>
            <div class="bubble system">
              <span class="bubble-role">系统记录 · 原文未解析</span>
              <pre>{{ selected.transcript }}</pre>
            </div>
          </div>
          <p v-else class="empty">这张单没有记录失败/升级上下文</p>
          <p v-if="selected.transcript" class="fine">
            这一格是降级出口写下的诊断事实（失败原因、被拦的交接、工具回执），不是完整对话记录。
          </p>
        </section>

        <section v-if="selected.status === 'RESOLVED'" class="resolution">
          <h3>处理结论</h3>
          <p>{{ selected.resolutionNote || '本次没有填写结单说明' }}</p>
        </section>
      </aside>
      <aside v-else class="detail-pane empty-pane">选择左侧一张工单查看详情</aside>
    </div>
  </section>
</template>

<style scoped>
.summary {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  margin: 0;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  overflow: hidden;
}
.kpi {
  text-align: left;
  background: transparent;
  border: none;
  border-right: 1px solid var(--line);
  border-radius: 0;
  padding: 12px 16px;
  cursor: pointer;
  min-height: 64px;
}
.kpi:last-child { border-right: none; }
.kpi span { display: block; color: var(--ink-3); font-size: 11px; text-transform: uppercase; letter-spacing: .04em; }
.kpi strong { display: block; font-size: 22px; line-height: 1.2; margin-top: 4px; font-variant-numeric: tabular-nums; }
.kpi:hover:not(:disabled) { background: var(--panel-2); border-color: var(--line); }
.kpi.active { background: var(--teal-wash); box-shadow: inset 0 -2px 0 var(--teal); }
.kpi.danger strong { color: var(--rose); }
.kpi.refund strong { color: var(--amber); }
.toolbar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin: 0; }
.filter-field { font-size: 12px; color: var(--ink-3); display: flex; align-items: center; gap: 6px; }
.toolbar select { padding: 5px 8px; font-size: 12px; }
.search-field {
  flex: 1; min-width: 220px;
  display: flex; align-items: center; gap: 7px;
  border: 1px solid var(--line); border-radius: var(--radius-md);
  background: var(--panel); padding: 0 10px; height: 32px;
  color: var(--ink-3);
}
.search-field:focus-within { border-color: var(--teal-line-2); box-shadow: var(--ring); }
.search-field input {
  flex: 1; min-width: 0; border: none; background: none; padding: 0;
  font-size: 12px; color: var(--ink);
}
.search-field input:focus { outline: none; box-shadow: none; }
.toolbar-hint { margin: -6px 0 0; color: var(--ink-3); font-size: 11px; }
.toolbar .ghost { margin-left: auto; }
.workspace {
  display: grid;
  grid-template-columns: minmax(360px, 420px) minmax(0, 1fr);
  gap: 16px;
  align-items: stretch;
  height: calc(100dvh - 236px);
  min-height: 420px;
}
.queue-pane, .detail-pane {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.detail-pane { overflow: auto; }
.ticket-list { display: grid; gap: 8px; }
.ticket-list { overflow-y: auto; padding-right: 2px; }
.ticket-card {
  border: 1px solid var(--line);
  border-left: 3px solid var(--line);
  border-radius: var(--radius-md);
  padding: 10px 12px;
  cursor: pointer;
  transition: border-color .15s ease, background .15s ease;
}
.ticket-card:hover { border-color: var(--teal-line); border-left-color: var(--teal-line); background: var(--panel-2); }
.ticket-card.active { border-color: var(--teal-line); border-left-color: var(--teal); background: var(--teal-wash); }
.ticket-card.overdue { border-left: 3px solid var(--rose); }
.ticket-top { display: flex; align-items: center; gap: 7px; }
.priority {
  display: inline-flex; align-items: center; gap: 5px;
  font-size: 11px; color: var(--ink-2); letter-spacing: .02em;
}
.prio-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--ink-3); display: inline-block; }
.priority.urgent { color: var(--rose); }
.priority.urgent .prio-dot { background: var(--rose); box-shadow: 0 0 0 3px var(--rose-soft); }
.priority.money { color: var(--amber-ink); }
.priority.money .prio-dot { background: var(--amber); box-shadow: 0 0 0 3px var(--amber-soft); }
.priority.normal .prio-dot { background: var(--ink-3); }
.source-badge {
  font-size: 10px; color: var(--ink-3); background: var(--bg);
  border: 1px solid var(--line-2); border-radius: var(--radius-full); padding: 0 7px;
  white-space: nowrap;
}
.ticket-top .status-pill { margin-left: auto; }
.ticket-card.urgent .priority { color: var(--rose); font-weight: 600; }
.ticket-query { margin: 6px 0 8px; font-size: 13px; line-height: 1.5; overflow-wrap: anywhere; }
.ticket-meta { display: flex; gap: 12px; flex-wrap: wrap; color: var(--ink-3); font-size: 11px; }
.ticket-foot { display: flex; justify-content: space-between; gap: 10px; margin-top: 8px; color: var(--ink-3); font-size: 11px; }
.detail-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.detail-head h2 { margin: 2px 0 0; font-size: 14px; }
.detail-query.quote {
  margin: 12px 0;
  background: var(--panel-2);
  border: 1px solid var(--line);
  border-left: 3px solid var(--teal-line-2);
  border-radius: var(--radius-md);
  padding: 10px 12px;
}
.quote-label {
  margin: 0 0 4px; color: var(--ink-3); font-size: 10px;
  letter-spacing: .06em; text-transform: uppercase;
}
.quote-text {
  margin: 0; overflow-wrap: anywhere; font-size: 13px; line-height: 1.65; color: var(--ink);
}
.detail-meta { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin-bottom: 14px; }
.detail-meta div { display: grid; gap: 2px; }
.detail-meta span { color: var(--ink-3); font-size: 11px; text-transform: uppercase; letter-spacing: .04em; }
.detail-meta strong { font-size: 13px; }
.detail-actions { display: flex; gap: 8px; flex-wrap: wrap; margin: 0 0 16px; }
.action-hint { margin: -8px 0 16px; color: var(--ink-2); font-size: 12px; }
.actor-stamp { margin: -8px 0 16px; color: var(--ink-3); font-size: 11px; }
.block { border-top: 1px solid var(--line-2); padding-top: 14px; margin-top: 14px; }
.block h3 { font-size: 13px; margin: 0 0 8px; }
.timeline { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
.timeline li { position: relative; padding-left: 16px; color: var(--ink-3); font-size: 13px; }
.timeline li::before { content: ''; position: absolute; left: 0; top: 6px; width: 6px; height: 6px; border-radius: 50%; background: var(--line); }
.timeline li.done::before { background: var(--teal); }
.timeline li.warn { color: var(--amber-ink); }
.timeline li.warn::before { background: var(--amber); }
.timeline time { margin-left: 6px; }
.lifecycle-tags { display: flex; gap: 6px; flex-wrap: wrap; margin: 0 0 9px; }
.lifecycle-tags span {
  font-size: 11px; color: var(--ink-3); background: var(--bg);
  border-radius: var(--radius-full); padding: 1px 8px;
}
.lifecycle-tags .danger { color: var(--rose); background: var(--rose-soft); }
.transcript { display: grid; gap: 8px; max-height: 320px; overflow: auto; padding-right: 2px; }
.bubble {
  max-width: 92%; border-radius: var(--radius-md); padding: 8px 10px;
  font-size: 12px; line-height: 1.55;
}
.bubble-role { display: block; font-size: 11px; color: var(--ink-3); margin-bottom: 3px; }
.bubble p { margin: 0; overflow-wrap: anywhere; }
.bubble pre { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; font: 12px/1.55 var(--mono); }
.bubble.buyer {
  justify-self: end; background: var(--ink); border: 1px solid var(--ink); color: #fff;
}
.bubble.system { justify-self: start; background: var(--bg); border: 1px solid var(--line); color: var(--ink-2); }
.resolution { border-top: 1px solid var(--line-2); margin-top: 14px; padding-top: 14px; }
.resolution h3 { font-size: 13px; margin: 0 0 6px; color: var(--teal); }
.resolution p { margin: 0; font-size: 13px; line-height: 1.6; overflow-wrap: anywhere; }
.empty-pane { display: grid; place-items: center; color: var(--ink-3); min-height: 220px; }

@media (max-width: 960px) {
  .workspace { grid-template-columns: 1fr; height: auto; min-height: 0; }
  .queue-pane, .detail-pane { max-height: none; }
  .ticket-list { overflow: visible; }
  .summary { grid-template-columns: repeat(3, 1fr); }
  .kpi:nth-child(3) { border-right: none; }
  .detail-meta { grid-template-columns: 1fr 1fr; }
}

@media (max-width: 640px) {
  .summary { grid-template-columns: repeat(2, 1fr); }
  .kpi { border-right: 1px solid var(--line); }
  .kpi:nth-child(2n) { border-right: none; }
  .detail-meta { grid-template-columns: 1fr; }
}
</style>
