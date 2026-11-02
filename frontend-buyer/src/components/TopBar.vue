<script setup lang="ts">
/** 顶栏（round44 票 188 从 `App.vue` 拆出）：品牌、身份徽标、退出。 */
defineProps<{
  demoMode: boolean
  guestMode: boolean
  hasSession: boolean
}>()

const emit = defineEmits<{ signOut: [] }>()
</script>

<template>
  <header class="topbar">
    <div class="brand">
      <span class="logo">SP</span>
      <div>
        <strong>ShopPilot</strong>
        <small>买家服务</small>
      </div>
    </div>
    <div class="top-right">
      <span v-if="demoMode" class="badge">体验买家</span>
      <span v-else-if="guestMode" class="badge">访客</span>
      <button v-if="hasSession" class="link" type="button" @click="emit('signOut')">退出</button>
    </div>
  </header>
</template>

<style scoped>
.topbar {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 0;
  background: rgba(242, 243, 245, 0.92);
  backdrop-filter: blur(10px);
}
.brand { display: flex; align-items: center; gap: 10px; }
.logo {
  width: 32px; height: 32px; border-radius: 10px; background: var(--teal); color: #fff;
  display: grid; place-items: center; font-size: 12px; font-weight: 700; letter-spacing: 0.5px;
}
.brand strong { display: block; font-size: 15px; line-height: 1.2; }
.brand small { color: var(--ink-3); font-size: 12px; }
.top-right { display: flex; align-items: center; gap: 10px; }
.badge {
  font-size: 12px; color: var(--teal); border: 1px solid var(--teal-line); background: var(--teal-soft);
  border-radius: 999px; padding: 1px 8px; white-space: nowrap;
}

@media (max-width: 640px) {
  .topbar { padding: 10px 0; }
}
</style>
