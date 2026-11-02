<script setup lang="ts">
/**
 * 未登录入口（round44 票 188 从 `App.vue` 拆出）。
 *
 * <p>表单字段用 `defineModel`，**状态仍归 `App.vue`**：登录、一键体验、错误文案
 * 与成功后启动 SSE 的顺序都在根组件里。
 */
const tenant = defineModel<string>('tenant', { required: true })
const username = defineModel<string>('username', { required: true })
const password = defineModel<string>('password', { required: true })
const showLogin = defineModel<boolean>('showLogin', { required: true })

defineProps<{
  demoLoading: boolean
  loginError: string
}>()

const emit = defineEmits<{ demoLogin: [role: 'agent' | 'admin']; submit: [] }>()

function toggleLogin() {
  showLogin.value = !showLogin.value
}
</script>

<template>
  <section class="login-wrap">
    <div class="login-card">
      <p class="eyebrow">坐席工作台</p>
      <h1>先登录，再开始接单</h1>
      <p class="lede">队列、会话原文、结单与退款审核都在这里；所有动作记审计，署名取自登录账号。</p>
      <div class="login-actions">
        <button class="primary big" type="button" :disabled="demoLoading" @click="emit('demoLogin', 'agent')">
          {{ demoLoading ? '正在进入…' : '一键体验坐席' }}
        </button>
        <button class="ghost" type="button" :disabled="demoLoading" @click="emit('demoLogin', 'admin')">管理员体验</button>
      </div>
      <button class="link" type="button" @click="toggleLogin">
        {{ showLogin ? '收起账号登录' : '用账号口令登录' }}
      </button>
      <form v-if="showLogin" class="login-form" @submit.prevent="emit('submit')">
        <label>店铺编号<input v-model="tenant" type="text" placeholder="例如 T001" /></label>
        <label>坐席账号<input v-model="username" type="text" placeholder="例如 agent" /></label>
        <label>口令<input v-model="password" type="password" placeholder="演示口令见 .env" /></label>
        <button class="primary" type="submit">登录</button>
      </form>
      <p v-if="loginError" class="err">{{ loginError }}</p>
      <p class="fine">一键体验只在回环演示栈可用；正式环境请用账号口令登录。</p>
    </div>
  </section>
</template>

<style scoped>
.login-wrap { flex: 1; display: grid; place-items: center; padding: 40px 0; }
.login-card { width: min(620px, 100%); box-sizing: border-box; background: var(--panel);
  border: 1px solid var(--line); border-radius: 20px; padding: 32px;
  box-shadow: 0 12px 40px rgba(20, 22, 26, .08); text-align: center; }
.login-card h1 { margin: 0 0 10px; font-size: 25px; }
.lede { color: var(--ink-2); margin: 0 auto 22px; max-width: 500px; }
.login-actions { display: flex; justify-content: center; gap: 12px; flex-wrap: wrap; }
.big { padding: 10px 20px; font-size: 15px; }
.login-form { display: grid; gap: 10px; max-width: 340px; margin: 18px auto 0; text-align: left; }
.login-form label { display: grid; gap: 4px; font-size: 13px; color: var(--ink-2); }
.login-form input { width: 100%; box-sizing: border-box; }

@media (max-width: 640px) {
  .login-card { padding: 22px 16px; border-radius: 16px; }
}
</style>
