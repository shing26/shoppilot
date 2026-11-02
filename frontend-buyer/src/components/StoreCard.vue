<script setup lang="ts">
/** 店铺卡（round44 票 188 从 `App.vue` 拆出）：这是哪家店、能帮什么。 */
import { computed } from 'vue'

const props = defineProps<{
  label: string
  business: string
  guarantees: string
}>()

const initial = computed(() => props.label.slice(0, 1))
</script>

<template>
  <section class="store-card">
    <span class="avatar">{{ initial }}</span>
    <div class="store-main">
      <div class="store-title">
        <strong>{{ label }}</strong>
        <span class="online">在线</span>
      </div>
      <p>{{ business || '在线客服 · 查订单 / 退换 / 生鲜理赔' }}</p>
      <p v-if="guarantees" class="guarantee">本店保障：{{ guarantees }}</p>
    </div>
  </section>
</template>

<style scoped>
.store-card {
  box-sizing: border-box;
  display: flex; gap: 12px; align-items: flex-start;
  background: var(--panel); border: 1px solid var(--line); border-radius: 16px;
  padding: 16px; margin: 4px 0 18px;
}
.avatar {
  width: 42px; height: 42px; border-radius: 12px; background: var(--teal); color: #fff;
  display: grid; place-items: center; font-weight: 650; flex: none;
}
.store-main { min-width: 0; }
.store-title { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.store-title strong { font-size: 16px; }
.store-main p { margin: 4px 0 0; color: var(--ink-2); font-size: 13px; }
.guarantee { color: var(--ink-3) !important; }
.online { color: var(--green); font-size: 12px; white-space: nowrap; }
.online::before { content: '●'; margin-right: 4px; }

@media (max-width: 640px) {
  .store-card { padding: 12px; border-radius: 12px; margin-bottom: 14px; }
}
</style>
