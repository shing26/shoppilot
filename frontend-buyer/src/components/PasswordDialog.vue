<script setup lang="ts">
/**
 * 改密入口（round56 票 224 / ADR 0086）。
 *
 * <p>只收当前口令与新口令；账号由服务端从已验签令牌取，页面上没有账号输入框——
 * 那会让「改谁的口令」重新变成一个可伪造的参数。
 */
import { ref } from 'vue'
import { changePassword, describePasswordChange } from 'frontend-shared'

const props = defineProps<{ token: string }>()
const emit = defineEmits<{ close: []; changed: [] }>()

const current = ref('')
const next = ref('')
const confirm = ref('')
const busy = ref(false)
const error = ref('')

async function submit() {
  error.value = ''
  if (next.value.length < 8) {
    error.value = '新口令至少 8 位'
    return
  }
  if (next.value !== confirm.value) {
    error.value = '两次输入的新口令不一致'
    return
  }
  busy.value = true
  try {
    const result = await changePassword(props.token, current.value, next.value)
    if (result.status >= 200 && result.status < 300) {
      emit('changed')
      return
    }
    error.value = describePasswordChange(result)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="mask" role="presentation" @click.self="emit('close')">
    <form class="dialog" role="dialog" aria-modal="true" aria-labelledby="pwd-title" @submit.prevent="submit">
      <p class="eyebrow">账号安全</p>
      <h2 id="pwd-title">修改登录口令</h2>
      <p class="lede">修改成功后，其他设备上的登录会失效，需要用新口令重新登录。</p>
      <label>当前口令
        <input v-model="current" type="password" autocomplete="current-password" required />
      </label>
      <label>新口令
        <input v-model="next" type="password" autocomplete="new-password" required />
      </label>
      <label>再次输入新口令
        <input v-model="confirm" type="password" autocomplete="new-password" required />
      </label>
      <p v-if="error" class="err" role="alert">{{ error }}</p>
      <div class="actions">
        <button class="ghost" type="button" @click="emit('close')">取消</button>
        <button class="primary" type="submit" :disabled="busy || !current || !next">
          {{ busy ? '正在保存…' : '保存新口令' }}
        </button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(18, 24, 22, 0.42);
}
.dialog {
  width: min(420px, 100%);
  display: grid;
  gap: 12px;
  padding: 22px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  box-shadow: 0 18px 48px rgba(18, 24, 22, 0.18);
}
.dialog h2 { margin: 0; font-size: 19px; letter-spacing: -0.01em; }
.lede { margin: 0 0 4px; color: var(--ink-2); font-size: 13px; line-height: 1.6; }
label { display: grid; gap: 6px; font-size: 13px; color: var(--ink-2); }
input { min-height: 40px; padding: 0 12px; }
.actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 4px; }
.err { margin: 0; color: var(--danger-ink); font-size: 13px; }
</style>
