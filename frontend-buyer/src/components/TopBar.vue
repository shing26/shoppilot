<script setup lang="ts">
/** 顶栏（round44 票 188 从 `App.vue` 拆出）：品牌、身份徽标、退出。 */
import { computed } from 'vue'

const props = defineProps<{
  demoMode: boolean
  guestMode: boolean
  hasSession: boolean
  /** 当前登录账号；署名取自令牌，页面上改不了（round44 票 190 补回这一格）。 */
  who: string
  /** 店铺名；买家顶栏先回答「我在哪家店」。 */
  storeLabel: string
  /** 本次订单 + 工单数；移动端抽屉入口用它说明还有多少上下文。 */
  activityCount: number
}>()

const emit = defineEmits<{ signOut: []; openActivity: [] }>()

const identityLabel = computed(() => {
  if (props.demoMode) return '体验买家'
  if (props.guestMode) return '访客'
  return props.who || '买家'
})
</script>

<template>
  <header class="topbar">
    <a class="brand" href="/" aria-label="返回 ShopPilot 入口">
      <span class="logo">SP</span>
      <div>
        <strong>{{ storeLabel }}</strong>
        <small>ShopPilot · 买家服务</small>
      </div>
    </a>
    <div class="top-right">
      <span v-if="hasSession" class="who" :title="'当前身份：' + identityLabel">{{ identityLabel }}</span>
      <button
        v-if="hasSession"
        class="activity-trigger"
        type="button"
        aria-label="打开订单与进度"
        @click="emit('openActivity')"
      >
        <span>订单与进度</span>
        <em v-if="activityCount">{{ activityCount }}</em>
      </button>
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
  height: 60px;
  margin: 0 -24px;
  padding: 0 24px;
  background: var(--panel);
  border-bottom: 1px solid var(--line);
  backdrop-filter: none;
}
.brand { display: flex; align-items: center; gap: 10px; color: inherit; text-decoration: none; }
.logo {
  width: 30px; height: 30px; border-radius: 8px; background: var(--teal); color: #fff;
  display: grid; place-items: center; font-size: 12px; font-weight: 650; letter-spacing: 0.3px;
}
.brand strong { display: block; font-size: 15px; line-height: 1.25; letter-spacing: -0.01em; }
.brand small { color: var(--ink-3); font-size: 12px; }
.top-right { display: flex; align-items: center; gap: 10px; }
.who {
  font-size: 13px; color: var(--ink-2); max-width: 180px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  padding-right: 10px; border-right: 1px solid var(--line);
}
.activity-trigger {
  display: none;
  align-items: center;
  gap: 7px;
  min-height: 36px;
  padding: 0 10px;
  color: var(--ink-2);
  background: var(--panel);
  border-color: var(--line);
}
.activity-trigger em {
  min-width: 18px;
  padding: 0 5px;
  border-radius: var(--radius-xs);
  background: var(--teal-soft);
  color: var(--teal-ink);
  font-size: 12px;
  font-style: normal;
  line-height: 18px;
  font-variant-numeric: tabular-nums;
}

@media (max-width: 960px) {
  .activity-trigger { display: inline-flex; }
}

@media (max-width: 640px) {
  .topbar { height: 56px; margin: 0 -12px; padding: 0 12px; }
  .brand { gap: 8px; }
  .brand small { display: none; }
  .who { display: none; }
  .top-right { gap: 6px; }
  /* 触屏目标 ≥40px（round47 票 196）；桌面密度不变。 */
  .topbar .link {
    display: inline-flex; align-items: center; justify-content: center;
    min-height: 40px; min-width: 44px; padding: 0 10px;
  }
}
</style>
