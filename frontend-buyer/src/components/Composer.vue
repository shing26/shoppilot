<script setup lang="ts">
/** 输入框与发送/停止（round44 票 188 从 `App.vue` 拆出）。 */
const query = defineModel<string>('query', { required: true })

defineProps<{ asking: boolean }>()

const emit = defineEmits<{ submit: []; stop: [] }>()
</script>

<template>
  <section class="composer">
    <input
      v-model="query"
      type="text"
      placeholder="输入问题，例如：订单 90001 到哪了"
      aria-label="输入问题"
      @keyup.enter="emit('submit')"
    />
    <button v-if="asking" class="stop" type="button" @click="emit('stop')">停止</button>
    <button v-else class="primary" type="button" :disabled="!query.trim()" @click="emit('submit')">发送</button>
  </section>
</template>

<style scoped>
.composer {
  position: sticky; bottom: 0; z-index: 10;
  display: flex; gap: 10px; align-items: center;
  padding: 12px 0 18px; margin-top: auto;
  background: linear-gradient(180deg, rgba(242, 243, 245, 0), var(--bg) 34%);
}
.composer input {
  flex: 1; min-width: 0; height: 42px; box-sizing: border-box;
  border-radius: 10px; padding: 0 12px;
}
.composer button { height: 42px; padding: 0 18px; flex: none; }
.stop { background: var(--panel); border-color: var(--line); color: var(--rose); }

@media (max-width: 640px) {
  .composer { padding: 10px 0 14px; }
  .composer button { padding: 0 14px; }
}
</style>
