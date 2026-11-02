<script setup lang="ts">
/**
 * 工单详情抽屉（round44 票 188 从 `App.vue` 拆出）。
 *
 * <p>轮询（8 秒）由 `App.vue` 持有：它取的是"当前选中工单"，而选中状态属于页面，
 * 不属于这张卡片。这里只负责画，刷新动作发事件回去。
 */
import { PRIORITY_LABEL, STATUS_LABEL, type Ticket } from '../types'
import { channelLabel, formatTime, reasonLabel, slaText } from '../lib/format'

defineProps<{
  ticket: Ticket
  detailLoading: boolean
  detailError: string
  progressNote: string
  now: number
}>()

const emit = defineEmits<{ close: []; refresh: [] }>()
</script>

<template>
  <div class="drawer-mask" @click.self="emit('close')">
    <aside class="drawer" role="dialog" aria-modal="true" aria-label="工单详情">
      <header class="drawer-head">
        <div>
          <p class="eyebrow">工单详情</p>
          <h2 class="mono">{{ ticket.id }}</h2>
        </div>
        <button class="link" type="button" @click="emit('close')">关闭</button>
      </header>

      <p v-if="detailLoading" class="empty">正在读取最新进度…</p>
      <p v-if="detailError" class="err">{{ detailError }}</p>
      <p v-if="progressNote" class="progress" aria-live="polite">{{ progressNote }}</p>

      <div class="detail-status">
        <span class="pill">{{ STATUS_LABEL[ticket.status] ?? ticket.status }}</span>
        <span>{{ reasonLabel(ticket.reason) }}</span>
      </div>
      <p class="detail-query">{{ ticket.userQuery }}</p>

      <ol class="detail-timeline">
        <li class="done"><span>已提交</span><time>{{ formatTime(ticket.createdAt) }}</time></li>
        <li v-if="ticket.assignee" class="done"><span>坐席 {{ ticket.assignee }} 已接入</span></li>
        <li v-if="ticket.escalatedAt" class="warn">
          <span>已超时升级</span><time>{{ formatTime(ticket.escalatedAt) }}</time>
        </li>
        <li :class="{ done: ticket.status === 'RESOLVED' }">
          <span>{{ STATUS_LABEL[ticket.status] ?? ticket.status }}</span>
          <time v-if="ticket.status !== 'RESOLVED'">{{ slaText(ticket, now) }}</time>
        </li>
      </ol>

      <dl class="detail-grid">
        <div><dt>优先级</dt><dd>{{ PRIORITY_LABEL[ticket.priority ?? 'normal'] ?? ticket.priority }}</dd></div>
        <div><dt>处理人</dt><dd>{{ ticket.assignee ?? '待分配' }}</dd></div>
        <div><dt>预计时限</dt><dd>{{ formatTime(ticket.slaDeadline) || '—' }}</dd></div>
        <div><dt>渠道</dt><dd>{{ channelLabel(ticket.channel) }}</dd></div>
      </dl>

      <div v-if="ticket.status === 'RESOLVED'" class="resolution">
        <h3>处理结论</h3>
        <p v-if="ticket.resolutionNote">{{ ticket.resolutionNote }}</p>
        <p v-else class="muted">人工已处理完成；本次没有填写结单说明。</p>
        <p class="muted">结论也会按你提问的渠道回传。</p>
      </div>
      <p v-else class="detail-note">坐席正在处理，进度每 8 秒自动更新；不用再重复描述。</p>
      <button class="primary" type="button" @click="emit('refresh')">刷新进度</button>
    </aside>
  </div>
</template>

<style scoped>
.drawer-mask {
  position: fixed; inset: 0; z-index: 50;
  display: flex; justify-content: flex-end;
  background: rgba(20, 22, 26, 0.36);
}
.drawer {
  width: min(460px, 100%); height: 100%; overflow-y: auto;
  box-sizing: border-box; padding: 22px;
  background: var(--panel); box-shadow: -12px 0 40px rgba(20, 22, 26, 0.16);
}
.drawer-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.drawer-head h2 { margin: 2px 0 0; font-size: 16px; overflow-wrap: anywhere; }
.detail-status { display: flex; align-items: center; gap: 10px; margin: 18px 0 8px; color: var(--ink-3); font-size: 13px; }
.detail-query { margin: 0 0 18px; padding: 10px 12px; background: var(--bg); border-radius: 10px; overflow-wrap: anywhere; }
.detail-timeline { list-style: none; margin: 0 0 18px; padding: 0; display: grid; gap: 10px; }
.detail-timeline li { position: relative; padding-left: 18px; color: var(--ink-3); font-size: 13px; }
.detail-timeline li::before { content: ''; position: absolute; left: 0; top: 6px; width: 7px; height: 7px; border-radius: 50%; background: var(--line); }
.detail-timeline li.done::before { background: var(--teal); }
.detail-timeline li.warn { color: var(--amber-ink); }
.detail-timeline li.warn::before { background: var(--amber); }
.detail-timeline time { margin-left: 6px; color: var(--ink-3); }
.detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin: 0 0 18px; }
.detail-grid dt { color: var(--ink-3); font-size: 12px; }
.detail-grid dd { margin: 2px 0 0; font-size: 13px; overflow-wrap: anywhere; }
.detail-note { color: var(--ink-2); font-size: 13px; line-height: 1.6; }
.resolution {
  background: var(--teal-wash); border: 1px solid var(--teal-line-2); border-radius: 12px;
  padding: 12px 14px; margin: 0 0 14px;
}
.resolution h3 { margin: 0 0 6px; font-size: 13px; color: var(--teal); }
.resolution p { margin: 0 0 6px; font-size: 13px; line-height: 1.6; overflow-wrap: anywhere; }
.resolution p:last-child { margin-bottom: 0; }
.muted { color: var(--ink-3); }
.progress {
  color: var(--teal); background: var(--teal-soft); border: 1px solid var(--teal-line);
  border-radius: 10px; padding: 8px 10px; font-size: 13px; margin: 12px 0 0;
}

@media (max-width: 640px) {
  .drawer-mask { align-items: flex-end; }
  .drawer { width: 100%; height: auto; max-height: 86vh; border-radius: 16px 16px 0 0; padding: 18px; }
  .detail-grid { grid-template-columns: 1fr; }
}
</style>
