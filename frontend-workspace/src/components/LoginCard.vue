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
      <p class="eyebrow">坐席工作台</p>
      <h1>让每一张售后工单<br>都有交接、有结论</h1>
      <p class="brand-lede">
        队列、会话接管、退款审核与结单意见在同一个工作台完成。
        所有动作记审计，署名取自登录账号。
      </p>
      <ol class="brand-chain" aria-label="工单处理链路">
        <li><b>接单</b><span>队列按优先级排序，SLA 只标记超时</span></li>
        <li><b>处理</b><span>订单、会话与上下文在同一条工单里</span></li>
        <li><b>结单</b><span>结论回到买家，责任落在登录账号</span></li>
      </ol>
    </div>

    <div class="login-card">
      <p class="eyebrow">开始值班</p>
      <h2>登录坐席工作台</h2>
      <p class="lede">登录后进入工单队列，处理会话、结单与退款审核；演示档可直接进入。</p>
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
  max-width: 1040px;
  max-height: 640px;
  width: 100%;
  margin: auto;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-panel);
  overflow: hidden;
  box-shadow: var(--shadow-2);
}
.login-brand {
  background: var(--chrome);
  color: var(--chrome-ink);
  padding: 44px;
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
  font-family: var(--serif);
  font-size: 32px;
  font-weight: 600;
  line-height: 1.35;
  letter-spacing: -0.01em;
}
.brand-lede {
  margin: 0 0 28px;
  color: var(--chrome-muted);
  font-size: 14px;
  line-height: 1.75;
  max-width: 400px;
}
.brand-chain {
  list-style: none;
  margin: 0;
  padding: 4px 0 4px 22px;
  position: relative;
  display: grid;
  gap: 14px;
  border-top: 1px solid var(--chrome-line);
  border-bottom: 1px solid var(--chrome-line);
}
.brand-chain::before {
  content: "";
  position: absolute;
  left: 4px;
  top: 16px;
  bottom: 16px;
  width: 1px;
  background: var(--chrome-line);
}
.brand-chain li { position: relative; display: grid; gap: 2px; padding: 8px 0; }
.brand-chain li::before {
  content: "";
  position: absolute;
  left: -22px;
  top: 14px;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--chrome);
  border: 1px solid var(--teal-line);
}
.brand-chain b { color: var(--chrome-ink); font-size: 14px; font-weight: 500; }
.brand-chain span { color: var(--chrome-muted); font-size: 12px; line-height: 1.55; }

.login-card {
  box-sizing: border-box;
  padding: 44px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.login-card h2 {
  margin: 0 0 8px;
  font-family: var(--serif);
  font-size: 24px;
  font-weight: 600;
  line-height: 1.35;
}
.lede { color: var(--ink-2); margin: 0 0 24px; font-size: 14px; line-height: 1.65; }
.login-actions { display: flex; justify-content: flex-start; gap: 10px; flex-wrap: wrap; }
.big { padding: 9px 18px; font-size: 14px; }
.login-form {
  display: grid; gap: 10px; margin: 20px 0 0; text-align: left;
  padding-top: 20px; border-top: 1px solid var(--line);
}
.login-form label { display: grid; gap: 5px; font-size: 13px; color: var(--ink-2); }
.login-form input { width: 100%; box-sizing: border-box; }
.login-card .link { align-self: flex-start; margin-top: 12px; }

@media (max-width: 640px) {
  .login-wrap { grid-template-columns: 1fr; max-height: none; margin: 16px; }
  .login-brand { padding: 24px 20px; }
  .login-brand h1 { font-size: 25px; }
  .brand-chain, .brand-lede { display: none; }
  .login-card { padding: 24px 20px; }
}
</style>
