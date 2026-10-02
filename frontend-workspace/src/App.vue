<script setup lang="ts">
/**
 * 坐席工作台（round23 票 73 / ADR 0057）。
 *
 * <p>三条刻意的口径：
 * <ol>
 *   <li><b>失败不冻结</b>：读不到就写清「读不到 + 为什么」，不留空态、不抛未捕获 Promise
 *       （同调试台那轮修的七处里的一条）；</li>
 *   <li><b>动作防连点</b>：处理是不可逆的资金/责任动作，一次点击只发一次请求
 *       （票 61 在退款面板上踩过这个坑）；</li>
 *   <li><b>坐席身份是自报的</b>：本轮没有认证身份（ADR 0056 是下一轮），
 *       所以它只写进审计的 actor，不构成权限。</li>
 * </ol>
 */
import { computed, onMounted, ref } from 'vue'
import { claimTicket, describe, listTickets, messageOf, releaseTicket, resolveTicket } from './api'
import { QUEUES, minutesLeft, priorityRank, type Ticket } from './types'

const opsToken = ref(localStorage.getItem('shoppilot.opsToken') ?? '')
const agent = ref(localStorage.getItem('shoppilot.agent') ?? '')
const queue = ref<string>('ALL')
const tickets = ref<Ticket[]>([])
const loadError = ref('')
const actionNote = ref('')
const inFlight = ref<string>('')
const now = ref(Date.now())

let timer = 0

const sorted = computed(() =>
  [...tickets.value].sort((a, b) => {
    const byPriority = priorityRank(a.priority) - priorityRank(b.priority)
    return byPriority !== 0 ? byPriority : b.createdAt.localeCompare(a.createdAt)
  }),
)

/** 三种失败各有各的说法——混成一句「加载失败」正是调试台修掉的那个毛病。 */
async function refresh() {
  loadError.value = ''
  if (!opsToken.value.trim()) {
    loadError.value = '先填运维令牌（ops token）——工单队列要凭证才能读'
    tickets.value = []
    return
  }
  const result = await listTickets(queue.value, opsToken.value)
  if (result.status === 0) {
    loadError.value = messageOf(result.body) ?? '网关不可达'
    tickets.value = []
    return
  }
  if (result.status === 401 || result.status === 403) {
    loadError.value = '运维凭证不对'
    tickets.value = []
    return
  }
  if (!Array.isArray(result.body)) {
    loadError.value = messageOf(result.body) ?? `队列读取失败（HTTP ${result.status}）`
    tickets.value = []
    return
  }
  tickets.value = result.body
}

async function run(ticket: Ticket, kind: 'claim' | 'release' | 'resolve') {
  if (inFlight.value) return // 防连点：一次只允许一个动作在飞
  // 处理完成是不可逆的一步（谁结的它是责任链的终点证据），所以要人点一次确认。
  if (kind === 'resolve' && !window.confirm(`确认处理完 ${ticket.id}？\n结单后不能再流转，且会记进审计。`)) {
    return
  }
  inFlight.value = `${ticket.id}:${kind}`
  actionNote.value = ''
  const result =
    kind === 'claim'
      ? await claimTicket(ticket.id, agent.value, opsToken.value)
      : kind === 'release'
        ? await releaseTicket(ticket.id, agent.value, opsToken.value)
        : await resolveTicket(ticket.id, agent.value, opsToken.value, '')
  actionNote.value = describe(result, { claim: '已领取', release: '已释放', resolve: '已处理完成' }[kind])
  inFlight.value = ''
  await refresh()
}

onMounted(() => {
  void refresh()
  // SLA 倒计时只需要分钟粒度，10 秒一跳足够；不跳得更勤是为了不给浏览器白烧 CPU。
  timer = window.setInterval(() => {
    now.value = Date.now()
  }, 10_000)
  window.addEventListener('beforeunload', () => window.clearInterval(timer))
})

function saveCredentials() {
  localStorage.setItem('shoppilot.opsToken', opsToken.value)
  localStorage.setItem('shoppilot.agent', agent.value)
  void refresh()
}

function slaText(ticket: Ticket): string {
  if (ticket.escalatedAt) return '已超时（状态不变）'
  const left = minutesLeft(ticket.slaDeadline, now.value)
  if (left === null) return '无 SLA'
  return left >= 0 ? `剩 ${left} 分钟` : `超时 ${-left} 分钟`
}
</script>

<template>
  <main class="wrap">
    <header>
      <h1>坐席工作台</h1>
      <p class="sub">工单与坐席服务（round23 票 72）· 凭证形态：ops token + 自报坐席名，真身份域是下一轮</p>
    </header>

    <section class="ctl">
      <label>运维令牌 <input v-model="opsToken" type="text" placeholder="ops token" @change="saveCredentials" /></label>
      <label>坐席名 <input v-model="agent" type="text" placeholder="坐席名（自报，进审计）" @change="saveCredentials" /></label>
      <label>队列
        <select v-model="queue" @change="refresh">
          <option v-for="q in QUEUES" :key="q" :value="q">{{ q }}</option>
        </select>
      </label>
      <button type="button" @click="refresh">刷新</button>
    </section>

    <p v-if="loadError" class="err">{{ loadError }}</p>
    <p v-if="actionNote" class="note">{{ actionNote }}</p>

    <table v-if="sorted.length">
      <thead>
        <tr><th>工单号</th><th>来源</th><th>队列</th><th>诉求</th><th>SLA</th><th>状态</th><th>操作</th></tr>
      </thead>
      <tbody>
        <tr v-for="t in sorted" :key="t.id">
          <td class="mono">{{ t.id }}</td>
          <td>{{ t.source }}</td>
          <td>{{ t.queue }}</td>
          <td class="query">{{ t.userQuery }}</td>
          <td :class="{ overdue: !!t.escalatedAt }">{{ slaText(t) }}</td>
          <td>{{ t.status }}<span v-if="t.assignee"> / {{ t.assignee }}</span></td>
          <td class="ops">
            <button type="button" :disabled="!!inFlight" @click="run(t, 'claim')">领取</button>
            <button type="button" :disabled="!!inFlight || t.status === 'OPEN'" @click="run(t, 'release')">释放</button>
            <button type="button" :disabled="!!inFlight || t.status === 'RESOLVED'" @click="run(t, 'resolve')">处理完成</button>
          </td>
        </tr>
      </tbody>
    </table>
    <p v-else-if="!loadError" class="empty">这一档队列里没有待处理工单</p>
  </main>
</template>

<style scoped>
.wrap { font: 14px/1.6 system-ui, sans-serif; margin: 24px auto; max-width: 1100px; padding: 0 16px; }
h1 { font-size: 20px; margin: 0 0 4px; }
.sub { color: #666; margin: 0 0 16px; }
.ctl { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; margin-bottom: 12px; }
.ctl input, .ctl select { padding: 4px 6px; }
table { border-collapse: collapse; width: 100%; }
th, td { border-bottom: 1px solid #e5e5e5; padding: 6px 8px; text-align: left; vertical-align: top; }
.mono { font-family: ui-monospace, monospace; }
.query { max-width: 320px; }
.overdue { color: #b42318; }
.err { color: #b42318; }
.note { color: #146c2e; }
.empty { color: #666; }
.ops button { margin-right: 6px; }
</style>