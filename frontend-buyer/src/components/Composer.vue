<script setup lang="ts">
/** 输入框与发送/停止（round44 票 188 从 `App.vue` 拆出）。 */
const query = defineModel<string>('query', { required: true })

defineProps<{ asking: boolean }>()

const emit = defineEmits<{ submit: []; stop: [] }>()

/**
 * 回车发送，但**输入法组合中的回车不算**（round46 票 192）。
 *
 * <p>中文/日文输入法用回车确认候选词，`keyup.enter` 在那时也会触发——用户只是想选词，
 * 消息却已经飞出去了。`isComposing` 是标准信号，keyCode 229 补老浏览器。
 */
function onEnter(event: KeyboardEvent) {
  if (event.isComposing || event.keyCode === 229) return
  emit('submit')
}
</script>

<template>
  <section class="composer">
    <div class="composer-box">
      <input
        v-model="query"
        type="text"
        placeholder="输入问题，例如：订单 90001 到哪了"
        aria-label="输入问题"
        @keydown.enter="onEnter"
      />
      <button v-if="asking" class="stop" type="button" @click="emit('stop')">停止</button>
      <button v-else class="primary" type="button" :disabled="!query.trim()" @click="emit('submit')">发送</button>
    </div>
    <p class="composer-hint">Enter 发送 · 回答基于店铺政策与订单数据，办理前会核验归属</p>
  </section>
</template>

<style scoped>
.composer {
  position: sticky; bottom: 0; z-index: 10;
  padding: 12px 16px 14px; margin-top: auto;
  background: var(--panel);
  border-top: 1px solid var(--line);
}
.composer-box {
  display: flex; gap: 10px; align-items: center;
}
.composer input {
  flex: 1; min-width: 0; height: 40px; box-sizing: border-box;
  border-radius: var(--radius-md); padding: 0 12px; background: var(--panel-2);
}
.composer input:focus { outline: none; border-color: var(--teal); box-shadow: var(--ring); background: var(--panel); }
.composer button { height: 40px; padding: 0 18px; flex: none; }
.stop { background: var(--panel); border-color: var(--line); color: var(--rose); }
.composer-hint {
  margin: 8px 0 0;
  color: var(--ink-3);
  font-size: 11px;
}

@media (max-width: 640px) {
  .composer { padding: 10px 12px 12px; }
  .composer button { padding: 0 14px; }
  .composer-hint { display: none; }
}
</style>
