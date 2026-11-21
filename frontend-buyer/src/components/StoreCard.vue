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
        <span class="verified">平台认证商家</span>
        <span class="online">服务中</span>
      </div>
      <p>{{ business || '在线客服 · 查订单 / 退换 / 生鲜理赔' }}</p>
    </div>
    <p v-if="guarantees" class="guarantee">本店保障：{{ guarantees }}</p>
  </section>
</template>

<style scoped>
.store-card {
  box-sizing: border-box;
  display: flex; gap: 12px; align-items: center;
  background: var(--panel); border: 1px solid var(--line); border-radius: var(--radius-panel);
  padding: 11px 16px; margin: 14px 0;
}
.avatar {
  width: 34px; height: 34px; border-radius: 9px; background: var(--teal-wash-deep); color: var(--teal-ink);
  border: 1px solid var(--teal-line); display: grid; place-items: center;
  font-weight: 650; font-size: 14px; flex: none;
}
.store-main { min-width: 0; flex: 1; }
.store-title { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.store-title strong { font-size: 14px; letter-spacing: -0.01em; }
.store-main p { margin: 2px 0 0; color: var(--ink-3); font-size: 12px; }
.verified {
  color: var(--teal-ink); font-size: 12px;
}
.guarantee {
  margin: 0; color: var(--ink-3);
  font-size: 12px; max-width: 42%;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.online { color: var(--green); font-size: 12px; white-space: nowrap; }
.online::before {
  content: '';
  display: inline-block;
  width: 6px; height: 6px;
  margin-right: 5px;
  border-radius: 50%;
  background: var(--green);
  vertical-align: 1px;
}

@media (max-width: 640px) {
  .store-card { padding: 10px 12px; margin: 12px 0; }
  .guarantee { display: none; }
}
</style>
