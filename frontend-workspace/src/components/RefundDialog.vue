<script setup lang="ts">
/**
 * 退款确认框（round44 票 188 从 `App.vue` 拆出）。
 *
 * <p>资金动作不可逆，金额与订单号必须一眼可见——这个框存在的理由就是把它们摆出来，
 * 而不是用浏览器原生 `confirm` 问一句「确定吗」。
 */
import type { RefundItem } from '../api'

const note = defineModel<string>('note', { required: true })

defineProps<{
  target: RefundItem
  decision: 'APPROVE' | 'REJECT'
  error: string
  inFlight: number
}>()

const emit = defineEmits<{ close: []; confirm: [] }>()
</script>

<template>
  <div class="scrim" @click.self="emit('close')">
    <section class="dialog" role="dialog" aria-modal="true" aria-labelledby="refund-title">
      <h2 id="refund-title">{{ decision === 'APPROVE' ? '放行退款' : '驳回退款' }}</h2>
      <p class="dialog-sub">
        退款 #{{ target.refundId }} · 订单 {{ target.orderNo }} ·
        <strong>{{ (target.amountFen / 100).toFixed(2) }} 元</strong>
      </p>
      <p class="dialog-warn">这是不可逆的资金动作，责任人是当前登录账号；操作会记进审计。</p>
      <label class="field">
        备注（可选）
        <textarea v-model="note" rows="3" placeholder="例如：已核对订单与凭证" />
      </label>
      <p v-if="error" class="err">{{ error }}</p>
      <div class="dialog-actions">
        <button class="ghost" type="button" :disabled="!!inFlight" @click="emit('close')">取消</button>
        <button class="primary" type="button" :disabled="!!inFlight" @click="emit('confirm')">
          {{ inFlight ? '正在提交…' : (decision === 'APPROVE' ? '确认放行' : '确认驳回') }}
        </button>
      </div>
    </section>
  </div>
</template>
