<script setup lang="ts">
/**
 * 渠道连接视图（round44 票 188 从 `App.vue` 拆出）。
 *
 * <p>`channelForm` 按引用传入并就地编辑：它是根组件里的一棵小树，六个字段各自
 * 定义 model 只会让模板更难读；这里没有跨组件共享，改的是同一对象。
 */
import type { ChannelConnection, ChannelProbe } from '../api'
import type { ChannelForm } from '../lib/ui'

defineProps<{
  channel: ChannelConnection | null
  channelForm: ChannelForm
  channelLoading: boolean
  channelError: string
  channelNote: string
  channelSaving: boolean
  channelTesting: boolean
  channelProbe: ChannelProbe | null
}>()

const emit = defineEmits<{ refresh: []; save: []; test: [] }>()
</script>

<template>
  <section class="view-panel channel-panel">
    <div class="pane-head">
      <h2>渠道连接</h2>
      <button class="ghost" type="button" :disabled="channelLoading" @click="emit('refresh')">
        {{ channelLoading ? '读取中…' : '刷新状态' }}
      </button>
    </div>
    <p class="hint">飞书单连接。保存只写配置并标记重启，不会在进程内热切长连接；密钥只存本机文件，页面不回显。</p>

    <p v-if="channelError" class="err">{{ channelError }}</p>
    <p v-if="channelNote" class="note">{{ channelNote }}</p>

    <article v-if="channel" class="channel-card">
      <header class="channel-head">
        <div>
          <p class="eyebrow">飞书 Long Connection</p>
          <h3>{{ channel.enabled ? '长连接已启用' : '长连接已停用' }}</h3>
        </div>
        <span class="status-pill" :class="{ off: !channel.enabled }">
          {{ channel.enabled ? 'ENABLED' : 'DISABLED' }}
        </span>
      </header>
      <div class="channel-grid">
        <div><span>appId</span><strong class="mono">{{ channel.appId }}</strong></div>
        <div><span>租户</span><strong>{{ channel.tenant }}</strong></div>
        <div><span>默认买家</span><strong>{{ channel.customer }}</strong></div>
        <div><span>配置来源</span><strong>{{ channel.source === 'file' ? '文件' : '环境变量' }}</strong></div>
      </div>
      <p v-if="channel.restartRequired" class="channel-warn">配置已保存，需重启网关后生效。</p>
    </article>

    <form class="channel-form" @submit.prevent="emit('save')">
      <div class="pane-head"><h2>连接配置</h2><span class="count">写文件</span></div>
      <p class="fine">appId / appSecret 留空表示沿用当前值；测试凭据会调用飞书 token 接口，不会保存。</p>
      <label class="channel-check">
        <input v-model="channelForm.enabled" type="checkbox" />
        <span>启用飞书长连接</span>
      </label>
      <div class="channel-form-grid">
        <label>appId<input v-model="channelForm.appId" type="text" placeholder="留空沿用当前" autocomplete="off" /></label>
        <label>appSecret<input v-model="channelForm.appSecret" type="password" placeholder="留空沿用当前" autocomplete="new-password" /></label>
        <label>租户<input v-model="channelForm.tenant" type="text" placeholder="T001" /></label>
        <label>默认买家<input v-model="channelForm.customer" type="text" placeholder="C001" /></label>
        <label class="wide">身份映射文件<input v-model="channelForm.identityFile" type="text" placeholder="可空：config/feishu-identities.json" /></label>
      </div>
      <div class="channel-actions">
        <button class="primary" type="submit" :disabled="channelSaving">
          {{ channelSaving ? '保存中…' : '保存并标记重启' }}
        </button>
        <button class="ghost" type="button" :disabled="channelTesting" @click="emit('test')">
          {{ channelTesting ? '测试中…' : '测试凭据' }}
        </button>
      </div>
      <p v-if="channelProbe" class="note" :class="{ err: !channelProbe.ok }">{{ channelProbe.message }}</p>
    </form>
  </section>
</template>

<style scoped>
.channel-card { border: 1px solid var(--line); border-radius: 12px; padding: 14px; background: var(--panel-2); }
.channel-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.channel-head h3 { margin: 2px 0 0; font-size: 15px; }
.channel-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-top: 14px; }
.channel-grid div { display: grid; gap: 3px; min-width: 0; }
.channel-grid span { color: var(--ink-3); font-size: 12px; }
.channel-grid strong { font-size: 13px; overflow-wrap: anywhere; }
.channel-warn { margin: 12px 0 0; padding: 7px 10px; border: 1px solid var(--amber-line); border-radius: 8px;
  background: var(--amber-soft); color: var(--amber-ink); font-size: 12px; }
.channel-form { margin-top: 16px; border: 1px solid var(--line); border-radius: 12px; padding: 14px; background: var(--panel); }
.channel-form .pane-head { margin-bottom: 6px; }
.channel-check { display: flex; align-items: center; gap: 8px; margin: 10px 0 12px; color: var(--ink-2); font-size: 13px; }
.channel-check input { width: 16px; height: 16px; accent-color: var(--teal); }
.channel-form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.channel-form-grid label { display: grid; gap: 5px; color: var(--ink-2); font-size: 12px; }
.channel-form-grid label.wide { grid-column: 1 / -1; }
.channel-form-grid input { width: 100%; box-sizing: border-box; padding: 8px 10px; border: 1px solid var(--line);
  border-radius: 8px; font: inherit; background: #fff; }
.channel-form-grid input:focus { outline: none; border-color: var(--teal); box-shadow: 0 0 0 3px var(--teal-soft); }
.channel-actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 14px; }

@media (max-width: 960px) {
  .channel-grid { grid-template-columns: repeat(2, 1fr); }
}

@media (max-width: 640px) {
  .channel-grid, .channel-form-grid { grid-template-columns: 1fr; }
}
</style>
