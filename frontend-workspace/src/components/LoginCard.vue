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
    <div class="login-brand">
      <div class="login-mark">SP</div>
      <p class="eyebrow">SHOPPILOT WORKSPACE</p>
      <h1>让每一张售后工单<br>都有交接、有结论</h1>
      <p class="brand-lede">
        队列、会话接管、退款审核与结单意见在同一个工作台完成。
        所有动作记审计，署名取自登录账号。
      </p>
      <ul class="brand-points">
        <li><span>01</span>工单队列与 SLA</li>
        <li><span>02</span>渠道会话接管</li>
        <li><span>03</span>退款审核与审计</li>
      </ul>
    </div>

    <div class="login-card">
      <p class="eyebrow">坐席工作台</p>
      <h2>登录坐席工作台</h2>
      <p class="lede">登录后进入工单队列，处理会话、结单与退款审核。</p>
      <div class="login-actions">
        <button class="primary big" type="button" @click="toggleLogin">
          {{ showLogin ? '收起账号登录' : '用账号口令登录' }}
        </button>
        <button class="ghost" type="button" :disabled="demoLoading" @click="emit('demoLogin', 'agent')">
          {{ demoLoading ? '正在进入…' : '一键体验坐席' }}
        </button>
        <button class="ghost" type="button" :disabled="demoLoading" @click="emit('demoLogin', 'admin')">管理员体验</button>
      </div>
      <form v-if="showLogin" class="login-form" @submit.prevent="emit('submit')">
        <label>店铺编号<input v-model="tenant" type="text" placeholder="例如 T001" /></label>
        <label>坐席账号<input v-model="username" type="text" placeholder="例如 agent" /></label>
        <label>口令<input v-model="password" type="password" placeholder="演示口令见 .env" /></label>
        <button class="primary" type="submit">登录</button>
      </form>
      <p v-if="loginError" class="err">{{ loginError }}</p>
      <p class="fine">一键体验仅在演示档可用；正式部署请用账号口令登录。</p>
    </div>
  </section>
</template>

<style scoped>
.login-wrap {
  flex: 0 0 auto;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(380px, 0.95fr);
  place-items: stretch;
  max-width: 1000px;
  max-height: 640px;
  width: 100%;
  margin: auto;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-xl);
  overflow: hidden;
  box-shadow: var(--shadow-2);
}
.login-brand {
  background: var(--chrome);
  color: var(--chrome-ink);
  padding: 40px;
  display: flex;
  flex-direction: column;
}
.login-mark {
  width: 32px; height: 32px; border-radius: 8px;
  background: var(--teal); color: #fff;
  display: grid; place-items: center;
  font-size: 12px; font-weight: 700; letter-spacing: 0.4px;
  margin-bottom: 28px;
}
.login-brand .eyebrow { color: var(--teal-line); margin: 0 0 12px; }
.login-brand h1 {
  margin: 0 0 16px;
  font-size: 30px;
  line-height: 1.22;
  letter-spacing: -0.02em;
}
.brand-lede {
  margin: 0 0 28px;
  color: var(--chrome-muted);
  font-size: 13px;
  line-height: 1.75;
  max-width: 380px;
}
.brand-points {
  list-style: none;
  margin: 0;
  padding: 0;
  border-top: 1px solid var(--chrome-line);
}
.brand-points li {
  display: flex; align-items: center; gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid var(--chrome-line);
  font-size: 13px;
}
.brand-points span { font-family: var(--mono); font-size: 11px; color: var(--chrome-muted); }

.login-card {
  box-sizing: border-box;
  padding: 40px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.login-card h2 { margin: 0 0 8px; font-size: 22px; line-height: 1.3; letter-spacing: -0.01em; }
.lede { color: var(--ink-2); margin: 0 0 24px; font-size: 13px; }
.login-actions { display: flex; justify-content: flex-start; gap: 10px; flex-wrap: wrap; }
.big { padding: 9px 18px; font-size: 14px; }
.login-form {
  display: grid; gap: 10px; margin: 20px 0 0; text-align: left;
  padding-top: 20px; border-top: 1px solid var(--line);
}
.login-form label { display: grid; gap: 4px; font-size: 13px; color: var(--ink-2); }
.login-form input { width: 100%; box-sizing: border-box; }
.login-card .link { align-self: flex-start; margin-top: 12px; }

@media (max-width: 640px) {
  .login-wrap { grid-template-columns: 1fr; max-height: none; margin: 16px; }
  .login-brand { padding: 24px 20px; }
  .login-brand h1 { font-size: 24px; }
  .brand-points, .brand-lede { display: none; }
  .login-card { padding: 24px 20px; }
}
</style>
