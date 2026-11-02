<script setup lang="ts">
/** 顶栏（round44 票 188 从 `App.vue` 拆出）：身份与队列刷新。 */
defineProps<{
  who: string
  hasSession: boolean
}>()

const emit = defineEmits<{ refresh: []; signOut: [] }>()
</script>

<template>
  <header class="topbar">
    <div class="brand">
      <span class="logo">SP</span>
      <div>
        <strong>ShopPilot</strong>
        <small>坐席工作台</small>
      </div>
    </div>
    <div v-if="hasSession" class="top-right">
      <span class="identity">{{ who }}</span>
      <button class="ghost" type="button" @click="emit('refresh')">刷新队列</button>
      <button class="link" type="button" @click="emit('signOut')">退出</button>
    </div>
  </header>
</template>

<style scoped>
.topbar {
  position: sticky; top: 0; z-index: 20;
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  padding: 14px 0; background: rgba(242, 243, 245, .94);
  backdrop-filter: blur(10px);
}
.brand { display: flex; align-items: center; gap: 10px; }
.logo { width: 32px; height: 32px; border-radius: 10px; background: var(--teal); color: #fff;
  display: grid; place-items: center; font-size: 12px; font-weight: 700; }
.brand strong { display: block; font-size: 15px; line-height: 1.2; }
.brand small { color: var(--ink-3); font-size: 12px; }
.top-right { display: flex; align-items: center; gap: 12px; }
.identity { color: var(--ink-2); font-size: 13px; }

@media (max-width: 640px) {
  .topbar { padding: 10px 0; }
  .top-right .identity { display: none; }
}
</style>
