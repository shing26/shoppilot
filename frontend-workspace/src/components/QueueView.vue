<script setup lang="ts">
/**
 * 工单队列视图（round44 票 188 从 `App.vue` 拆出）：KPI、筛选、队列与详情。
 *
 * <p>`queue` / `statusFilter` / `selectedId` 都用**显式 prop + 事件**而不是 `v-model`：
 * 换队列要先更新值再拉数据，依赖同一事件上两个处理器的先后顺序太脆。
 */
import { shortId } from 'frontend-shared'
import { PRIORITY_LABEL, QUEUE_LABEL, QUEUES, SOURCE_LABEL, STATUS_LABEL, type Ticket } from '../types'
import { formatTime, isOverdue, slaText } from '../lib/format'
import type { StatusFilter } from '../lib/ui'

defineProps<{
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
}>()

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
      <label>队列
        <select :value="queue" @change="onQueueChange">
          <option v-for="q in QUEUES" :key="q" :value="q">{{ QUEUE_LABEL[q] ?? q }}</option>
        </select>
      </label>
      <span class="toolbar-hint">按优先级排序；SLA 每 10 秒刷新</span>
      <button class="ghost" type="button" @click="emit('refresh')">刷新</button>
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
            :class="{ active: selectedId === t.id, overdue: isOverdue(t, now), urgent: t.priority === 'high' }"
            role="listitem"
            tabindex="0"
            @click="emit('select', t.id)"
            @keyup.enter="emit('select', t.id)"
          >
            <div class="ticket-top">
              <span class="priority">{{ PRIORITY_LABEL[t.priority ?? 'normal'] ?? t.priority }}</span>
              <span class="status-pill">{{ STATUS_LABEL[t.status] ?? t.status }}</span>
            </div>
            <p class="ticket-query">{{ t.userQuery }}</p>
            <div class="ticket-meta">
              <span>{{ SOURCE_LABEL[t.source ?? ''] ?? t.source ?? '—' }}</span>
              <span>{{ QUEUE_LABEL[t.queue ?? ''] ?? t.queue ?? '—' }}</span>
              <span :class="{ danger: isOverdue(t, now) }">{{ slaText(t, now) }}</span>
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
          <div><span>SLA</span><strong :class="{ danger: isOverdue(selected, now) }">{{ slaText(selected, now) }}</strong></div>
        </div>

        <div class="detail-actions">
          <button class="primary" type="button" :disabled="!!inFlight || !!selected.assignee || selected.status === 'RESOLVED'" @click="emit('claim', selected)">
            {{ inFlight === `${selected.id}:claim` ? '领取中…' : '领取' }}
          </button>
          <button class="ghost" type="button" :disabled="!!inFlight || !selected.assignee || selected.status === 'RESOLVED'" @click="emit('release', selected)">
            {{ inFlight === `${selected.id}:release` ? '释放中…' : '释放' }}
          </button>
          <button class="primary" type="button" :disabled="!!inFlight || selected.status === 'RESOLVED'" @click="emit('resolve', selected)">
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
              <time v-if="selected.status !== 'RESOLVED'">{{ slaText(selected, now) }}</time>
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
</template>

<style scoped>
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
.queue-pane, .detail-pane { background: var(--panel); border: 1px solid var(--line); border-radius: 16px; padding: 16px; }
.detail-pane { position: sticky; top: 74px; max-height: calc(100vh - 96px); overflow: auto; }
.ticket-list { display: grid; gap: 8px; }
.ticket-card { border: 1px solid var(--line); border-radius: 12px; padding: 11px 12px; cursor: pointer;
  transition: border-color .15s ease, box-shadow .15s ease; }
.ticket-card:hover { border-color: var(--teal-line); }
.ticket-card.active { border-color: var(--teal); box-shadow: 0 0 0 1px var(--teal) inset; background: var(--panel-active); }
.ticket-card.overdue { border-left: 3px solid var(--rose); }
.ticket-top { display: flex; justify-content: space-between; align-items: center; gap: 10px; }
.priority { font-size: 12px; color: var(--ink-3); }
.ticket-card.urgent .priority { color: var(--rose); font-weight: 600; }
.ticket-query { margin: 6px 0 8px; font-size: 14px; overflow-wrap: anywhere; }
.ticket-meta { display: flex; gap: 12px; flex-wrap: wrap; color: var(--ink-3); font-size: 12px; }
.ticket-foot { display: flex; justify-content: space-between; gap: 10px; margin-top: 8px; color: var(--ink-3); font-size: 12px; }
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
.timeline li.warn { color: var(--amber-ink); }
.timeline li.warn::before { background: var(--amber); }
.timeline time { margin-left: 6px; }
.transcript { max-height: 260px; overflow: auto; margin: 0; padding: 10px; background: var(--bg);
  border-radius: 10px; white-space: pre-wrap; overflow-wrap: anywhere; font: 12px/1.55 var(--mono); }
.resolution { border-top: 1px solid var(--line-2); margin-top: 14px; padding-top: 14px; }
.resolution h3 { font-size: 13px; margin: 0 0 6px; color: var(--teal); }
.resolution p { margin: 0; font-size: 13px; line-height: 1.6; overflow-wrap: anywhere; }
.empty-pane { display: grid; place-items: center; color: var(--ink-3); min-height: 220px; }

@media (max-width: 960px) {
  .workspace { grid-template-columns: 1fr; }
  .detail-pane { position: static; max-height: none; }
  .summary { grid-template-columns: repeat(3, 1fr); }
}

@media (max-width: 640px) {
  .summary { grid-template-columns: repeat(2, 1fr); }
  .detail-meta { grid-template-columns: 1fr; }
}
</style>
