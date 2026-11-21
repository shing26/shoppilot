<script setup lang="ts">
/**
 * 未登录的入口（round44 票 188 从 `App.vue` 拆出）。
 *
 * <p>表单字段用 `defineModel` 双向绑定，**状态仍归 `App.vue`**：登录流程、错误文案、
 * 演示入口的回退顺序都在根组件里，这一层只负责把它画出来。
 */
const username = defineModel<string>('username', { required: true })
const password = defineModel<string>('password', { required: true })
const showLogin = defineModel<boolean>('showLogin', { required: true })

defineProps<{
  storeLabel: string
  demoLoading: boolean
  hasStoreCode: boolean
  autoGuest: boolean
  guestError: string
  loginError: string
}>()

const emit = defineEmits<{ demoEnter: []; submit: [] }>()

function toggleLogin() {
  showLogin.value = !showLogin.value
}
</script>

<template>
  <section class="gate">
    <div class="gate-brand">
      <div class="gate-mark">SP</div>
      <p class="eyebrow">买家服务</p>
      <h1>订单、政策与售后，<br>在同一条对话里说清楚</h1>
      <p class="lede">
        政策咨询、订单核验与售后办理在同一条对话里完成。需要转人工时直接转，
        并留下一个可追踪的工单号。
      </p>
      <p class="gate-foot">本店客服在线 · 办理类问题先核验归属</p>
    </div>

    <div class="gate-form">
      <p class="eyebrow">开始咨询</p>
      <h2>登录{{ storeLabel === '本店' ? '店铺客服' : storeLabel }}</h2>
      <p class="form-lede">登录后可查看订单、工单进度与历史对话；演示档可直接进入。</p>
      <div class="gate-actions">
        <button class="primary big" type="button" @click="toggleLogin">
          {{ showLogin ? '收起账号登录' : '用买家账号登录' }}
        </button>
        <button class="ghost" type="button" :disabled="demoLoading || !hasStoreCode" @click="emit('demoEnter')">
          {{ demoLoading ? '正在进入…' : '一键体验买家' }}
        </button>
      </div>

      <form v-if="showLogin" class="login" @submit.prevent="emit('submit')">
        <label>买家账号
          <input v-model="username" type="text" autocomplete="username" placeholder="例如 buyer" />
        </label>
        <label>口令
          <input v-model="password" type="password" autocomplete="current-password" placeholder="演示口令见 .env" />
        </label>
        <button class="primary" type="submit" :disabled="!hasStoreCode || !username.trim() || !password">登录</button>
      </form>

      <p v-if="!hasStoreCode" class="err">
        缺少店铺码：请通过店铺链接进入（例如 <span class="mono">/buyer/?store=T001</span>）
      </p>
      <p v-else-if="guestError" class="err">{{ guestError }}</p>
      <p v-else-if="autoGuest" class="fine">正在为你进入店铺…</p>
      <p v-if="loginError" class="err">{{ loginError }}</p>
      <p class="fine">演示档可一键进入；正式部署请用买家账号登录。</p>
    </div>
  </section>
</template>

<style scoped>
.gate {
  box-sizing: border-box;
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(380px, 0.95fr);
  max-width: 1040px;
  width: 100%;
  margin: 48px auto 0;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-panel);
  overflow: hidden;
  box-shadow: var(--shadow-2);
}
.gate-brand {
  background: var(--chrome);
  color: var(--chrome-ink);
  padding: 44px;
  display: flex;
  flex-direction: column;
}
.gate-mark {
  width: 32px; height: 32px; border-radius: 8px;
  background: var(--teal); color: #fff;
  display: grid; place-items: center;
  font-size: 12px; font-weight: 700; letter-spacing: 0.4px;
  margin-bottom: 28px;
}
.gate-brand .eyebrow { color: var(--teal-line); margin: 0 0 12px; }
.gate-brand h1 {
  margin: 0 0 16px;
  font-family: var(--serif);
  font-size: 32px;
  font-weight: 600;
  line-height: 1.35;
  letter-spacing: -0.01em;
}
.gate-brand .lede {
  margin: 0 0 28px;
  color: var(--chrome-muted);
  font-size: 14px;
  line-height: 1.75;
  max-width: 400px;
}
.gate-foot {
  margin: 24px 0 0;
  color: var(--chrome-muted);
  font-size: 12px;
}
.gate-form {
  padding: 44px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.gate-form h2 {
  margin: 0 0 8px;
  font-family: var(--serif);
  font-size: 24px;
  font-weight: 600;
  line-height: 1.35;
  letter-spacing: -0.01em;
}
.form-lede { margin: 0 0 24px; color: var(--ink-2); font-size: 14px; line-height: 1.65; }
.gate-actions { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
.big { padding: 9px 18px; font-size: 14px; }
.login {
  display: grid; gap: 10px; margin: 24px 0 0; text-align: left;
  padding-top: 20px; border-top: 1px solid var(--line);
}
.login label { display: grid; gap: 5px; font-size: 13px; color: var(--ink-2); }
.login input { width: 100%; box-sizing: border-box; }
.login button { margin-top: 2px; }

@media (max-width: 640px) {
  .gate { grid-template-columns: 1fr; margin: 16px 0 0; }
  .gate-brand { padding: 24px 20px; }
  .gate-brand h1 { font-size: 25px; }
  .gate-foot { display: none; }
  .gate-form { padding: 24px 20px; }
  .gate-actions { flex-direction: column; align-items: stretch; }
  .gate-actions .link { align-self: center; margin-top: 10px; }
}
</style>
