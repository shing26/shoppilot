<script setup lang="ts">
/** 退款审核视图（round44 票 188 从 `App.vue` 拆出）：只列单，决策在确认框里做。 */
import type { RefundItem } from '../api'

defineProps<{
  refunds: RefundItem[]
  refundError: string
  refundNote: string
  refundInFlight: number
}>()

const emit = defineEmits<{ open: [refund: RefundItem, decision: 'APPROVE' | 'REJECT'] }>()
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
      <article v-for="r in refunds" :key="r.refundId" class="refund-card">
        <div class="refund-top">
          <strong>退款 #{{ r.refundId }}</strong>
          <span class="amount">{{ (r.amountFen / 100).toFixed(2) }} 元</span>
        </div>
        <p>订单 {{ r.orderNo }} · {{ r.reason }}</p>
        <div class="refund-actions">
          <button class="primary" type="button" :disabled="!!refundInFlight" @click="emit('open', r, 'APPROVE')">放行</button>
          <button class="ghost" type="button" :disabled="!!refundInFlight" @click="emit('open', r, 'REJECT')">驳回</button>
        </div>
      </article>
    </div>
  </section>
</template>

<style scoped>
.refund-list { display: grid; gap: 10px; }
.refund-card { border: 1px solid var(--line); border-radius: 12px; padding: 12px; }
.refund-top { display: flex; justify-content: space-between; gap: 12px; }
.amount { color: var(--amber); font-weight: 600; }
.refund-card p { color: var(--ink-2); margin: 6px 0 10px; }
.refund-actions { display: flex; gap: 8px; }
</style>
