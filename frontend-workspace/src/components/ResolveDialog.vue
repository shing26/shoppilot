<script setup lang="ts">
/**
 * 结单确认框（round44 票 188 从 `App.vue` 拆出）。
 *
 * <p>处理结论是必填且会随结果回流给买家，所以校验留在根组件（`confirmResolve`），
 * 这里只负责画与发 `confirm`。
 */
import type { Ticket } from '../types'

const note = defineModel<string>('note', { required: true })

defineProps<{
  target: Ticket
  error: string
  inFlight: string
}>()

const emit = defineEmits<{ close: []; confirm: [] }>()
</script>

<template>
  <div class="scrim" @click.self="emit('close')">
    <section class="dialog" role="dialog" aria-modal="true" aria-labelledby="resolve-title">
      <h2 id="resolve-title">处理完成</h2>
      <p class="dialog-sub">工单 <span class="mono">{{ target.id }}</span> · {{ target.userQuery }}</p>
      <label class="field">
        处理结论（必填，会显示在买家工单详情并随结果回流）
        <textarea v-model="note" rows="4" placeholder="例如：已核实商品破损，按实付金额退款，3 个工作日内到账" />
      </label>
      <p v-if="error" class="err">{{ error }}</p>
      <p class="fine">结单后不能再流转；操作会记进审计，责任人是当前登录账号。</p>
      <div class="dialog-actions">
        <button class="ghost" type="button" :disabled="!!inFlight" @click="emit('close')">取消</button>
        <button class="primary" type="button" :disabled="!!inFlight" @click="emit('confirm')">
          {{ inFlight ? '正在提交…' : '确认结单' }}
        </button>
      </div>
    </section>
  </div>
</template>
