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
    <p class="eyebrow">在线客服</p>
    <h1>你好，我是{{ storeLabel }}的客服</h1>
    <p class="lede">
      可以帮你查订单、退换货、生鲜理赔。答不了会转人工，并给你一个可追踪的工单号，不用重复描述。
    </p>
    <div class="gate-actions">
      <button class="primary big" type="button" :disabled="demoLoading || !hasStoreCode" @click="emit('demoEnter')">
        {{ demoLoading ? '正在进入…' : '一键体验买家' }}
      </button>
      <button class="link" type="button" @click="toggleLogin">
        {{ showLogin ? '收起账号登录' : '用买家账号登录' }}
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
    <p class="fine">演示环境可一键进入；正式环境请用买家账号登录。</p>
  </section>
</template>

<style scoped>
.gate {
  box-sizing: border-box;
  background: var(--panel); border: 1px solid var(--line); border-radius: 20px;
  padding: 32px; margin: 12px auto 0; max-width: 640px; width: 100%;
  box-shadow: 0 12px 40px rgba(20, 22, 26, 0.07);
  text-align: center;
}
.gate h1 { margin: 0 0 10px; font-size: 24px; line-height: 1.3; }
.lede { margin: 0 auto 20px; max-width: 520px; color: var(--ink-2); font-size: 14px; }
.gate-actions { display: flex; align-items: center; justify-content: center; gap: 14px; flex-wrap: wrap; }
.big { padding: 10px 20px; font-size: 15px; }
.login {
  display: grid; gap: 10px; max-width: 320px; margin: 20px auto 0; text-align: left;
}
.login label { display: grid; gap: 4px; font-size: 13px; color: var(--ink-2); }
.login input { width: 100%; box-sizing: border-box; }
.login button { margin-top: 2px; }

@media (max-width: 640px) {
  .gate { padding: 22px 16px; margin: 10px 0 0; border-radius: 16px; }
  .gate h1 { font-size: 21px; }
  .gate-actions { flex-direction: column; align-items: stretch; }
  .gate-actions .link { align-self: center; margin-top: 10px; }
}
</style>
