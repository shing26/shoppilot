<script setup lang="ts">
/** 顶栏（round44 票 188 从 `App.vue` 拆出）：身份与队列刷新。 */
import { computed } from 'vue'

const props = defineProps<{
  who: string
  hasSession: boolean
  /** 登录令牌里的角色；界面只做标识，不据此隐藏后端仍允许的入口。 */
  role: string
  tenantId: string
}>()

const emit = defineEmits<{ refresh: []; signOut: [] }>()

const roleLabel = computed(() => {
  if (props.role === 'ADMIN') return '管理员'
  if (props.role === 'AGENT') return '坐席'
  return props.role || '坐席'
})
</script>

<template>
  <header class="topbar">
    <a class="brand" href="/" aria-label="返回 ShopPilot 入口">
      <span class="logo">SP</span>
      <div>
        <strong>ShopPilot</strong>
        <small>坐席工作台</small>
      </div>
    </a>
    <div v-if="hasSession" class="top-right">
      <span v-if="who" class="identity">{{ who }}</span>
      <span class="role">{{ roleLabel }}<template v-if="tenantId"> · 店铺 {{ tenantId }}</template></span>
      <button class="ghost" type="button" @click="emit('refresh')">刷新队列</button>
      <button class="link" type="button" @click="emit('signOut')">退出</button>
    </div>
  </header>
</template>

<style scoped>
.topbar {
  position: sticky; top: 0; z-index: 20;
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  height: 56px;
  flex: none;
  padding: 0 20px;
  background: var(--chrome);
  border-bottom: 1px solid var(--chrome-line);
}
.brand { display: flex; align-items: center; gap: 10px; color: inherit; text-decoration: none; }
.logo { width: 28px; height: 28px; border-radius: 7px; background: var(--teal); color: #fff;
  display: grid; place-items: center; font-size: 12px; font-weight: 700; }
.brand strong { display: block; font-size: 14px; line-height: 1.2; color: var(--chrome-ink); letter-spacing: -0.01em; }
.brand small { color: var(--chrome-muted); font-size: 12px; }
.top-right { display: flex; align-items: center; gap: 12px; }
.identity {
  color: var(--chrome-muted);
  font-size: 12px;
  padding-right: 12px;
  border-right: 1px solid var(--chrome-line);
}
.role {
  color: var(--chrome-ink);
  font-size: 12px;
  border: 1px solid var(--chrome-line);
  border-radius: var(--radius-full);
  padding: 1px 8px;
  white-space: nowrap;
}
.topbar .ghost {
  background: transparent;
  border-color: var(--chrome-line);
  color: var(--chrome-ink);
}
.topbar .ghost:hover:not(:disabled) { border-color: var(--chrome-muted); }
.topbar .link { color: var(--chrome-muted); text-decoration: none; }
.topbar .link:hover:not(:disabled) { color: var(--chrome-ink); }

@media (max-width: 640px) {
  .topbar { padding: 0 12px; }
  .top-right .identity { display: none; }
  .top-right .role { display: none; }
  /* 触屏目标 ≥40px（round47 票 197）；桌面密度不变。 */
  .top-right .ghost,
  .top-right .link {
    display: inline-flex; align-items: center; justify-content: center;
    min-height: 40px; padding: 0 12px;
  }
}
</style>
