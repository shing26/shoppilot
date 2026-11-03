<script setup lang="ts">
/**
 * 退款审核视图（round44 票 188 从 `App.vue` 拆出；票 189 补决策路径与工单对应）。
 *
 * <p>只列单，决策在确认框里做。这里多画的两件事都不是新数据：
 * 决策路径是 ADR 0047 写死的口径（放行 ≠ 到账），工单对应靠退款审批单的
 * `payload.refundId`——按订单号猜会在同一单多次申请时对错。
 */
import { computed } from 'vue'
import { labelOf, REFUND_STATUS_LABEL } from 'frontend-shared'
import type { RefundItem } from '../api'
import { STATUS_LABEL, type Ticket } from '../types'
import { payloadField } from '../lib/format'

const props = defineProps<{
  refunds: RefundItem[]
  refundError: string
  refundNote: string
  refundInFlight: number
  /** 当前队列里的工单；用来把退款与 `source=REFUND_APPROVAL` 的审批单对上。 */
  refundTickets: Ticket[]
}>()

const emit = defineEmits<{ open: [refund: RefundItem, decision: 'APPROVE' | 'REJECT'] }>()

const ticketByRefund = computed(() => {
  const map = new Map<string, Ticket>()
  for (const ticket of props.refundTickets) {
    const refundId = payloadField(ticket, 'refundId')
    if (refundId) map.set(refundId, ticket)
  }
  return map
})

interface RefundRow {
  refund: RefundItem
  ticket: Ticket | null
}

const rows = computed<RefundRow[]>(() =>
  props.refunds.map((refund) => ({
    refund,
    ticket: ticketByRefund.value.get(String(refund.refundId)) ?? null,
  })),
)
</script>

<template>
  <section class="view-panel">
    <div class="pane-head">
      <h2>退款审核</h2>
      <span class="count">{{ refunds.length }}</span>
    </div>
    <p class="hint">资金动作的责任人必须是已验签账号；放行/驳回都会记进审计。</p>
    <p v-if="refundError" class="err">{{ refundError }}</p>
    <p v-if="refundNote" class="note">{{ refundNote }}</p>
    <p v-if="!refunds.length && !refundError" class="empty">当前没有待审核的退款申请</p>
    <div v-else class="refund-list">
      <article v-for="row in rows" :key="row.refund.refundId" class="refund-card">
        <div class="refund-top">
          <strong>退款 #{{ row.refund.refundId }}</strong>
          <span class="amount">{{ (row.refund.amountFen / 100).toFixed(2) }} 元</span>
        </div>
        <p class="refund-order">
          订单 <span class="mono">{{ row.refund.orderNo }}</span> · {{ row.refund.reason }}
        </p>

        <ol class="decision-path" aria-label="退款决策路径">
          <li data-state="done"><span>买家申请</span><small>已受理</small></li>
          <li data-state="current"><span>人工审核</span><small>{{ labelOf(REFUND_STATUS_LABEL, row.refund.status) }}</small></li>
          <li data-state="pending"><span>放行 / 驳回</span><small>待决策</small></li>
          <li data-state="pending"><span>到账</span><small>由支付渠道处理</small></li>
        </ol>

        <p v-if="row.ticket" class="refund-ticket">
          审批工单 <span class="mono">{{ row.ticket.id }}</span>
          · {{ STATUS_LABEL[row.ticket.status] ?? row.ticket.status }}
        </p>
        <p v-else class="fine">当前队列里没有对应的审批工单（切到「全部」队列可见）。</p>

        <div class="refund-actions">
          <button class="primary" type="button" :disabled="!!refundInFlight" @click="emit('open', row.refund, 'APPROVE')">放行</button>
          <button class="ghost" type="button" :disabled="!!refundInFlight" @click="emit('open', row.refund, 'REJECT')">驳回</button>
        </div>
      </article>
    </div>
  </section>
</template>

<style scoped>
.refund-list { display: grid; gap: 10px; }
.refund-card { border: 1px solid var(--line); border-radius: 12px; padding: 12px; background: var(--panel-2); }
.refund-top { display: flex; justify-content: space-between; gap: 12px; }
.amount { color: var(--amber); font-weight: 600; }
.refund-order { color: var(--ink-2); margin: 6px 0 10px; }
.decision-path {
  list-style: none; display: flex; gap: 6px; margin: 0 0 10px; padding: 0;
}
.decision-path li {
  flex: 1; display: grid; gap: 1px; padding-top: 9px; position: relative;
  color: var(--ink-3); font-size: 11px;
}
.decision-path li::before {
  content: ''; position: absolute; top: 0; left: 0; right: 0; height: 2px; background: var(--line);
}
.decision-path li[data-state='done'] { color: var(--ink-2); }
.decision-path li[data-state='done']::before { background: var(--teal-line); }
.decision-path li[data-state='current'] { color: var(--ink); }
.decision-path li[data-state='current']::before { background: var(--amber); }
.decision-path small { color: var(--ink-3); }
.refund-ticket { margin: 0 0 10px; font-size: 12px; color: var(--ink-2); }
.refund-actions { display: flex; gap: 8px; }
</style>
