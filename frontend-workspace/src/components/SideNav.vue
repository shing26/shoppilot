<script setup lang="ts">
/**
 * 左侧导航（round44 票 188 从 `App.vue` 拆出）。
 *
 * <p>只发 `select`，不直接改 `view`：切换视图在根组件里带副作用（按视图懒加载），
 * 让导航自己写 `view` 会把刷新逻辑绕过去。
 */
import type { WorkspaceView } from '../lib/ui'
import type { Capability } from '../api'
import { hasCapability } from '../lib/nav'
import { computed } from 'vue'

const props = defineProps<{
  view: WorkspaceView
  ticketCount: number
  unread: number
  refundCount: number
  /** 知识视图的待办数：待复核反馈 + 待发布草稿（票 190）。 */
  knowledgeCount: number
  tenantId: string
  /** 后端派生的能力；侧栏只画有能力的入口，真正的边界在后端。 */
  capabilities: string[]
  /** 令牌里的角色；侧栏只做身份标识。 */
  role: string
}>()

const emit = defineEmits<{ select: [view: WorkspaceView] }>()

const roleLabel = computed(() => (props.role === 'ADMIN' ? '管理员' : props.role === 'AGENT' ? '坐席' : props.role))
const can = (capability: Capability) => hasCapability(props.capabilities, capability)
</script>

<template>
  <aside class="side-nav">
    <p v-if="can('tickets') || can('conversations') || can('refunds')" class="nav-title">工作</p>
    <button v-if="can('tickets')" class="nav-item" :class="{ active: view === 'queue' }" type="button" @click="emit('select', 'queue')">
      <span>工单队列</span><em>{{ ticketCount }}</em>
    </button>
    <button v-if="can('conversations')" class="nav-item" :class="{ active: view === 'conversations' }" type="button" @click="emit('select', 'conversations')">
      <span>渠道会话</span><em :class="{ 'unread-badge': unread > 0 }">{{ unread }}</em>
    </button>
    <button v-if="can('refunds')" class="nav-item" :class="{ active: view === 'refunds' }" type="button" @click="emit('select', 'refunds')">
      <span>退款审核</span><em>{{ refundCount }}</em>
    </button>
    <p v-if="can('knowledge') || can('quality') || can('channels')" class="nav-title nav-title-2">知识与观测</p>
    <button v-if="can('knowledge')" class="nav-item" :class="{ active: view === 'knowledge' }" type="button" @click="emit('select', 'knowledge')">
      <span>知识飞轮</span>
      <em :class="{ 'unread-badge': knowledgeCount > 0 }">{{ knowledgeCount }}</em>
    </button>
    <button v-if="can('quality')" class="nav-item" :class="{ active: view === 'quality' }" type="button" @click="emit('select', 'quality')">
      <span>编排质量</span><em aria-hidden="true">●</em>
    </button>
    <button v-if="can('channels')" class="nav-item" :class="{ active: view === 'channels' }" type="button" @click="emit('select', 'channels')">
      <span>渠道连接</span><em aria-hidden="true">●</em>
    </button>
    <div class="nav-foot">
      <strong>{{ tenantId }}</strong>
      <span>{{ roleLabel }} · SLA 每 10 秒刷新</span>
    </div>
  </aside>
</template>

<style scoped>
.side-nav {
  height: 100%;
  display: grid;
  align-content: start;
  gap: 2px;
  padding: 20px 12px;
  background: var(--chrome);
  border-right: 1px solid var(--chrome-line);
}
.nav-title {
  margin: 0 10px 10px;
  color: var(--chrome-muted);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: .1em;
  text-transform: uppercase;
}
.nav-title-2 { margin-top: 18px; }
.nav-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  padding: 9px 10px;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  background: none;
  text-align: left;
  color: var(--chrome-muted);
  font-size: 13px;
}
.nav-item:hover:not(:disabled) {
  background: var(--chrome-2);
  border-color: var(--chrome-line);
  color: var(--chrome-ink);
}
.nav-item.active {
  color: var(--chrome-ink);
  background: var(--chrome-2);
  border-color: var(--chrome-line);
  font-weight: 600;
  box-shadow: inset 2px 0 0 var(--teal);
}
.nav-item em { font-style: normal; color: var(--chrome-muted); font-size: 11px; }
.nav-item.active em { color: var(--chrome-ink); }
.nav-item em.unread-badge { color: #fff; background: var(--rose); border-radius: var(--radius-full); min-width: 20px; text-align: center; }
.nav-foot {
  margin-top: 20px;
  padding: 14px 10px 0;
  border-top: 1px solid var(--chrome-line);
  display: grid;
  gap: 2px;
}
.nav-foot strong { font-size: 12px; color: var(--chrome-ink); }
.nav-foot span { color: var(--chrome-muted); font-size: 11px; }

@media (max-width: 960px) {
  /* 最多六个视图：三列两行比一行六列好按，也不会把「渠道会话」压成两个字一行。 */
  .side-nav {
    height: auto;
    grid-template-columns: repeat(3, 1fr);
    align-items: center;
    border-right: none;
    border-bottom: 1px solid var(--chrome-line);
    padding: 8px 12px;
  }
  .nav-title, .nav-title-2, .nav-foot { display: none; }
  .nav-item { justify-content: center; }
  .nav-item.active { box-shadow: inset 0 -2px 0 var(--teal); }
}

@media (max-width: 640px) {
  /* 单列会把队列推到首屏之外（round47 票 197）：沿用三列两行，只把触屏高度补足。 */
  .side-nav { grid-template-columns: repeat(3, 1fr); }
  .nav-item { min-height: 40px; }
}
</style>
