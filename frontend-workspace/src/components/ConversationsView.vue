<script setup lang="ts">
/**
 * 渠道会话视图（round44 票 188 从 `App.vue` 拆出）。
 *
 * <p>SSE 流、通知权限、提示音与 push 订阅都还在根组件里：它们是**页面级副作用**
 * （一个 SSE 连接、一个 AudioContext），不是这个视图的局部状态。这里只画与发事件。
 */
import type { ChannelConversation } from '../api'
import { formatTime } from '../lib/format'

const reply = defineModel<string>('reply', { required: true })

defineProps<{
  conversations: ChannelConversation[]
  selectedConversation: ChannelConversation | null
  selectedConversationId: string
  conversationLoading: boolean
  conversationError: string
  conversationNote: string
  notificationError: string
  notificationsEnabled: boolean
  soundEnabled: boolean
  conversationInFlight: string
}>()

const emit = defineEmits<{
  refresh: []
  toggleNotifications: []
  toggleSound: []
  select: [id: string]
  takeover: []
  release: []
  reply: []
}>()
</script>

<template>
  <section class="view-panel conversation-panel">
    <div class="pane-head">
      <h2>渠道会话</h2>
      <div class="channel-head-actions">
        <button class="ghost" type="button" @click="emit('toggleNotifications')">
          {{ notificationsEnabled ? '通知已开启' : '开启浏览器通知' }}
        </button>
        <button class="ghost" type="button" @click="emit('toggleSound')">
          {{ soundEnabled ? '提示音已开' : '开启提示音' }}
        </button>
        <button class="ghost" type="button" :disabled="conversationLoading" @click="emit('refresh')">
          {{ conversationLoading ? '读取中…' : '刷新会话' }}
        </button>
      </div>
    </div>
    <p class="hint">飞书会话收件箱。接管后 Agent 不再自动回复，坐席回复会直接发回飞书；新消息走 SSE 实时推送。</p>
    <p v-if="notificationError" class="err">{{ notificationError }}</p>
    <p v-if="conversationError" class="err">{{ conversationError }}</p>
    <p v-if="conversationNote" class="note">{{ conversationNote }}</p>
    <p v-if="conversationLoading" class="empty">正在读取渠道会话…</p>

    <div v-else class="conversation-layout">
      <section class="conversation-list">
        <p v-if="!conversations.length" class="empty">还没有渠道会话；飞书来消息后会出现在这里。</p>
        <button
          v-for="c in conversations"
          :key="c.conversationId"
          class="conversation-card"
          :class="{ active: selectedConversationId === c.conversationId }"
          type="button"
          @click="emit('select', c.conversationId)"
        >
          <div class="conversation-top">
            <span class="conversation-channel">{{ c.channel }}</span>
            <span class="conversation-tags">
              <span v-if="c.unread" class="unread-pill">{{ c.unread }}</span>
              <span class="status-pill" :class="{ agent: c.status === 'AGENT' }">{{ c.status }}</span>
            </span>
          </div>
          <p class="conversation-last">{{ c.lastMessage || '（暂无消息）' }}</p>
          <div class="conversation-meta">
            <span>{{ c.customerId }}</span>
            <time>{{ formatTime(c.lastAt) }}</time>
          </div>
          <p v-if="c.assignee" class="conversation-owner">坐席 {{ c.assignee }}</p>
        </button>
      </section>

      <aside class="conversation-detail">
        <template v-if="selectedConversation">
          <header class="conversation-detail-head">
            <div>
              <p class="eyebrow">{{ selectedConversation.channel }} · {{ selectedConversation.customerId }}</p>
              <h2 class="mono">{{ selectedConversation.conversationId }}</h2>
            </div>
            <span class="status-pill" :class="{ agent: selectedConversation.status === 'AGENT' }">
              {{ selectedConversation.status === 'AGENT' ? '坐席接管' : '智能助手' }}
            </span>
          </header>

          <div class="conversation-actions">
            <button
              v-if="selectedConversation.status !== 'AGENT'"
              class="primary"
              type="button"
              :disabled="!!conversationInFlight"
              @click="emit('takeover')"
            >
              {{ conversationInFlight.endsWith(':takeover') ? '接管中…' : '接管会话' }}
            </button>
            <button
              v-else
              class="ghost"
              type="button"
              :disabled="!!conversationInFlight"
              @click="emit('release')"
            >
              {{ conversationInFlight.endsWith(':release') ? '释放中…' : '释放回助手' }}
            </button>
            <button class="ghost" type="button" :disabled="conversationLoading" @click="emit('refresh')">刷新</button>
          </div>

          <ol class="conversation-messages">
            <li v-for="(m, index) in selectedConversation.messages" :key="index" :class="m.role">
              <div class="message-head">
                <span>{{ m.role === 'user' ? '买家' : m.role === 'agent' ? '坐席' : '助手' }}</span>
                <time>{{ formatTime(m.at) }}</time>
              </div>
              <p>{{ m.text }}</p>
            </li>
          </ol>

          <div v-if="selectedConversation.status === 'AGENT'" class="conversation-reply">
            <textarea v-model="reply" rows="3" placeholder="回复会直接发到飞书…"></textarea>
            <button class="primary" type="button" :disabled="!!conversationInFlight" @click="emit('reply')">
              {{ conversationInFlight.endsWith(':reply') ? '发送中…' : '发送到飞书' }}
            </button>
          </div>
          <p v-else class="fine">接管后可在这里回复；当前由智能助手自动应答。</p>
        </template>
        <p v-else class="empty">选择左侧一个会话查看详情</p>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.channel-head-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.conversation-layout { display: grid; grid-template-columns: 320px minmax(0, 1fr); gap: 14px; align-items: start; }
.conversation-list { display: grid; gap: 8px; }
.conversation-card { text-align: left; border: 1px solid var(--line); border-radius: 12px; background: var(--panel);
  padding: 10px 12px; cursor: pointer; font: inherit; }
.conversation-card:hover { border-color: var(--teal-line); }
.conversation-card.active { border-color: var(--teal); box-shadow: 0 0 0 1px var(--teal) inset; background: var(--panel-active); }
.conversation-top { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.conversation-channel { color: var(--ink-3); font-size: 12px; text-transform: uppercase; letter-spacing: .04em; }
.conversation-last { margin: 6px 0; color: var(--ink-2); font-size: 13px; line-height: 1.45; overflow-wrap: anywhere;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.conversation-meta { display: flex; justify-content: space-between; gap: 8px; color: var(--ink-3); font-size: 12px; }
.conversation-owner { margin: 6px 0 0; color: var(--amber-ink); font-size: 12px; }
.conversation-detail { border: 1px solid var(--line); border-radius: 12px; background: var(--panel); padding: 14px; }
.conversation-detail-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.conversation-detail-head h2 { margin: 2px 0 0; font-size: 13px; }
.conversation-actions { display: flex; gap: 8px; flex-wrap: wrap; margin: 12px 0; }
.conversation-messages { list-style: none; margin: 0; padding: 10px 0 0; display: grid; gap: 8px;
  border-top: 1px solid var(--line-2); max-height: 360px; overflow: auto; }
.conversation-messages li { padding: 8px 10px; border-radius: 10px; background: var(--bg); max-width: 86%; }
.conversation-messages li.user { justify-self: end; background: var(--teal-soft); }
.conversation-messages li.agent { background: var(--amber-soft); }
.message-head { display: flex; gap: 8px; justify-content: space-between; color: var(--ink-3); font-size: 11px; margin-bottom: 3px; }
.conversation-messages p { margin: 0; font-size: 13px; line-height: 1.55; overflow-wrap: anywhere; }
.conversation-reply { margin-top: 12px; border-top: 1px solid var(--line-2); padding-top: 12px; }
.conversation-reply textarea { width: 100%; box-sizing: border-box; padding: 8px 10px; border: 1px solid var(--line);
  border-radius: 8px; font: inherit; resize: vertical; margin-bottom: 8px; }
.conversation-reply textarea:focus { outline: none; border-color: var(--teal); box-shadow: 0 0 0 3px var(--teal-soft); }
.conversation-tags { display: flex; align-items: center; gap: 6px; }
.unread-pill { min-width: 18px; padding: 0 6px; border-radius: 999px; background: var(--rose); color: #fff;
  font-size: 11px; line-height: 18px; text-align: center; }

@media (max-width: 960px) {
  .conversation-layout { grid-template-columns: 1fr; }
}
</style>
