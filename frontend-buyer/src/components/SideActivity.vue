<script setup lang="ts">
/**
 * 侧栏「最近订单 / 最近工单」（round44 票 188 从 `App.vue` 拆出）。
 *
 * <p>只读展示 + 发事件：刷新、切换标签、点订单去问、点工单开抽屉都由 `App.vue` 执行——
 * 数据拉取与持久化状态不在这一层。
 */
import { computed } from 'vue'
import { countLabel } from 'frontend-shared'
import type { MyOrder, OrderDetail, OrderLogistics } from '../api'
import { PRIORITY_LABEL, STATUS_LABEL, type Ticket } from '../types'
import { formatTime, slaText } from '../lib/format'
import OrderCard from './OrderCard.vue'

const sideTab = defineModel<'orders' | 'tickets'>('sideTab', { required: true })
const showAllOrders = defineModel<boolean>('showAllOrders', { required: true })
const showAllTickets = defineModel<boolean>('showAllTickets', { required: true })

const props = defineProps<{
  orders: MyOrder[]
  tickets: Ticket[]
  ordersLoading: boolean
  ticketsLoading: boolean
  orderError: string
  ticketError: string
  isGuest: boolean
  now: number
  /** 展开中的订单号；空串表示全部收起。详情数据由 `App.vue` 拉取后下传。 */
  expandedOrderNo: string
  orderDetail: OrderDetail | null
  orderLogistics: OrderLogistics | null
  orderDetailLoading: boolean
  orderDetailError: string
}>()

const emit = defineEmits<{
  refreshOrders: []
  refreshTickets: []
  toggleOrder: [orderNo: string]
  askOrder: [order: MyOrder]
  openTicket: [ticket: Ticket]
}>()

// P0 侧栏：默认只露出 2 条，避免工单历史把聊天淹没。
const visibleOrders = computed(() => (showAllOrders.value ? props.orders : props.orders.slice(0, 2)))
const visibleTickets = computed(() => (showAllTickets.value ? props.tickets : props.tickets.slice(0, 2)))

function pickTab(tab: 'orders' | 'tickets') {
  sideTab.value = tab
}

function toggleOrders() {
  showAllOrders.value = !showAllOrders.value
}

function toggleTickets() {
  showAllTickets.value = !showAllTickets.value
}
</script>

<template>
  <section class="side-card side-activity">
    <div class="side-tabs" role="tablist" aria-label="订单与工单">
      <button
        class="side-tab"
        :class="{ active: sideTab === 'orders' }"
        type="button"
        role="tab"
        :aria-selected="sideTab === 'orders'"
        @click="pickTab('orders')"
      >
        订单<span class="tab-count">{{ countLabel(orders.length) }}</span>
      </button>
      <button
        class="side-tab"
        :class="{ active: sideTab === 'tickets' }"
        type="button"
        role="tab"
        :aria-selected="sideTab === 'tickets'"
        @click="pickTab('tickets')"
      >
        工单<span class="tab-count">{{ countLabel(tickets.length) }}</span>
      </button>
    </div>

    <template v-if="sideTab === 'orders'">
      <div class="section-head">
        <h2>最近订单</h2>
        <button v-if="!isGuest" class="link" type="button" @click="emit('refreshOrders')">刷新</button>
      </div>
      <p v-if="isGuest" class="notice">游客模式不展示订单：登录买家账号后可以查看自己的订单。</p>
      <p v-else-if="orderError" class="err">{{ orderError }}</p>
      <p v-else-if="ordersLoading" class="empty">正在读取订单…</p>
      <div v-else-if="orders.length" class="order-list">
        <OrderCard
          v-for="order in visibleOrders"
          :key="order.orderNo"
          :order="order"
          :expanded="expandedOrderNo === order.orderNo"
          :detail="expandedOrderNo === order.orderNo ? orderDetail : null"
          :logistics="expandedOrderNo === order.orderNo ? orderLogistics : null"
          :loading="expandedOrderNo === order.orderNo && orderDetailLoading"
          :error="expandedOrderNo === order.orderNo ? orderDetailError : ''"
          @toggle="emit('toggleOrder', order.orderNo)"
          @ask="emit('askOrder', order)"
        />
      </div>
      <p v-else class="empty">你还没有订单</p>
      <button v-if="orders.length > 2" class="link side-more" type="button" @click="toggleOrders">
        {{ showAllOrders ? '收起' : '查看全部订单' }}
      </button>
    </template>

    <template v-else>
      <div class="section-head">
        <h2>最近工单</h2>
        <button v-if="!isGuest" class="link" type="button" @click="emit('refreshTickets')">刷新</button>
      </div>
      <p v-if="isGuest" class="notice">游客模式不展示工单：先登录买家账号，才能看到自己的售后进度。</p>
      <p v-else-if="ticketError" class="err">{{ ticketError }}</p>
      <p v-else-if="ticketsLoading" class="empty">正在读取…</p>
      <div v-else-if="tickets.length" class="ticket-list">
        <article
          v-for="t in visibleTickets"
          :key="t.id"
          class="ticket-card clickable"
          role="button"
          tabindex="0"
          @click="emit('openTicket', t)"
          @keyup.enter="emit('openTicket', t)"
        >
          <div class="ticket-top">
            <strong class="mono">{{ t.id }}</strong>
            <span class="pill">{{ STATUS_LABEL[t.status] ?? t.status }}</span>
          </div>
          <p class="ticket-query">{{ t.userQuery }}</p>
          <div class="ticket-timeline">
            <span class="step done">已提交 <time>{{ formatTime(t.createdAt) }}</time></span>
            <span v-if="t.escalatedAt" class="step warn">已超时升级 <time>{{ formatTime(t.escalatedAt) }}</time></span>
            <span class="step" :class="{ done: t.status === 'RESOLVED' }">
              {{ STATUS_LABEL[t.status] ?? t.status }}<template v-if="t.status !== 'RESOLVED'"> · {{ slaText(t, now) }}</template>
            </span>
          </div>
          <div class="ticket-meta">
            <span>{{ PRIORITY_LABEL[t.priority ?? 'normal'] ?? t.priority }}</span>
            <span>{{ t.assignee ?? '待分配' }}</span>
            <span class="ticket-open">查看进度 →</span>
          </div>
        </article>
      </div>
      <p v-else class="empty">你还没有需要人工处理的工单</p>
      <button v-if="tickets.length > 2" class="link side-more" type="button" @click="toggleTickets">
        {{ showAllTickets ? '收起' : '查看全部工单' }}
      </button>
    </template>
  </section>
</template>

<style scoped>
.side-tabs {
  display: flex; gap: 4px; padding: 3px; margin: 0 0 12px;
  background: var(--bg); border-radius: 10px;
}
.side-tab {
  flex: 1; border: none; background: transparent; color: var(--ink-3);
  border-radius: 8px; padding: 7px 8px; cursor: pointer; font-size: 13px;
  display: flex; align-items: center; justify-content: center; gap: 6px;
}
.side-tab.active { background: var(--panel); color: var(--ink); box-shadow: 0 1px 4px rgba(20, 22, 26, .08); font-weight: 600; }
.tab-count {
  min-width: 18px; padding: 0 5px; border-radius: 999px; background: var(--teal-soft-2);
  color: var(--teal); font-size: 11px; line-height: 18px; text-align: center;
}
.side-tab:not(.active) .tab-count { background: var(--line); color: var(--ink-3); }
.side-activity .section-head { margin-bottom: 8px; }
.side-more { display: block; margin: 8px auto 0; font-size: 12px; }
.order-list { display: grid; gap: 8px; max-height: 360px; overflow-y: auto; padding-right: 2px; }
.ticket-list { display: grid; gap: 10px; }
.ticket-card {
  box-sizing: border-box;
  background: var(--panel); border: 1px solid var(--line); border-radius: 12px; padding: 12px;
}
.ticket-card.clickable { cursor: pointer; transition: border-color 0.15s ease, box-shadow 0.15s ease; }
.ticket-card.clickable:hover { border-color: var(--teal-line); box-shadow: 0 4px 16px rgba(15, 118, 110, 0.08); }
.ticket-card.clickable:focus-visible { outline: 2px solid var(--teal); outline-offset: 2px; }
.ticket-open { margin-left: auto; color: var(--teal); white-space: nowrap; }
.ticket-top { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.ticket-top strong { font-size: 13px; overflow-wrap: anywhere; }
.ticket-query { margin: 6px 0 8px; color: var(--ink); overflow-wrap: anywhere; }
.ticket-timeline { display: flex; gap: 12px; flex-wrap: wrap; margin: 0 0 8px; color: var(--ink-3); font-size: 12px; }
.step { position: relative; padding-left: 10px; }
.step::before { content: ''; position: absolute; left: 0; top: 6px; width: 5px; height: 5px; border-radius: 50%; background: var(--line); }
.step.done::before { background: var(--teal); }
.step.warn { color: var(--amber-ink); }
.step.warn::before { background: var(--amber); }
.step time { margin-left: 4px; }
.ticket-meta { display: flex; gap: 14px; flex-wrap: wrap; color: var(--ink-3); font-size: 12px; }
</style>
