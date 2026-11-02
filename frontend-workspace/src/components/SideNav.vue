<script setup lang="ts">
/**
 * 左侧导航（round44 票 188 从 `App.vue` 拆出）。
 *
 * <p>只发 `select`，不直接改 `view`：切换视图在根组件里带副作用（按视图懒加载），
 * 让导航自己写 `view` 会把刷新逻辑绕过去。
 */
import type { WorkspaceView } from '../lib/ui'

defineProps<{
  view: WorkspaceView
  ticketCount: number
  unread: number
  refundCount: number
  /** 知识视图的待办数：待复核反馈 + 待发布草稿（票 190）。 */
  knowledgeCount: number
  tenantId: string
}>()

const emit = defineEmits<{ select: [view: WorkspaceView] }>()
</script>

<template>
  <aside class="side-nav">
    <p class="nav-title">工作区</p>
    <button class="nav-item" :class="{ active: view === 'queue' }" type="button" @click="emit('select', 'queue')">
      <span>工单队列</span><em>{{ ticketCount }}</em>
    </button>
    <button class="nav-item" :class="{ active: view === 'conversations' }" type="button" @click="emit('select', 'conversations')">
      <span>渠道会话</span><em :class="{ 'unread-badge': unread > 0 }">{{ unread }}</em>
    </button>
    <button class="nav-item" :class="{ active: view === 'refunds' }" type="button" @click="emit('select', 'refunds')">
      <span>退款审核</span><em>{{ refundCount }}</em>
    </button>
    <button class="nav-item" :class="{ active: view === 'knowledge' }" type="button" @click="emit('select', 'knowledge')">
      <span>知识飞轮</span>
      <em :class="{ 'unread-badge': knowledgeCount > 0 }">{{ knowledgeCount }}</em>
    </button>
    <button class="nav-item" :class="{ active: view === 'quality' }" type="button" @click="emit('select', 'quality')">
      <span>编排质量</span><em aria-hidden="true">●</em>
    </button>
    <button class="nav-item" :class="{ active: view === 'channels' }" type="button" @click="emit('select', 'channels')">
      <span>渠道连接</span><em aria-hidden="true">●</em>
    </button>
    <div class="nav-foot">
      <strong>{{ tenantId }}</strong>
      <span>SLA 每 10 秒刷新</span>
    </div>
  </aside>
</template>

<style scoped>
.side-nav {
  position: sticky;
  top: 76px;
  display: grid;
  gap: 6px;
  padding: 14px 12px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 16px;
  box-shadow: 0 8px 28px rgba(20, 22, 26, .05);
}
.nav-title {
  margin: 0 4px 8px;
  color: var(--ink-3);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: .08em;
}
.nav-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  padding: 9px 10px;
  border: 1px solid transparent;
  border-radius: 10px;
  background: none;
  text-align: left;
  color: var(--ink-2);
}
.nav-item:hover:not(:disabled) { background: var(--bg); border-color: var(--line); }
.nav-item.active {
  color: var(--ink);
  background: var(--teal-wash-deep);
  border-color: var(--teal-line);
  font-weight: 600;
}
.nav-item em { font-style: normal; color: var(--ink-3); font-size: 12px; }
.nav-item.active em { color: var(--teal); }
.nav-item em.unread-badge { color: #fff; background: var(--rose); border-radius: 999px; min-width: 20px; text-align: center; }
.nav-foot {
  margin-top: 14px;
  padding: 10px;
  border-top: 1px solid var(--line-2);
  display: grid;
  gap: 2px;
}
.nav-foot strong { font-size: 13px; }
.nav-foot span { color: var(--ink-3); font-size: 12px; }

@media (max-width: 960px) {
  /* 六个视图：三列两行比一行六列好按，也不会把「渠道会话」压成两个字一行。 */
  .side-nav { position: static; grid-template-columns: repeat(3, 1fr); align-items: center; }
  .nav-title, .nav-foot { display: none; }
  .nav-item { justify-content: center; }
}

@media (max-width: 640px) {
  .side-nav { grid-template-columns: 1fr; }
}
</style>
