<script setup lang="ts">
/**
 * 工单队列视图（round44 票 188 从 `App.vue` 拆出）：KPI、筛选、队列与详情。
 *
 * <p>`queue` / `statusFilter` / `selectedId` 都用**显式 prop + 事件**而不是 `v-model`：
 * 换队列要先更新值再拉数据，依赖同一事件上两个处理器的先后顺序太脆。
 */
import { computed, ref, watch } from 'vue'
import { copyToClipboard } from 'frontend-shared'
import { PRIORITY_LABEL, QUEUE_LABEL, QUEUES, SOURCE_LABEL, STATUS_LABEL, type Ticket } from '../types'
import { formatTime, isOverdue, payloadField, shortTicketId, slaText } from '../lib/format'
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

const mobileDetail = ref(false)
const copyNote = ref('')
const copyFailed = ref(false)
let copyTimer = 0

watch(() => props.selectedId, () => {
  window.clearTimeout(copyTimer)
  copyNote.value = ''
  copyFailed.value = false
})

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

function openTicket(id: string) {
  emit('select', id)
  mobileDetail.value = true
}

function backToList() {
  mobileDetail.value = false
}

async function copySelectedId() {
  if (!props.selected) return
  window.clearTimeout(copyTimer)
  const copied = await copyToClipboard(props.selected.id)
  if (copied) {
    copyNote.value = '已复制'
    copyFailed.value = false
    copyTimer = window.setTimeout(() => {
      copyNote.value = ''
    }, 1600)
  } else {
    // 失败时保留完整单号，直到下一次复制或切换工单；自动收口会让人来不及选中。
    copyNote.value = '复制失败，请手动选择完整单号'
    copyFailed.value = true
  }
}
</script>

<template>
  <section class="view-panel">
    <section class="summary" aria-label="队列概览">
      <div class="summary-lead">
        <button class="kpi lead" :class="{ active: statusFilter === 'ALL' }" type="button" @click="emit('setFilter', 'ALL')">
          <span>当前队列</span>
          <strong>{{ tickets.length }}</strong>
          <small>张工单待查看</small>
        </button>
        <button class="refund-entry" type="button" @click="emit('openRefunds')">
          待审退款 <b>{{ refundCount }}</b><span>去审核 →</span>
        </button>
      </div>
      <div class="kpi-cluster">
        <button class="kpi mini" :class="{ active: statusFilter === 'OPEN' }" type="button" @click="emit('setFilter', 'OPEN')">
          <span>待处理</span><strong>{{ counts.open }}</strong><small>未领取</small>
        </button>
        <button class="kpi mini" :class="{ active: statusFilter === 'ASSIGNED' }" type="button" @click="emit('setFilter', 'ASSIGNED')">
          <span>处理中</span><strong>{{ counts.assigned }}</strong><small>已领取</small>
        </button>
        <button class="kpi mini danger" :class="{ active: statusFilter === 'OVERDUE' }" type="button" @click="emit('setFilter', 'OVERDUE')">
          <span>已超时</span><strong>{{ counts.overdue }}</strong><small>超过 SLA</small>
        </button>
      </div>
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
      <span class="toolbar-count">{{ visibleTickets.length }} 张</span>
    </section>
    <p class="toolbar-hint">
      按优先级排序；SLA 只标记超时，不承诺解决时限。
    </p>

    <p v-if="loadError" class="err">{{ loadError }}</p>
    <p v-if="actionError" class="err">{{ actionError }}</p>
    <p v-if="actionNote" class="note">{{ actionNote }}</p>

    <div class="workspace" :class="{ 'show-detail': mobileDetail && selected }">
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
            @click="openTicket(t.id)"
            @keyup.enter="openTicket(t.id)"
          >
            <div class="ticket-top">
              <span class="priority" :class="priorityTone(t.priority)">
                <i class="prio-dot" aria-hidden="true"></i>
                {{ PRIORITY_LABEL[t.priority ?? 'normal'] ?? t.priority }}
              </span>
              <time class="ticket-time">{{ formatTime(t.createdAt) }}</time>
            </div>
            <p class="ticket-query">{{ t.userQuery }}</p>
            <div class="ticket-meta">
              <span class="source-badge">{{ SOURCE_LABEL[t.source ?? ''] ?? t.source ?? '未标注' }}</span>
              <span>{{ QUEUE_LABEL[t.queue ?? ''] ?? t.queue ?? '—' }}</span>
              <span class="status-pill">{{ STATUS_LABEL[t.status] ?? t.status }}</span>
              <span v-if="isOverdue(t, now)" class="sla danger">已超时</span>
            </div>
            <div class="ticket-foot">
              <span>{{ t.assignee ?? '待分配' }}</span>
              <span class="mono" :title="'工单号 ' + t.id">{{ shortTicketId(t.id) }}</span>
            </div>
          </article>
        </div>
      </section>

      <aside v-if="selected" class="detail-pane">
        <header class="detail-head">
          <div>
            <p class="eyebrow">工单</p>
            <h2 class="mono" :title="selected.id">#{{ shortTicketId(selected.id) }}</h2>
            <p class="detail-sub">
              {{ SOURCE_LABEL[selected.source ?? ''] ?? selected.source ?? '未标注' }}
              · {{ QUEUE_LABEL[selected.queue ?? ''] ?? selected.queue ?? '—' }}
              · {{ formatTime(selected.createdAt) }}
            </p>
          </div>
          <div class="detail-head-right">
            <span class="status-pill">{{ STATUS_LABEL[selected.status] ?? selected.status }}</span>
            <button class="ghost mobile-back" type="button" @click="backToList">返回队列</button>
          </div>
        </header>

        <button class="copy-id" type="button" @click="copySelectedId">
          {{ copyNote || '复制完整工单号' }}
        </button>
        <code v-if="copyFailed" class="copy-fallback">{{ selected.id }}</code>

        <section class="detail-query quote">
          <p class="quote-label">买家原始诉求</p>
          <p class="quote-text">{{ selected.userQuery }}</p>
        </section>

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

        <div class="detail-body">
          <div class="detail-main">
            <section class="block lifecycle-block">
              <h3>生命周期</h3>
              <ol class="lifecycle-chain">
                <li class="done">
                  <span class="step-title">买家提交工单</span>
                  <span class="step-note">{{ formatTime(selected.createdAt) }} · 对话问题已留存</span>
                </li>
                <li v-if="selected.escalatedAt" class="warn done">
                  <span class="step-title">超时升级</span>
                  <span class="step-note">{{ formatTime(selected.escalatedAt) }} · 优先级已抬高</span>
                </li>
                <li :class="{ done: !!selected.assignee, current: !selected.assignee && selected.status !== 'RESOLVED' }">
                  <span class="step-title">{{ selected.assignee ? `坐席 ${selected.assignee} 已领取` : '等待领取' }}</span>
                  <span class="step-note">{{ selected.assignee ? '责任已落到登录账号' : '领取后进入处理中' }}</span>
                </li>
                <li :class="{ done: selected.status === 'RESOLVED', current: selected.status !== 'RESOLVED' }">
                  <span class="step-title">{{ selected.status === 'RESOLVED' ? '已结单' : (STATUS_LABEL[selected.status] ?? selected.status) }}</span>
                  <span class="step-note">
                    {{ selected.status === 'RESOLVED' ? (selected.resolutionNote || '已记录处理结论') : `${slaText(selected, now)} · 结论会回到买家` }}
                  </span>
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
          </div>

          <aside class="detail-side">
            <section class="side-block">
              <h3>工单信息</h3>
              <dl class="detail-meta">
                <div><dt>来源</dt><dd>{{ SOURCE_LABEL[selected.source ?? ''] ?? selected.source ?? '—' }}</dd></div>
                <div><dt>队列</dt><dd>{{ QUEUE_LABEL[selected.queue ?? ''] ?? selected.queue ?? '—' }}</dd></div>
                <div><dt>优先级</dt><dd>{{ PRIORITY_LABEL[selected.priority ?? 'normal'] ?? selected.priority }}</dd></div>
                <div><dt>SLA</dt><dd :class="{ danger: isOverdue(selected, now) }">{{ slaText(selected, now) }}</dd></div>
                <div><dt>责任人</dt><dd>{{ selected.assignee ?? '待领取' }}</dd></div>
                <div v-if="selectedOrderNo"><dt>关联订单</dt><dd class="mono">{{ selectedOrderNo }}</dd></div>
              </dl>
            </section>

            <section v-if="selected.status === 'RESOLVED'" class="resolution side-block">
              <h3>处理结论</h3>
              <p>{{ selected.resolutionNote || '本次没有填写结单说明' }}</p>
            </section>

            <p class="actor-note">署名来自登录账号；页面不提供自报操作人。</p>
          </aside>
        </div>
      </aside>
      <aside v-else class="detail-pane empty-pane">选择左侧一张工单查看详情</aside>
    </div>
  </section>
</template>

<style scoped>
.summary {
  display: grid;
  grid-template-columns: minmax(230px, 0.72fr) minmax(0, 1.65fr);
  gap: 12px;
  margin: 0;
  background: none;
  border: none;
  border-radius: 0;
  overflow: visible;
}
.summary-lead {
  display: grid;
  grid-template-rows: 1fr auto;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-panel);
  overflow: hidden;
}
.kpi-cluster {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-panel);
  overflow: hidden;
}
.kpi {
  text-align: left;
  background: transparent;
  border: none;
  border-radius: 0;
  cursor: pointer;
}
.kpi span { display: block; color: var(--ink-3); font-size: 12px; letter-spacing: .02em; }
.kpi strong { display: block; line-height: 1.1; font-variant-numeric: tabular-nums; }
.kpi.lead {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-height: 94px;
  padding: 16px 20px 14px;
}
.kpi.lead strong {
  margin: 4px 0 2px;
  font-family: var(--serif);
  font-size: 42px;
  font-weight: 600;
}
.kpi.lead small {
  color: var(--ink-3);
  font-size: 12px;
}
.kpi.lead.active { background: var(--panel-2); box-shadow: inset 3px 0 0 var(--teal); }
.refund-entry {
  display: flex;
  align-items: center;
  gap: 6px;
  min-height: 40px;
  padding: 8px 20px;
  border: none;
  border-top: 1px solid var(--line-2);
  border-radius: 0;
  background: var(--panel-2);
  color: var(--ink-2);
  text-align: left;
  font-size: 13px;
}
.refund-entry b { color: var(--amber); font-variant-numeric: tabular-nums; }
.refund-entry span { margin-left: auto; color: var(--teal); font-size: 12px; }
.refund-entry:hover:not(:disabled) { background: var(--amber-soft); border-color: var(--line-2); }
.kpi.mini {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  min-height: 118px;
  padding: 16px 20px;
  border-right: 1px solid var(--line-2);
}
.kpi.mini:last-child { border-right: none; }
.kpi.mini strong { margin: 0; font-size: 30px; font-weight: 650; }
.kpi.mini small { color: var(--ink-3); font-size: 12px; }
.kpi.mini:hover:not(:disabled) { background: var(--panel-2); border-color: var(--line-2); }
.kpi.mini.active { background: var(--panel-2); box-shadow: inset 0 -2px 0 var(--teal); }
.kpi.danger strong { color: var(--rose); }
.toolbar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin: 0; }
.filter-field { font-size: 13px; color: var(--ink-3); display: flex; align-items: center; gap: 7px; }
.toolbar select { min-height: 34px; padding: 5px 9px; font-size: 13px; }
.search-field {
  flex: 1; min-width: 240px;
  display: flex; align-items: center; gap: 7px;
  border: 1px solid var(--line); border-radius: var(--radius-md);
  background: var(--panel); padding: 0 11px; height: 36px;
  color: var(--ink-3);
}
.search-field:focus-within { border-color: var(--teal-line-2); box-shadow: var(--ring); }
.search-field input {
  flex: 1; min-width: 0; border: none; background: none; padding: 0;
  font-size: 13px; color: var(--ink);
}
.search-field input:focus { outline: none; box-shadow: none; }
.toolbar-count { margin-left: auto; color: var(--ink-3); font-size: 12px; font-variant-numeric: tabular-nums; }
.toolbar-hint { margin: -7px 0 0; color: var(--ink-3); font-size: 12px; }
.workspace {
  display: grid;
  grid-template-columns: minmax(330px, 390px) minmax(0, 1fr);
  gap: 14px;
  align-items: stretch;
  height: calc(100dvh - 318px);
  min-height: 440px;
}
.queue-pane, .detail-pane {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-panel);
  padding: 20px;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.detail-pane { overflow: auto; }
.ticket-list { display: grid; overflow-y: auto; padding-right: 2px; }
.ticket-card {
  border: none;
  border-bottom: 1px solid var(--line-2);
  border-left: 2px solid transparent;
  border-radius: 0;
  padding: 13px 12px;
  cursor: pointer;
  background: transparent;
  transition: border-color var(--dur-1) var(--ease), background var(--dur-1) var(--ease);
}
.ticket-card:last-child { border-bottom: none; }
.ticket-card:hover { background: var(--panel-2); border-left-color: var(--teal-line); }
.ticket-card.active { background: var(--panel-active); border-left-color: var(--teal); }
.ticket-card.overdue { border-left-color: var(--rose); }
.ticket-card.overdue.active { border-left-color: var(--teal); }
.ticket-top { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.priority {
  display: inline-flex; align-items: center; gap: 5px;
  font-size: 12px; color: var(--ink-2); letter-spacing: .02em;
}
.prio-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--ink-3); display: inline-block; }
.priority.urgent { color: var(--rose); }
.priority.urgent .prio-dot { background: var(--rose); box-shadow: 0 0 0 3px var(--rose-soft); }
.priority.money { color: var(--amber-ink); }
.priority.money .prio-dot { background: var(--amber); box-shadow: 0 0 0 3px var(--amber-soft); }
.priority.normal .prio-dot { background: var(--ink-3); }
.ticket-time { color: var(--ink-3); font-size: 12px; font-variant-numeric: tabular-nums; }
.source-badge {
  font-size: 12px; color: var(--ink-3); background: none;
  border: none; border-radius: 0; padding: 0;
  white-space: nowrap;
}
.ticket-card.urgent .priority { color: var(--rose); font-weight: 600; }
.ticket-query {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  margin: 7px 0 9px;
  overflow: hidden;
  font-size: 14px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}
.ticket-meta { display: flex; align-items: center; gap: 9px; flex-wrap: wrap; color: var(--ink-3); font-size: 12px; }
.ticket-meta .status-pill { margin-left: auto; }
.ticket-foot { display: flex; justify-content: space-between; gap: 10px; margin-top: 8px; color: var(--ink-3); font-size: 12px; }
.detail-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.detail-head h2 { margin: 3px 0 0; font-family: var(--serif); font-size: 20px; font-weight: 600; }
.detail-sub { margin: 5px 0 0; color: var(--ink-3); font-size: 12px; line-height: 1.55; }
.detail-head-right { display: flex; align-items: center; gap: 8px; flex: none; }
.mobile-back { display: none; }
.copy-id {
  display: inline-flex;
  flex: none;
  align-items: center;
  min-height: 22px;
  align-self: flex-start;
  margin-top: 8px;
  padding: 0;
  border: none;
  background: none;
  color: var(--teal);
  font-size: 12px;
  text-decoration: underline;
  text-underline-offset: 2px;
}
.copy-fallback {
  display: block;
  margin-top: 6px;
  padding: 6px 8px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: var(--panel-2);
  color: var(--ink-2);
  font: 12px/1.5 var(--mono);
  overflow-wrap: anywhere;
  user-select: all;
}
.detail-query.quote {
  margin: 14px 0;
  background: var(--panel-2);
  border: 1px solid var(--line);
  border-left: 3px solid var(--teal-line-2);
  border-radius: var(--radius-lg);
  padding: 16px 20px;
}
.quote-label {
  margin: 0 0 5px; color: var(--ink-3); font-size: 12px;
  letter-spacing: .02em;
}
.quote-text {
  margin: 0; overflow-wrap: anywhere; font-size: 14px; line-height: 1.7; color: var(--ink);
}
.detail-actions { display: flex; gap: 8px; flex-wrap: wrap; margin: 0 0 8px; }
.action-hint { margin: -8px 0 16px; color: var(--ink-2); font-size: 12px; }
.actor-stamp { margin: -2px 0 16px; color: var(--ink-3); font-size: 12px; }
.detail-body { display: grid; grid-template-columns: minmax(0, 1.45fr) minmax(230px, .7fr); gap: 22px; align-items: start; }
.detail-main { min-width: 0; }
.detail-side { display: grid; gap: 12px; }
.block { border-top: 1px solid var(--line-2); padding-top: 16px; margin-top: 16px; }
.lifecycle-block { border-top: none; padding-top: 0; margin-top: 2px; }
.block h3, .side-block h3 { font-family: var(--serif); font-size: 15px; font-weight: 600; margin: 0 0 12px; }
.lifecycle-chain {
  list-style: none;
  margin: 0;
  padding: 2px 0 2px 22px;
  position: relative;
  display: grid;
}
.lifecycle-chain::before {
  content: "";
  position: absolute;
  left: 4px;
  top: 8px;
  bottom: 12px;
  width: 1px;
  background: var(--line);
}
.lifecycle-chain li { position: relative; display: grid; gap: 2px; padding: 0 0 18px; }
.lifecycle-chain li:last-child { padding-bottom: 0; }
.lifecycle-chain li::before {
  content: "";
  position: absolute;
  left: -22px;
  top: 4px;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--panel);
  border: 1px solid var(--ink-3);
}
.lifecycle-chain li.done::before { background: var(--teal); border-color: var(--teal); }
.lifecycle-chain li.current::before {
  background: var(--panel);
  border: 2px solid var(--teal);
  box-shadow: 0 0 0 4px var(--teal-soft);
}
.lifecycle-chain li.warn::before { background: var(--amber); border-color: var(--amber); }
.step-title { color: var(--ink); font-size: 13px; font-weight: 500; }
.step-note { color: var(--ink-3); font-size: 12px; line-height: 1.55; overflow-wrap: anywhere; }
.transcript { display: grid; gap: 8px; max-height: 320px; overflow: auto; padding-right: 2px; }
.bubble {
  max-width: 92%; border-radius: var(--radius-md); padding: 8px 10px;
  font-size: 13px; line-height: 1.6;
}
.bubble-role { display: block; font-size: 12px; color: var(--ink-3); margin-bottom: 3px; }
.bubble p { margin: 0; overflow-wrap: anywhere; }
.bubble pre { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; font: 12px/1.6 var(--mono); }
.bubble.buyer {
  justify-self: end; background: var(--ink); border: 1px solid var(--ink); color: #fff;
}
.bubble.system { justify-self: start; background: var(--bg); border: 1px solid var(--line); color: var(--ink-2); }
.side-block {
  padding: 16px;
  background: var(--panel-2);
  border: 1px solid var(--line-2);
  border-radius: var(--radius-xl);
}
.side-block h3 { margin-bottom: 10px; }
.detail-meta { display: grid; gap: 0; margin: 0; }
.detail-meta div {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 7px 0;
  border-bottom: 1px solid var(--line-2);
}
.detail-meta div:last-child { border-bottom: none; }
.detail-meta dt { color: var(--ink-3); font-size: 12px; }
.detail-meta dd { margin: 0; color: var(--ink); font-size: 13px; text-align: right; overflow-wrap: anywhere; }
.resolution p { margin: 0; font-size: 13px; line-height: 1.7; overflow-wrap: anywhere; }
.actor-note { margin: 0; color: var(--ink-3); font-size: 12px; line-height: 1.6; }
.empty-pane { display: grid; place-items: center; color: var(--ink-3); min-height: 220px; }

@media (max-width: 960px) {
  .workspace { grid-template-columns: 1fr; height: auto; min-height: 0; }
  .queue-pane, .detail-pane { max-height: none; }
  .ticket-list { overflow: visible; }
  .summary { grid-template-columns: 1fr; }
  .kpi.mini { min-height: 104px; }
  .workspace:not(.show-detail) .detail-pane { display: none; }
  .workspace.show-detail .queue-pane { display: none; }
  .workspace.show-detail .detail-pane { display: flex; }
  .mobile-back { display: inline-flex; }
  .detail-body { grid-template-columns: 1fr; gap: 16px; }
  .detail-side { grid-template-columns: 1fr 1fr; align-items: start; }
  .actor-note { grid-column: 1 / -1; }
}

@media (max-width: 640px) {
  .summary-lead { grid-template-rows: 1fr auto; }
  .kpi.lead { min-height: 88px; padding: 14px 16px 12px; }
  .kpi.lead strong { font-size: 36px; }
  .refund-entry { padding: 8px 16px; }
  .kpi.mini { min-height: 104px; padding: 13px 12px; }
  .kpi.mini strong { font-size: 26px; }
  .detail-head { flex-direction: column; gap: 10px; }
  .detail-head-right { width: 100%; justify-content: space-between; }
  .detail-side { grid-template-columns: 1fr; }
  .queue-pane, .detail-pane { padding: 20px; }
  .ticket-card { padding: 14px 16px; }
  .toolbar-count { display: none; }
}
</style>
